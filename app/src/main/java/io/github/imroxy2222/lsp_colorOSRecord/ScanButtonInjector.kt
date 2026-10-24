package io.github.imroxy2222.lsp_colorOSRecord

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * 在录音列表（BrowseFragment）右上角展开菜单里注入"转文本"/"转全部"/"生成摘要:开/关"
 * /"模块诊断"四个菜单项，位于"设置"下方。
 *
 * 原理（17.8.8 反编译结论）：
 * - BrowseFragment 工具栏 inflate 的是 R.menu.recorder_list_option_browse，
 *   其中 showAsAction="never" 的 item_edit(编辑)/item_speaker(扬声器播放)/item_setting(设置)
 *   组成右上角展开菜单；COUIActionMenuView.tryBuildOverflowMenu() 在每次点开时
 *   从 menu 的 non-action items 重建弹窗 —— 所以只要往 menu 里追加 never 项即可。
 * - 菜单项点击由 configToolbar 的协程统一转发到 BrowseFragment.onOptionsItemSelected(MenuItem)，
 *   hook 该方法拦截自定义 itemId 即可处理点击；即使我们设置的 per-item listener
 *   被该协程覆盖（repeatOnLifecycle 每次 RESUMED 都会重设），也仍会走到这里。
 *
 * 两个菜单项：
 * - **转文本**：扫全部录音（最新优先，含标准/会议/采访/通话等全部类型，排除回收站），
 *   跳过已转完的，取最多 10 条未转的批量触发；
 * - **转全部**：同上但不设上限，把库里所有未转的录音全部入队。
 * 扫描在后台线程完成（每条仅 1~2 次 SQLite 查询，无性能压力）；转文本本身由
 * ConvertQueue 串行驱动（完成事件 + 3s 间隔），"转全部"只是队列更长、不更重。
 *
 * 另注入开关项 **生成摘要:开/关**（SummaryPrefs 持久化，默认开）：打开时每条
 * 转文本完成后自动接入 SummaryQueue 生成 AI 摘要（无 UI 走官方 AISummaryTaskManager
 * 管道，与播放页 tv_start_summary 同链路），串行一条一条生成、条间间隔防云端 QPS 限流。
 *
 * 以及 **模块诊断**：打开 HookStatus 诊断浮层（宿主/模块版本、各 hook 点定位状态、
 * 队列运行状态，支持复制文本），模块失效时用户截图即可快速定位漂移的符号。
 *
 * mediaId 用 MediaDBUtils.queryIdByData(路径) 拿 MediaStore _id，
 * 调 trigger.onMediaIdSynced 触发转文本；trigger 内部有 checkAlreadyConvertComplete
 * + 60s 去重兜底，队列 drain 时仍会再跳过已转/运行中的条目。
 *
 * 设计原因：入库自动触发路线（hook RecorderProvider 写库）实测走不通，已删除；
 * 录音机 App 进程内点菜单批量触发是唯一转文本入口，进程必然存活。
 */
