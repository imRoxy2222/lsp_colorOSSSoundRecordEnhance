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
 * 在录音列表（BrowseFragment）右上角展开菜单里注入"转文本"菜单项，位于"设置"下方。
 *
 * 原理（17.8.8 反编译结论）：
 * - BrowseFragment 工具栏 inflate 的是 R.menu.recorder_list_option_browse，
 *   其中 showAsAction="never" 的 item_edit(编辑)/item_speaker(扬声器播放)/item_setting(设置)
 *   组成右上角展开菜单；COUIActionMenuView.tryBuildOverflowMenu() 在每次点开时
 *   从 menu 的 non-action items 重建弹窗 —— 所以只要往 menu 里追加一个 never 项即可。
 * - 菜单项点击由 configToolbar 的协程统一转发到 BrowseFragment.onOptionsItemSelected(MenuItem)，
 *   hook 该方法拦截自定义 itemId 即可处理点击；即使我们设置的 per-item listener
 *   被该协程覆盖（repeatOnLifecycle 每次 RESUMED 都会重设），也仍会走到这里。
 *
 * 触发流程（与原浮动按钮一致）：后台扫 RecorderDB 最近 10 条 record_type==3（通话录音），
 * 用 MediaDBUtils.queryIdByData(路径) 拿 MediaStore _id，调 trigger.onMediaIdSynced 触发转文本。
 * trigger 内部有 checkAlreadyConvertComplete + 60s 去重，已转的自动跳过。
 *
 * 设计原因：VoIP 通话录音（QQ/微信）结束时录音机进程不运行，模块无法自动触发。
 * 改为用户打开录音机 App 后，在展开菜单点一下"转文本"批量触发，进程肯定存活。
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
     * 则追加"转文本"never 项。Fragment 视图重建（分屏/折叠切换）时菜单会重新
     * inflate，此 hook 每次都能补上，比挂在 Activity.onResume 更稳。
     */
    private fun installMenuItemHook() {
        val cl = param.classLoader
        val method: Method = try {
            Class.forName(CLS_COUI_TOOLBAR, false, cl)
                .getDeclaredMethod("inflateMenu", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            XLog.w("COUIToolbar.inflateMenu not found: ${t.message}")
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
    }

    /**
     * Hook `BrowseFragment.onOptionsItemSelected(MenuItem)`：拦截我们的 itemId，
     * 执行扫描逻辑，不调用原方法（原方法对我们未知 id 本来也只 return true）。
     */
    private fun installClickHook() {
        val cl = param.classLoader
        val method: Method = try {
            Class.forName(CLS_BROWSE_FRAGMENT, false, cl)
                .getDeclaredMethod("onOptionsItemSelected", MenuItem::class.java)
        } catch (t: Throwable) {
            XLog.w("BrowseFragment.onOptionsItemSelected not found: ${t.message}")
            return
        }

        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val item = chain.args.getOrNull(0) as? MenuItem
                if (item != null && item.itemId == MENU_ITEM_ID) {
                    try {
                        val activity = findFragmentActivity(chain.thisObject)
                        if (activity != null) {
                            onScanClicked(activity)
                        }
                    } catch (t: Throwable) {
                        XLog.w("scan menu click handler error", t)
                    }
                    true
                } else {
                    chain.proceed()
                }
            }
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

        // COUIToolbar 继承 androidx Toolbar，getMenu() 反射拿 Menu（模块不依赖宿主类）
        val menu = try {
            toolbar.javaClass.getMethod("getMenu").invoke(toolbar) as? Menu
        } catch (t: Throwable) {
            XLog.w("toolbar.getMenu failed: ${t.message}")
            null
        } ?: return

        // 防重复注入
        if (menu.findItem(MENU_ITEM_ID) != null) return

        val item = menu.add(Menu.NONE, MENU_ITEM_ID, ORDER_LAST, MENU_TITLE)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        XLog.i("menu item \"$MENU_TITLE\" injected into $menuName")
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

    private fun onScanClicked(activity: Activity) {
        val trigger = TriggerHolder.trigger
        if (trigger == null) {
            Toast.makeText(activity, "转文本引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "开始扫描通话录音…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                XLog.i("scan menu clicked, scanning latest 10 call records")
                val cl = param.classLoader
                val mediaDbCls = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
                val queryIdByData = mediaDbCls.getMethod(M_QUERY_ID_BY_DATA, String::class.java)
                val resolver = activity.contentResolver
                val queryUri = Uri.parse(
                    "content://$RECORDER_PROVIDER_AUTHORITY/$RECORDER_PATH_RECORDS?limit=10"
                )
                val cursor = resolver.query(
                    queryUri,
                    arrayOf(COL_DATA, COL_DISPLAY_NAME),
                    "$COL_RECORD_TYPE = ?",
                    arrayOf(TYPE_CALL.toString()),
                    "$COL_ID DESC"
                )
                // 开启一轮批量：清空旧队列，绑定上下文；队列内部逐条驱动，
                // 每条转完由 FileAsrController.toastAsrComplete 等 hook 回调推进，
                // 不再用固定 sleep —— 长录音也会等到真正转完才继续。
                ConvertQueue.startBatch(activity, trigger)
                var scanned = 0
                var enqueued = 0
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
                        scanned++
                        XLog.d("scan[$scanned]: mediaId=$mediaId data=$data")
                        ConvertQueue.enqueue(mediaId)
                        enqueued++
                    }
                }
                val msg = "扫描完成：共 $scanned 条，入队 $enqueued 条" +
                    if (skipped > 0) "，跳过 $skipped 条" else ""
                XLog.i(msg)
                if (enqueued == 0) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(activity, "没有需要转文本的通话录音", Toast.LENGTH_LONG).show()
                    }
                }
                // 队列自行驱动后续条目，无需在此 sleep
            } catch (t: Throwable) {
                XLog.w("scan failed", t)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, "扫描失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            isDaemon = true
            name = "CallRecConvert-Scan"
        }.start()
    }

    companion object {
        private const val CLS_COUI_TOOLBAR = "com.coui.appcompat.toolbar.COUIToolbar"
        private const val CLS_BROWSE_FRAGMENT = "com.soundrecorder.browsefile.home.BrowseFragment"

        /** BrowseFragment 列表菜单资源名（含 编辑/扬声器播放/设置 的那个）。 */
        private const val MENU_BROWSE = "recorder_list_option_browse"
        private const val MENU_TITLE = "转文本"

        /**
         * 自定义菜单 itemId。取 0x1A2B3C4D 避开资源 id 区间
         * （android 系统资源 0x01xxxxxx、宿主 App 资源 0x7fxxxxxx），不会撞车。
         */
        private const val MENU_ITEM_ID = 0x1A2B3C4D

        /**
         * 排序值：XML 内所有 item 的 orderInCategory 均为 0，
         * 取正数保证排在"设置"之后（列表最后）。
         * 通话录音分组视图里会多出"分组排序"（同为 order 0），此时我们的项排在其后。
         */
        private const val ORDER_LAST = 100
    }
}
