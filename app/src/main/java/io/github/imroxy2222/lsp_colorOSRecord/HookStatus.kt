package io.github.imroxy2222.lsp_colorOSRecord

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 模块自身 ApplicationInfo（ModuleMain.onModuleLoaded 时捕获），用于状态面板显示模块版本。
 * 模块版本只能从模块 APK 反查（模块运行在宿主进程内，宿主 PackageManager 不认识模块包名）。
 */
internal object ModuleInfo {
    @Volatile
    var appInfo: android.content.pm.ApplicationInfo? = null

    private fun archiveInfo(ctx: Context): android.content.pm.PackageInfo? = try {
        val ai = appInfo ?: return null
        ctx.packageManager.getPackageArchiveInfo(ai.sourceDir, 0)
    } catch (_: Throwable) {
        null
    }

    fun versionName(ctx: Context): String? = archiveInfo(ctx)?.versionName

    fun versionCode(ctx: Context): Long? = archiveInfo(ctx)?.let {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
}

/**
 * Hook 点状态注册表 + 「模块信息」诊断面板。
 *
 * 每个 hook/反射目标在定位成功/失败时向 [HookStatus] 上报（found/missing/...），
 * 面板打开时把全部条目连同环境信息（宿主版本、功能开关、队列状态）渲染成
 * 一个浮动 Dialog —— 模块失效时用户截图/复制该页面即可快速定位是宿主哪个
 * 版本改了哪个符号。
 *
 * 状态语义：
 * - PENDING：尚未定位（DexKit 后台定位中 / 菜单尚未 inflate / 摘要懒解析未触发）；
 * - FOUND：已定位并 hook 成功（layer = 层1 / DexKit / 运行时）；
 * - NOT_FOUND：定位失败（对应功能失效）；
 * - OPTIONAL_MISSING：可选加固点未找到（功能可用，缺防重等辅助能力）；
 * - DISABLED：因依赖缺失主动停用。
 */
internal object HookStatus {

    enum class State { PENDING, FOUND, NOT_FOUND, OPTIONAL_MISSING, DISABLED }

    class Entry(
        val id: String,
        val feature: String,    // 对应功能
        val target: String,     // 预期 hook 目标
        @Volatile var state: State = State.PENDING,
        @Volatile var layer: String? = null,   // 定位方式：层1 / DexKit / 运行时
        @Volatile var detail: String? = null,  // 实际定位到的类名 / 失败原因
    )

    private val regLock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    init {
        // 菜单注入链
        register("menu_toolbar", "菜单项注入（hook 入口）", "COUIToolbar#inflateMenu(int)")
        register("menu_click", "菜单点击分发", "BrowseFragment#onOptionsItemSelected(MenuItem)")
        register("menu_items", "菜单项注入结果", "recorder_list_option_browse + 4 个自定义项")
        // 转文本链
        register("trigger", "转文本触发", "ConvertTaskApi#startConvertTask(long)")
        register("check_complete", "防重·已转检测（可选）", "ConvertDbUtil#checkAlreadyConvertComplete(long)")
        register("check_running", "防重·运行中检测（可选）", "ConvertTaskThreadManager#checkIsTaskRunning(long)")
        register("complete_asr", "转写完成事件·在线/本地", "FileAsrController#toastAsrComplete(boolean)")
        register("complete_fake", "转写完成事件·降级链路", "FakeFileAsrController#toastComplete()")
        register(
            "complete_proc",
            "转写完成事件·后台进程",
            "ProcessConvert#toastCompletedConvert(ConvertRecord,boolean)"
        )
        // 转写正文索引链
        register(
            "index_update",
            "转写索引·完成落库",
            "ConvertDbUtil#updateCompleteAndFilePathByRecordId(long,String,Boolean,Integer)"
        )
        register("index_del_id", "转写索引·删除清理", "ConvertDeleteUtil#deleteConvertData(Context,long)")
        register("index_del_path", "转写索引·删除清理", "ConvertDeleteUtil#deleteConvertData(Context,String)")
        // 搜索注入链
        register("search_dao", "搜索注入·摘要关键字查询", "summary.request.database.f#b(String)")
        register("search_entity", "搜索注入·结果伪装", "SummaryCacheEntity 23 参构造")
        register("search_bind", "搜索注入·标签修正（摘要→转文本）", "xg.b#D()")
        // 自动摘要链
        register("summary_common", "自动摘要·公共依赖", "SummaryTheme / PrefUtil / Record / BaseApplication")
        register(
            "summary_pipe",
            "自动摘要·无 UI 管道",
            "AISummaryTaskManager g/h/i/b + 回调接口 u + model.o + database.b#f"
        )
    }

