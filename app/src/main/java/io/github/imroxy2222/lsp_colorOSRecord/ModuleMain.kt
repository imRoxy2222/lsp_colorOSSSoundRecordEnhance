package io.github.imroxy2222.lsp_colorOSRecord

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed 模块入口（libxposed 现代 API，API 102）。
 *
 * 入口类名同时登记在 `src/main/resources/META-INF/xposed/java_init.list`。
 *
 * 功能（hook2.md）：通话录音（电话 / QQ 电话 / 微信电话，系统录音广播同步链路）
 * 入库后自动触发转文本；手动录音走的是另一条链路，不受影响。
 *
 * - 无界面、无桌面图标；
 * - 作用域：`com.coloros.soundrecorder`（见 scope.list）。
 */
class ModuleMain : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.attach(this)
        // 状态面板需要模块自身版本（模块运行在宿主进程，宿主包管理器不认识模块包名）
        runCatching { ModuleInfo.appInfo = moduleApplicationInfo }
        // 触发 HookStatus 注册表初始化（登记全部 hook 点条目）
        runCatching { HookStatus.snapshot() }
        XLog.i(
            "module loaded: process=${param.processName}, " +
                "framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        XLog.attach(this)
        if (param.packageName != TARGET_PACKAGE) {
            XLog.d("ignore package: ${param.packageName}")
            return
        }
        XLog.i(
            "package ready: ${param.packageName}, firstPackage=${param.isFirstPackage}, " +
                "apk=${param.applicationInfo.sourceDir}"
        )
        HookInstaller(this, param).install()
    }

    companion object {
        const val TARGET_PACKAGE = "com.coloros.soundrecorder"
    }
}