class ScanButtonInjector(
    private val api: XposedInterface,
    private val param: XposedModuleInterface.PackageReadyParam
) {

    fun install() {
        installMenuItemHook()
        installClickHook()
    }

    /**
     * Hook `COUIToolbar.inflateMenu(int)`：任何工具栏 inflate 菜单后，
     * 若菜单是 recorder_list_option_browse（BrowseFragment 的列表菜单），
     * 则追加"转文本"/"转全部"两个 never 项。Fragment 视图重建（分屏/折叠切换）
     * 时菜单会重新 inflate，此 hook 每次都能补上，比挂在 Activity.onResume 更稳。
     */
    private fun installMenuItemHook() {
        val cl = param.classLoader
        val method: Method = try {
            Class.forName(CLS_COUI_TOOLBAR, false, cl)
                .getDeclaredMethod("inflateMenu", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            XLog.w("COUIToolbar.inflateMenu not found: ${t.message}")
            HookStatus.missing("menu_toolbar", "定位失败: ${t.message}（菜单功能整体失效）")
            return
        }

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val resId = chain.args.getOrNull(0) as? Int
                    if (resId != null) {
                        addMenuItemIfBrowseMenu(chain.thisObject, resId)
                    }
                } catch (t: Throwable) {
                    XLog.w("menu item inject failed", t)
                }
                result
            }
        XLog.i("hooked COUIToolbar.inflateMenu(int) [scan-menu]")
        HookStatus.found("menu_toolbar", "层1", "实际: ${method.declaringClass.name}")
    }

    /**
     * Hook `BrowseFragment.onOptionsItemSelected(MenuItem)`：拦截我们的 itemId，
     * 执行扫描逻辑，不调用原方法（原方法对我们未知 id 本来也只 return true）。
     * 「生成摘要」项在这里做开关切换，并同步刷新当前菜单里的标题。
     */
    private fun installClickHook() {
        val cl = param.classLoader
        val method: Method = try {
            Class.forName(CLS_BROWSE_FRAGMENT, false, cl)
                .getDeclaredMethod("onOptionsItemSelected", MenuItem::class.java)
        } catch (t: Throwable) {
            XLog.w("BrowseFragment.onOptionsItemSelected not found: ${t.message}")
            HookStatus.missing("menu_click", "定位失败: ${t.message}（菜单点击无响应）")
            return
        }

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val item = chain.args.getOrNull(0) as? MenuItem
                when (item?.itemId) {
                    MENU_ID_SCAN, MENU_ID_ALL -> {
                        val limit = if (item.itemId == MENU_ID_SCAN) SCAN_LIMIT else Int.MAX_VALUE
                        try {
                            val activity = findFragmentActivity(chain.thisObject)
                            if (activity != null) {
                                onScanClicked(activity, limit)
                            }
                        } catch (t: Throwable) {
                            XLog.w("scan menu click handler error", t)
                        }
                        true
                    }

                    MENU_ID_SUMMARY -> {
                        try {
                            val activity = findFragmentActivity(chain.thisObject)
                            if (activity != null) {
                                val on = SummaryPrefs.toggle(activity)
                                // MenuItem 实例常驻 Menu，改标题后下次展开菜单也是新状态
                                item.setTitle(SummaryPrefs.title())
                                Toast.makeText(
                                    activity,
                                    if (on) "转文本完成后自动生成摘要：已开启"
                                    else "转文本完成后自动生成摘要：已关闭",
                                    Toast.LENGTH_SHORT
                                ).show()
                                XLog.i("auto summary toggled -> $on")
                            }
                        } catch (t: Throwable) {
                            XLog.w("summary toggle handler error", t)
                        }
                        true
                    }

                    MENU_ID_SETTINGS -> {
                        try {
                            val activity = findFragmentActivity(chain.thisObject)
                            if (activity != null) {
                                HookStatus.showPanel(activity)
                            }
                        } catch (t: Throwable) {
                            XLog.w("status panel open error", t)
                        }
                        true
                    }

                    else -> chain.proceed()
                }
            }
        HookStatus.found("menu_click", "层1", "实际: ${method.declaringClass.name}")
        XLog.i("hooked BrowseFragment.onOptionsItemSelected(MenuItem) [scan-menu]")
    }

    /** 仅对 BrowseFragment 的列表菜单注入；其余工具栏菜单（通话列表、编辑页等）不动。 */
    private fun addMenuItemIfBrowseMenu(toolbarObj: Any?, resId: Int) {
        val toolbar = toolbarObj as? View ?: return
        val menuName = try {
            toolbar.resources.getResourceEntryName(resId)
        } catch (t: Throwable) {
            null
        }
        if (menuName != MENU_BROWSE) return

        SummaryPrefs.ensureLoaded(toolbar.context)

        // COUIToolbar 继承 androidx Toolbar，getMenu() 反射拿 Menu（模块不依赖宿主类）
        val menu = try {
            toolbar.javaClass.getMethod("getMenu").invoke(toolbar) as? Menu
        } catch (t: Throwable) {
            XLog.w("toolbar.getMenu failed: ${t.message}")
            null
        } ?: return

        // 防重复注入；已注入过时仅刷新摘要开关标题（状态可能被点击切换过）
        if (menu.findItem(MENU_ID_SCAN) != null) {
            menu.findItem(MENU_ID_SUMMARY)?.setTitle(SummaryPrefs.title())
            return
        }

        menu.add(Menu.NONE, MENU_ID_SCAN, ORDER_SCAN, TITLE_SCAN)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_ID_ALL, ORDER_ALL, TITLE_ALL)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_ID_SUMMARY, ORDER_SUMMARY, SummaryPrefs.title())
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_ID_SETTINGS, ORDER_SETTINGS, TITLE_SETTINGS)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        HookStatus.found(
            "menu_items", "运行时",
            "菜单项 x4 已注入（转文本/转全部/${SummaryPrefs.title()}/模块诊断）"
        )
        XLog.i(
            "menu items \"$TITLE_SCAN\"/\"$TITLE_ALL\"/\"${SummaryPrefs.title()}\"/\"$TITLE_SETTINGS\" " +
                    "injected into $menuName"
        )
    }

    /** 从 Fragment 实例反射取宿主 Activity。 */
    private fun findFragmentActivity(fragmentObj: Any?): Activity? {
        return try {
            fragmentObj?.javaClass?.getMethod("getActivity")?.invoke(fragmentObj) as? Activity
        } catch (t: Throwable) {
            XLog.d("fragment.getActivity failed: ${t.message}")
            null
        }
    }

    /**
     * 防重入标记：点击后置位，扫描线程入队完毕（或失败）后复位。
     * 与 [ConvertQueue.isActive] 配合，双重防止批量进行中重复点击
     * 造成队列清空重建 / 并发转文本 / 计数错乱。
     * 在主线程点击回调里同步检查（点击事件本身串行），无竞态。
     */
    @Volatile
    private var scanning = false

    /**
     * 扫描全部录音并批量入队转文本。
     *
     * 从最新往旧遍历所有录音（record_type IN (0,1,2,3,5,6,7)，即官方
     * RecordModeConstant.ALL_RECORD_TYPE_LIST 的全部类型：标准/会议/采访/通话/
     * 其他/互传/手表；排除回收站 is_recycle=0），**跳过已转完的**
     * （ConvertDbUtil.checkAlreadyConvertComplete），只把未转的入队，
     * 凑满 [limit] 条即停（转全部时 [limit]=Int.MAX_VALUE 不停）。
     * 这样"转文本"拿到的一定是最新 10 条**未转**的，而不是"最新 10 条里恰好没转过"的。
     *
     * 转文本管道（startConvertTask → FileAsrController）对所有类型通用：
     * 官方 ConvertSupportManager.isSupportConvert 只做设备能力检查，不限 record_type，
     * 播放页对标准/会议录音同样提供"转文本"按钮。
     */
    private fun onScanClicked(activity: Activity, limit: Int) {
        val trigger = TriggerHolder.trigger
        if (trigger == null) {
            Toast.makeText(activity, "转文本引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        // 防重入：批量进行中（或上一次扫描还没入队完）再点直接忽略。
        // 进行中的转文本任务无法取消，重建队列会导致新旧两条并发转换、易被限流。
        if (scanning || ConvertQueue.isActive()) {
            XLog.i("scan ignored: batch already in progress (scanning=$scanning)")
            Toast.makeText(activity, "已有转文本任务进行中，请等待完成", Toast.LENGTH_SHORT).show()
            return
        }
        scanning = true
        val convertAll = limit == Int.MAX_VALUE
        Toast.makeText(
            activity,
            if (convertAll) "开始扫描全部录音…" else "开始扫描录音…",
            Toast.LENGTH_SHORT
        ).show()
        Thread {
            try {
                XLog.i("scan menu clicked (limit=$limit), skipping converted records")
                val cl = param.classLoader
                val mediaDbCls = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
                val queryIdByData = mediaDbCls.getMethod(M_QUERY_ID_BY_DATA, String::class.java)
                val resolver = activity.contentResolver
                // 不在 URI 上做 limit：需要跳过已转条目后再数满 limit，SQLite 端无法预知
                val queryUri = Uri.parse(
                    "content://$RECORDER_PROVIDER_AUTHORITY/$RECORDER_PATH_RECORDS"
                )
                val cursor = resolver.query(
                    queryUri,
                    arrayOf(COL_DATA, COL_DISPLAY_NAME),
                    // 全部录音类型（官方 ALL_RECORD_TYPE_LIST）+ 排除回收站；
                    // Provider 的 query 把 selection 直接透传给 SQLite（无内置过滤）
                    "$COL_RECORD_TYPE IN ($SQL_ALL_RECORD_TYPES) AND $COL_IS_RECYCLE = 0",
                    null,
                    "$COL_ID DESC"
                )
                // 开启一轮批量：清空旧队列，绑定上下文；队列内部逐条驱动，
                // 每条转完由 FileAsrController.toastAsrComplete 等 hook 回调推进，
                // 不再用固定 sleep —— 长录音也会等到真正转完才继续。
                ConvertQueue.startBatch(activity, trigger)
                var enqueued = 0
                var converted = 0
                var skipped = 0
                cursor?.use { c ->
                    val dataIdx = c.getColumnIndex(COL_DATA)
                    while (c.moveToNext()) {
                        val data = if (dataIdx >= 0) c.getString(dataIdx) else null
                        if (data.isNullOrEmpty()) continue
                        var mediaId = -1L
                        try {
                            mediaId = queryIdByData.invoke(null, data) as? Long ?: -1L
                        } catch (t: Throwable) {
                            XLog.d("queryIdByData failed for $data: ${t.message}")
                        }
                        if (mediaId <= 0L) {
                            skipped++
                            continue
                        }
                        // 已转完的不占名额，继续往更旧的记录找
                        if (trigger.isConverted(mediaId)) {
                            converted++
                            continue
                        }
                        XLog.d("scan[${enqueued + 1}]: mediaId=$mediaId data=$data")
                        ConvertQueue.enqueue(mediaId)
                        enqueued++
                        if (enqueued >= limit) break
                    }
                }
                val msg = buildString {
                    append("扫描完成：待转 $enqueued 条")
                    if (converted > 0) append("，已转 $converted 条")
                    if (skipped > 0) append("，跳过 $skipped 条")
                }
                XLog.i(msg)
                if (enqueued == 0) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(activity, "没有需要转文本的录音", Toast.LENGTH_LONG).show()
                    }
                }
                // 队列自行驱动后续条目，无需在此 sleep
            } catch (t: Throwable) {
                XLog.w("scan failed", t)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, "扫描失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                scanning = false
            }
        }.apply {
            isDaemon = true
            name = "RecConvert-Scan"
        }.start()
    }

    companion object {
        private const val CLS_COUI_TOOLBAR = "com.coui.appcompat.toolbar.COUIToolbar"
        private const val CLS_BROWSE_FRAGMENT = "com.soundrecorder.browsefile.home.BrowseFragment"

        /** BrowseFragment 列表菜单资源名（含 编辑/扬声器播放/设置 的那个）。 */
        private const val MENU_BROWSE = "recorder_list_option_browse"
        private const val TITLE_SCAN = "转文本"
        private const val TITLE_ALL = "转全部"
        private const val TITLE_SETTINGS = "模块诊断"

        /**
         * 扫描覆盖的全部录音类型，即官方 RecordModeConstant.ALL_RECORD_TYPE_LIST：
         * 0=标准 1=会议 2=采访 3=通话 5=其他 6=互传 7=手表。
         */
        private const val SQL_ALL_RECORD_TYPES = "0,1,2,3,5,6,7"

        /** 回收站标记列（DatabaseConstant.RecorderColumn.COLUMN_NAME_IS_RECYCLE），扫描时排除。 */
        private const val COL_IS_RECYCLE = "is_recycle"

        /** "转文本"：跳过已转后最多入队条数。 */
        private const val SCAN_LIMIT = 10

        /**
         * 自定义菜单 itemId。取 0x1A2Bxxxx 避开资源 id 区间
         * （android 系统资源 0x01xxxxxx、宿主 App 资源 0x7fxxxxxx），不会撞车。
         */
        private const val MENU_ID_SCAN = 0x1A2B3C4D
        private const val MENU_ID_ALL = 0x1A2B3C4E
        private const val MENU_ID_SUMMARY = 0x1A2B3C4F
        private const val MENU_ID_SETTINGS = 0x1A2B3C50

        /**
         * 排序值：XML 内所有 item 的 orderInCategory 均为 0，
         * 取正数保证排在"设置"之后（列表最后，转文本在转全部之上）。
         * 通话录音分组视图里会多出"分组排序"（同为 order 0），此时我们的项排在其后。
         */
        private const val ORDER_SCAN = 100
        private const val ORDER_ALL = 101
        private const val ORDER_SUMMARY = 102
        private const val ORDER_SETTINGS = 103
    }
}