    fun register(id: String, feature: String, target: String) = synchronized(regLock) {
        entries.getOrPut(id) { Entry(id, feature, target) }
    }

    fun found(id: String, layer: String, detail: String? = null) =
        update(id, State.FOUND, layer, detail)

    fun missing(id: String, detail: String? = null) =
        update(id, State.NOT_FOUND, null, detail)

    fun optionalMissing(id: String, detail: String? = null) =
        update(id, State.OPTIONAL_MISSING, null, detail)

    fun disabled(id: String, detail: String? = null) =
        update(id, State.DISABLED, null, detail)

    private fun update(id: String, state: State, layer: String?, detail: String?) =
        synchronized(regLock) {
            entries[id]?.let { e ->
                e.state = state
                layer?.let { e.layer = it }
                detail?.let { e.detail = it }
            }
        }

    fun snapshot(): List<Entry> = synchronized(regLock) { entries.values.toList() }

    // ---------------------------------------------------------------- 诊断面板

    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentDialog: Dialog? = null

    /** 打开浮动状态面板（必须在主线程调用；菜单点击回调天然在主线程）。 */
    fun showPanel(activity: Activity) {
        try {
            // 摘要管道是懒解析，打开面板时顺手触发一次后台解析，几秒后自动刷新显示结果
            SummaryQueue.prefetchResolveAsync()

            val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
            val scroll = ScrollView(activity).apply { setBackgroundColor(Color.WHITE) }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 14))
            }
            scroll.addView(box)
            dialog.setContentView(scroll)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
            )
            dialog.setOnDismissListener { currentDialog = null }
            currentDialog = dialog
            renderContent(activity, box)

            // 有 PENDING 条目（DexKit 定位中 / 摘要懒解析）时自动刷新几次
            var refreshes = 0
            val autoRefresh = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing || refreshes >= 5) return
                    refreshes++
                    if (snapshot().any { it.state == State.PENDING }) {
                        renderContent(activity, box)
                        uiHandler.postDelayed(this, 2500)
                    }
                }
            }
            uiHandler.postDelayed(autoRefresh, 2500)
            dialog.setOnDismissListener {
                uiHandler.removeCallbacks(autoRefresh)
                currentDialog = null
            }

            dialog.show()
        } catch (t: Throwable) {
            XLog.w("status panel show failed", t)
            Toast.makeText(activity, "状态面板打开失败: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderContent(activity: Activity, box: LinearLayout) {
        box.removeAllViews()

        // ---- 标题 + 环境信息 ----
        title(activity, box, "录音增强模块 · 状态")
        val hostVer = try {
            val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
            "${pi.versionName ?: "?"} (${pi.longVersionCode})"
        } catch (_: Throwable) {
            "?"
        }
        val moduleVerCode = ModuleInfo.versionCode(activity)?.toString() ?: "未知"
        text(
            activity, box,
            "录音APP: $hostVer\n" +
                    "模块versionCode: $moduleVerCode\n" +
                    "系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            Color.DKGRAY, 12f, mono = true, bottomPad = 6
        )

        // ---- 功能运行状态 ----
        title(activity, box, "功能状态")
        text(
            activity, box,
            "生成摘要开关: ${if (SummaryPrefs.enabled) "开" else "关"}\n" +
                    "转文本队列: ${ConvertQueue.statusText()}\n" +
                    "摘要队列: ${SummaryQueue.statusText()}",
            Color.DKGRAY, 12f, mono = true, bottomPad = 8
        )

        // ---- Hook 点状态 ----
        title(activity, box, "Hook 点定位状态")
        for (e in snapshot()) {
            box.addView(entryView(activity, e))
        }

        // ---- 操作按钮 ----
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(activity, 10), 0, 0)
        }
        // 蓝=复制（主操作） 青=刷新 红=关闭
        btnRow.addView(button(activity, "复制全部", 0xFF1976D2.toInt()) {
            copyToClipboard(activity, buildPlainText(activity))
        })
        btnRow.addView(button(activity, "刷新", 0xFF00897B.toInt()) { renderContent(activity, box) })
        btnRow.addView(button(activity, "关闭", 0xFFE53935.toInt()) { currentDialog?.dismiss() })
        box.addView(btnRow)
    }

    private fun entryView(activity: Activity, e: Entry): View {
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        }
        val (label, color) = when (e.state) {
            State.FOUND -> "已找到" to 0xFF1B7E3C.toInt()
            State.NOT_FOUND -> "未找到" to 0xFFC62828.toInt()
            State.OPTIONAL_MISSING -> "可选·缺" to 0xFFE65100.toInt()
            State.DISABLED -> "已停用" to 0xFF757575.toInt()
            State.PENDING -> "定位中" to 0xFF1565C0.toInt()
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(newText(activity, label, color, 13f, bold = true))
        head.addView(newText(activity, "  ${e.feature}", 0xFF212121.toInt(), 13f, bold = true))
        col.addView(head)
        col.addView(newText(activity, "目标: ${e.target}", 0xFF616161.toInt(), 11f, mono = true))
        val detailParts = mutableListOf<String>()
        e.layer?.let { detailParts.add("定位: $it") }
        e.detail?.let { detailParts.add(it) }
        if (detailParts.isNotEmpty()) {
            col.addView(
                newText(activity, "实际: ${detailParts.joinToString("  |  ")}", 0xFF616161.toInt(), 11f, mono = true)
            )
        }
        return col
    }

    /** 供"复制全部"按钮的纯文本快照（用户可直接粘贴反馈，比截图更精确）。 */
    private fun buildPlainText(activity: Activity): String = buildString {
        appendLine("=== 录音增强模块状态 ===")
        val hostVer = try {
            val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
            "${pi.versionName ?: "?"} (${pi.longVersionCode})"
        } catch (_: Throwable) {
            "?"
        }
        val moduleVerCode = ModuleInfo.versionCode(activity)?.toString() ?: "未知"
        appendLine("录音APP: $hostVer")
        appendLine("模块versionCode: $moduleVerCode / Android ${android.os.Build.VERSION.RELEASE}")
        appendLine("生成摘要开关: ${if (SummaryPrefs.enabled) "开" else "关"}")
        appendLine("转文本队列: ${ConvertQueue.statusText()}")
        appendLine("摘要队列: ${SummaryQueue.statusText()}")
        for (e in snapshot()) {
            val tag = when (e.state) {
                State.FOUND -> "OK"
                State.NOT_FOUND -> "缺失"
                State.OPTIONAL_MISSING -> "可选缺失"
                State.DISABLED -> "停用"
                State.PENDING -> "定位中"
            }
            append("[$tag] ${e.feature}")
            append(" | 目标: ${e.target}")
            e.layer?.let { append(" | 定位: $it") }
            e.detail?.let { append(" | 实际: $it") }
            appendLine()
        }
    }.trimEnd()

    private fun copyToClipboard(activity: Activity, text: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("lsp_ColorOSRecord status", text))
            Toast.makeText(activity, "状态已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(activity, "复制失败: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ 小部件工厂

    private fun title(activity: Activity, box: LinearLayout, s: String) {
        val tv = newText(activity, s, 0xFF212121.toInt(), 15f, bold = true)
        tv.setPadding(0, dp(activity, 6), 0, dp(activity, 4))
        box.addView(tv)
    }

    private fun text(
        activity: Activity,
        parent: LinearLayout,
        s: String,
        col: Int,
        sizeSp: Float,
        mono: Boolean = false,
        bold: Boolean = false,
        topPad: Int = 0,
        bottomPad: Int = 0,
    ) {
        val tv = newText(activity, s, col, sizeSp, mono, bold)
        tv.setPadding(0, dp(activity, topPad), 0, dp(activity, bottomPad))
        parent.addView(tv)
    }

    private fun newText(
        activity: Activity,
        s: String,
        col: Int,
        sizeSp: Float,
        mono: Boolean = false,
        bold: Boolean = false,
    ): TextView = TextView(activity).apply {
        text = s
        setTextColor(col)
        textSize = sizeSp
        if (mono) typeface = Typeface.MONOSPACE
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /**
     * 彩色圆角胶囊按钮：自绘 GradientDrawable 背景（默认色 / 按压加深色），
     * 不依赖宿主主题资源。关闭 stateListAnimator 去掉 Material 默认投影，
     * minWidth=0 去掉系统 88dp 最小宽。
     */
    private fun button(activity: Activity, label: String, bgColor: Int, onClick: () -> Unit): Button {
        fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, 20).toFloat()
            setColor(color)
        }
        val bg = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(darken(bgColor)))
            addState(intArrayOf(), pill(bgColor))
        }
        return Button(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(activity, 8) }
            setOnClickListener { runCatching(onClick) }
        }
    }

    /** 按压态：整体加深到 85%。 */
    private fun darken(color: Int): Int =
        Color.rgb((color shr 16 and 0xFF) * 85 / 100, (color shr 8 and 0xFF) * 85 / 100, (color and 0xFF) * 85 / 100)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
