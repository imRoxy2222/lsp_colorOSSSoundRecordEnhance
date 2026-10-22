package io.github.imroxy2222.lsp_colorOSRecord

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 模块日志门面（libxposed API 102 的 Xposed 日志）。
 *
 * 日志策略：
 * - **debug 构建**（`BuildConfig.DEBUG == true`）：输出全部调试日志
 *   （hook 安装、层1/层2定位结果、去重跳过、异常堆栈等），便于排查；
 * - **release 构建**：不输出任何调试日志，仅保留 [conversionTriggered]
 *   —— 每次真正下发转文本任务时输出一条。
 */
object XLog {

    const val TAG = "CallRecConvert"

    @Volatile
    private var api: XposedInterface? = null

    /** 在 onModuleLoaded / onPackageReady 时调用，绑定当前模块代的框架接口。 */
    fun attach(api: XposedInterface) {
        this.api = api
    }

    private val debugEnabled: Boolean
        get() = BuildConfig.DEBUG

    fun d(msg: String) {
        if (debugEnabled) api?.log(Log.DEBUG, TAG, msg)
    }

    fun i(msg: String) {
        if (debugEnabled) api?.log(Log.INFO, TAG, msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (debugEnabled) api?.log(Log.WARN, TAG, msg, tr)
    }

    /**
     * release 构建下唯一保留的日志：每次实际触发转文本时输出一条。
     * debug 构建下同样会输出（除此之外还有全部调试日志）。
     */
    fun conversionTriggered(mediaId: Long) {
        api?.log(Log.INFO, TAG, "startConvertTask, mediaId=$mediaId")
    }
}
