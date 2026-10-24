package io.github.imroxy2222.lsp_colorOSRecord

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ---------------------------------------------------------------- 目标常量表
// 顶层 internal 常量：HookInstaller 与 DexKitResolver 共用。

/** 层1 直接定位的已知类名（本 app 的 Kotlin 业务类大多未混淆）。 */
internal const val CLS_CONVERT_TASK_API = "com.soundrecorder.convertservice.api.ConvertTaskApi"
internal const val CLS_CONVERT_DB_UTIL = "com.soundrecorder.common.utils.ConvertDbUtil"
internal const val CLS_CONVERT_TASK_THREAD_MANAGER =
    "com.soundrecorder.convertservice.convert.ConvertTaskThreadManager"
internal const val CLS_RECORD = "com.soundrecorder.common.databean.Record"
internal const val CLS_MEDIA_DB_UTILS = "com.soundrecorder.common.db.MediaDBUtils"

/** Kotlin object 单例字段名。 */
internal const val F_INSTANCE = "INSTANCE"

/** 目标方法名。 */
internal const val M_START_CONVERT_TASK = "startConvertTask"
internal const val M_CHECK_ALREADY_CONVERT_COMPLETE = "checkAlreadyConvertComplete"
internal const val M_CHECK_IS_TASK_RUNNING = "checkIsTaskRunning"

/** 转文本完成回调点：弹出"xxxx转文本已完成"toast 的方法（反推自 R.string.convert_end）。 */
internal const val CLS_FILE_ASR_CONTROLLER = "com.soundrecorder.convertservice.convert.asr.FileAsrController"
internal const val CLS_FAKE_FILE_ASR_CONTROLLER = "com.soundrecorder.convertservice.convert.fake.FakeFileAsrController"
internal const val CLS_PROCESS_CONVERT = "com.soundrecorder.convertservice.process.ProcessConvert"
internal const val CLS_CONVERT_RECORD = "com.soundrecorder.common.databean.ConvertRecord"
internal const val M_TOAST_ASR_COMPLETE = "toastAsrComplete"
internal const val M_TOAST_COMPLETE = "toastComplete"
internal const val M_TOAST_COMPLETED_CONVERT = "toastCompletedConvert"
internal const val M_GET_MEDIA_ID = "getMediaId"
internal const val M_GET_RECORD_ID = "getRecordId"

/** ContentValues 列名（扫描查询用）。 */
internal const val COL_RECORD_TYPE = "record_type"
internal const val COL_DISPLAY_NAME = "display_name"

/** MediaDBUtils 直接按文件路径查 MediaStore _id（比反查 Record 更简单可靠）。 */
internal const val M_QUERY_ID_BY_DATA = "queryIdByData"

/** RecorderProvider 查询用常量。 */
internal const val RECORDER_PROVIDER_AUTHORITY = "com.coloros.soundrecorder.provider"
internal const val RECORDER_PATH_RECORDS = "records"
internal const val COL_DATA = "_data"
internal const val COL_ID = "_id"

// ---------------------------------------------------------------- 转写正文搜索索引（search-transcript.md）

/** 转写完成落库口（4 参重载；3 参重载内部转发到它，hook 一个全覆盖）。 */
internal const val M_UPDATE_COMPLETE_AND_FILE_PATH = "updateCompleteAndFilePathByRecordId"

/** 转写数据删除入口（convert 表 + txt 文件 + 关键词索引一起删）。 */
internal const val CLS_CONVERT_DELETE_UTIL = "com.soundrecorder.common.db.ConvertDeleteUtil"
internal const val M_DELETE_CONVERT_DATA = "deleteConvertData"

/** ConvertDbUtil 反查（索引器由 mediaId 找 txt 路径 / 由媒体路径找 recordId）。 */
internal const val M_SELECT_BY_RECORD_ID = "selectByRecordId"
internal const val M_SELECT_BY_MEDIA_PATH = "selectByMediaPath"
internal const val M_GET_CONVERT_TEXTFILE_PATH = "getConvertTextfilePath"

/** 摘要关键字查询（搜索注入点）：17.8.8 的 DAO 封装类与 helper，均为混淆名、跨版本会漂移。 */
internal const val CLS_SUMMARY_DAO = "com.soundrecorder.summary.request.database.f"
internal const val CLS_SUMMARY_DB_HELPER = "com.soundrecorder.summary.request.database.b"
internal const val CLS_SUMMARY_CACHE_ENTITY = "com.soundrecorder.summary.request.database.SummaryCacheEntity"

/** f.b(String) 内的 SQL（dex 常量，全局唯一，DexKit 兜底锚点）。 */
internal const val ANCHOR_SUMMARY_KEYWORD_QUERY =
    "SELECT * FROM summary_cache_data WHERE INSTR(summary_content, ?) > 0 AND choose_state = 0"

/** 搜索结果条目 ViewHolder（17.8.8 混淆类名 xg.b）及其绑定方法 D()（browsefile.home.item.h.D 的实现）。 */
internal const val CLS_ITEM_SEARCH_VIEW = "xg.b"
internal const val M_BIND_SEARCH_ITEM = "D"

/** xg.b 构造器内注册点击回调的日志串（dex 常量，全局唯一，DexKit 定位兜底）。 */
internal const val ANCHOR_SEARCH_ITEM_VIEW = "convert click isQuickClick"

/** 全局触发器持有者：ScanButtonInjector 通过它拿 trigger 调用转文本。 */
internal object TriggerHolder {
    @Volatile
    var trigger: TriggerHandle? = null
}

/**
 * Hook 安装器：定位目标方法并安装 hook。
 *
 * 触发架构（转文本唯一入口 = 列表菜单"转文本/转全部"，见 ScanButtonInjector）：
 * - 入库自动触发（hook RecorderProvider.insert/bulkInsert）**已废弃删除**：实测该路线
 *   走不通（入库时机与 MediaStore 同步不同步，触发不可靠），保留菜单手动批量入口；
 * - 菜单扫描后 ConvertQueue 串行驱动，经 [TriggerHandle] 反射 startConvertTask；
 * - 转写完成由三个 toast 回调 hook 推进队列（覆盖在线/离线/后台三条链路）。
 *
 * 定位策略（hook2.md §6 分层）：
 * - **层1**：直接 `Class.forName` 已知未混淆类名（本 app 的 Kotlin 业务类大多保留原名）；
 * - **层2**：层1 trigger 定位失败时，用 DexKit 按结构特征重新定位，跨版本也能工作。
 *
 * 线程说明：层1 很快，直接在 onPackageReady 调用线程完成；
 * 层2（DexKit 全量解析 APK）较慢，放后台线程执行，避免拖慢 App 启动。
 */
class HookInstaller(
    private val api: XposedInterface,
    private val param: XposedModuleInterface.PackageReadyParam
) {

    fun install() {
        val cl = param.classLoader

        // ---------- 层1：未混淆类名直接定位 trigger ----------
        var trigger = resolveTriggerDirect(cl)
        XLog.d("layer1: trigger=${trigger != null}")
        if (trigger != null) {
            HookStatus.found("trigger", "层1", "触发器就绪")
        }

        if (trigger == null) {
            // ---------- 层2：DexKit 兜底（后台线程，避免阻塞 App 启动） ----------
            XLog.i("layer1 incomplete, fallback to DexKit")
            Thread {
                var resolved: TriggerHandle? = null
                try {
                    DexKitResolver(cl, param.applicationInfo.sourceDir).use { resolver ->
                        resolved = resolver.findTrigger()
                    }
                } catch (t: Throwable) {
                    XLog.w("dexkit fallback failed", t)
                    HookStatus.missing("trigger", "DexKit 定位异常: ${t.message}")
                }
                if (resolved != null) {
                    HookStatus.found("trigger", "DexKit", "触发器就绪（层1失败后兜底定位）")
                } else if (HookStatus.snapshot().first { it.id == "trigger" }.state == HookStatus.State.PENDING) {
                    HookStatus.missing("trigger", "层1 + DexKit 均未定位到 startConvertTask")
                }
                finishInstall(resolved)
            }.apply {
                isDaemon = true
                name = "RecConvert-DexKit"
            }.start()
        } else {
            finishInstall(trigger)
        }
    }

    private fun finishInstall(trigger: TriggerHandle?) {
        if (trigger == null) {
            XLog.w("trigger unresolved, module inactive")
            HookStatus.missing("trigger", "触发器未定位，模块整体失效")
            return
        }

        // 存 trigger 供 ScanButtonInjector（一键转文本按钮）使用
        TriggerHolder.trigger = trigger
        // 转文本完成回调 hook：驱动 ConvertQueue 逐条推进（替代固定 sleep）
        installConvertCompleteHooks()
        // 注入一键转文本按钮到录音列表界面
        ScanButtonInjector(api, param).install()

        // -------- 转写正文搜索索引（search-transcript.md） --------
        ConvertIndexer.attachClassLoader(param.classLoader)
        // 摘要队列：宿主 classLoader + APK 路径（层2 DexKit 定位混淆符号用）
        SummaryQueue.attachClassLoader(param.classLoader, param.applicationInfo.sourceDir)
        // 层一：转写完成落库 → 索引该 txt；层二：删除 → 清索引
        installConvertIndexHooks()
        // 搜索注入：摘要关键字查询 after-hook 混入转写正文命中
        SearchInjector(api, param).install()
    }

    // ---------------------------------------------------------------- 层1：直接定位

    private fun resolveTriggerDirect(cl: ClassLoader): TriggerHandle? {
        return try {
            val apiClazz = Class.forName(CLS_CONVERT_TASK_API, false, cl)
            val instance = apiClazz.getField(F_INSTANCE).get(null) ?: return null
            val method = apiClazz.getMethod(M_START_CONVERT_TASK, java.lang.Long.TYPE)
            buildTrigger(instance, method, cl)
        } catch (t: Throwable) {
            XLog.d("layer1 trigger not found: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------- 组装 TriggerHandle

    private fun buildTrigger(instance: Any, method: Method, cl: ClassLoader): TriggerHandle {
        val checkComplete = resolveCheckCompleteDirect(cl)
        val checkRunning = resolveCheckRunningDirect(cl)
        return TriggerHandle(instance, method, checkComplete, checkRunning?.first, checkRunning?.second)
    }

    private fun resolveCheckCompleteDirect(cl: ClassLoader): Method? {
        return try {
            Class.forName(CLS_CONVERT_DB_UTIL, false, cl)
                .getMethod(M_CHECK_ALREADY_CONVERT_COMPLETE, java.lang.Long.TYPE)
                .also { HookStatus.found("check_complete", "层1") }
        } catch (t: Throwable) {
            XLog.d("layer1 checkAlreadyConvertComplete not found: ${t.message}")
            HookStatus.optionalMissing("check_complete", t.message)
            null
        }
    }

    private fun resolveCheckRunningDirect(cl: ClassLoader): Pair<Any, Method>? {
        return try {
            val clazz = Class.forName(CLS_CONVERT_TASK_THREAD_MANAGER, false, cl)
            val instance = clazz.getField(F_INSTANCE).get(null) ?: return null
            val method = clazz.getMethod(M_CHECK_IS_TASK_RUNNING, java.lang.Long.TYPE)
            HookStatus.found("check_running", "层1")
            instance to method
        } catch (t: Throwable) {
            XLog.d("layer1 checkIsTaskRunning not found: ${t.message}")
            HookStatus.optionalMissing("check_running", t.message)
            null
        }
    }

    // ---------------------------------------------------------------- 转文本完成回调 hook

    /**
     * 转文本完成回调 hook（反推自 R.string.convert_end = "xxxx转文本已完成"）。
     *
     * 弹出完成 toast 的方法有三处（覆盖在线/离线/后台进程三条转文本链路）：
     * - [FileAsrController.toastAsrComplete]（public，在线/本地 ASR 主链路）
     * - [FakeFileAsrController.toastComplete]（private 无参，假 ASR 降级链路）
     * - [ProcessConvert.toastCompletedConvert]（private，后台转文本进程）
     *
     * after-hook 时从实例/参数取 mediaId，回调 [ConvertQueue.onConvertComplete]，
     * 队列据此延迟后再推进下一条 —— 替代早期固定随机 sleep，
     * 长录音也会等到真正转完才继续，不再因时长不足漏转。
     */
    private fun installConvertCompleteHooks() {
        installFileAsrCompleteHook()
        installFakeFileAsrCompleteHook()
        installProcessConvertCompleteHook()
    }

    private fun installFileAsrCompleteHook() {
        val cl = param.classLoader
        val (clazz, method, getMediaId) = try {
            val c = Class.forName(CLS_FILE_ASR_CONTROLLER, false, cl)
            Triple(c, c.getDeclaredMethod(M_TOAST_ASR_COMPLETE, java.lang.Boolean.TYPE), c.getMethod(M_GET_MEDIA_ID))
                .also { HookStatus.found("complete_asr", "层1", "实际: ${c.name}") }
        } catch (t: Throwable) {
            XLog.d("FileAsrController.toastAsrComplete not found: ${t.message}")
            HookStatus.missing("complete_asr", "定位失败: ${t.message}（在线/本地链路完成事件丢失）")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val mid = getMediaId.invoke(chain.thisObject) as? Long ?: -1L
                    if (mid > 0L) {
                        onConvertCompleteHooked(mid)
                        // 转写完成 → 索引正文（转写正文搜索，search-transcript.md §4.1）
                        ConvertIndexer.scheduleIndexByMediaId(mid)
                    }
                } catch (t: Throwable) {
                    XLog.w("FileAsrController.toastAsrComplete after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${clazz.name}#toastAsrComplete(boolean) [convert-complete]")
    }

    private fun installFakeFileAsrCompleteHook() {
        val cl = param.classLoader
        val (clazz, method, getMediaId) = try {
            val c = Class.forName(CLS_FAKE_FILE_ASR_CONTROLLER, false, cl)
            Triple(c, c.getDeclaredMethod(M_TOAST_COMPLETE), c.getMethod(M_GET_MEDIA_ID))
                .also { HookStatus.found("complete_fake", "层1", "实际: ${c.name}") }
        } catch (t: Throwable) {
            XLog.d("FakeFileAsrController.toastComplete not found: ${t.message}")
            HookStatus.missing("complete_fake", "定位失败: ${t.message}（降级链路完成事件丢失）")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val mid = getMediaId.invoke(chain.thisObject) as? Long ?: -1L
                    if (mid > 0L) {
                        onConvertCompleteHooked(mid)
                        ConvertIndexer.scheduleIndexByMediaId(mid)
                    }
                } catch (t: Throwable) {
                    XLog.w("FakeFileAsrController.toastComplete after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${clazz.name}#toastComplete() [convert-complete]")
    }

    private fun installProcessConvertCompleteHook() {
        val cl = param.classLoader
        val (clazz, method, getRecordId) = try {
            val c = Class.forName(CLS_PROCESS_CONVERT, false, cl)
            val recordCls = Class.forName(CLS_CONVERT_RECORD, false, cl)
            Triple(c, c.getDeclaredMethod(M_TOAST_COMPLETED_CONVERT, recordCls, java.lang.Boolean.TYPE), recordCls.getMethod(M_GET_RECORD_ID))
                .also { HookStatus.found("complete_proc", "层1", "实际: ${c.name}") }
        } catch (t: Throwable) {
            XLog.d("ProcessConvert.toastCompletedConvert not found: ${t.message}")
            HookStatus.missing("complete_proc", "定位失败: ${t.message}（后台进程完成事件丢失）")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val record = chain.args.getOrNull(0)
                    val mid = if (record != null) getRecordId.invoke(record) as? Long ?: -1L else -1L
                    if (mid > 0L) {
                        onConvertCompleteHooked(mid)
                        ConvertIndexer.scheduleIndexByMediaId(mid)
                    }
                } catch (t: Throwable) {
                    XLog.w("ProcessConvert.toastCompletedConvert after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${clazz.name}#toastCompletedConvert(ConvertRecord, boolean) [convert-complete]")
    }

    // ---------------------------------------------------------------- 转写正文搜索索引 hooks

    /**
     * 转写完成统一入口（三个完成 hook 共用）：
     * 1. 先交给 [ConvertQueue]：批量队列消费该事件（返回 true），由队列联动摘要 + 推进下一条；
     * 2. 队列未消费（无批量 / 手动单条转写完成）且「生成摘要」开关打开 → 独立入摘要队列。
     */
    private fun onConvertCompleteHooked(mediaId: Long) {
        val handled = ConvertQueue.onConvertComplete(mediaId)
        if (!handled && SummaryPrefs.enabled) {
            SummaryQueue.enqueue(mediaId)
        }
    }

    /**
     * 索引增量同步 hooks（search-transcript.md §4.1/§4.2）：
     * - 层一：`ConvertDbUtil.updateCompleteAndFilePathByRecordId(long, String, Boolean, Integer)` —
     *   转写完成的唯一落库口（3 参重载内部转发到 4 参，hook 一个全覆盖）。after 里
     *   result==true 且 txt 路径非空 → 投递索引任务（写线程单写者，pending set 去重）；
     * - 层二：`ConvertDeleteUtil.deleteConvertData(Context, long)` / `(Context, String)` —
     *   录音删除时同步清索引；漏删场景由首次搜索对账兜底。
     */
    private fun installConvertIndexHooks() {
        val cl = param.classLoader
        // 层一：转写完成 → 索引
        try {
            val clazz = Class.forName(CLS_CONVERT_DB_UTIL, false, cl)
            val method = clazz.getDeclaredMethod(
                M_UPDATE_COMPLETE_AND_FILE_PATH,
                java.lang.Long.TYPE, String::class.java,
                java.lang.Boolean::class.java, java.lang.Integer::class.java
            )
            api.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        if (result == true) {
                            val recordId = chain.args.getOrNull(0) as? Long ?: -1L
                            val path = chain.args.getOrNull(1) as? String
                            if (recordId > 0L && !path.isNullOrEmpty()) {
                                ConvertIndexer.scheduleIndex(recordId, path)
                            }
                        }
                    } catch (t: Throwable) {
                        XLog.w("updateCompleteAndFilePath after-hook error", t)
                    }
                    result
                }
            XLog.i("hooked ${clazz.name}#updateCompleteAndFilePathByRecordId(long,String,Boolean,Integer) [index-sync]")
            HookStatus.found("index_update", "层1", "实际: ${clazz.name}")
        } catch (t: Throwable) {
            XLog.w("updateCompleteAndFilePathByRecordId hook failed: ${t.message}")
            HookStatus.missing("index_update", "定位失败: ${t.message}（转写正文搜索会退化为仅对账）")
        }

        // 层二：删除 → 清索引
        try {
            val clazz = Class.forName(CLS_CONVERT_DELETE_UTIL, false, cl)
            val ctxType = android.content.Context::class.java
            val deleteById = clazz.getDeclaredMethod(M_DELETE_CONVERT_DATA, ctxType, java.lang.Long.TYPE)
            api.hook(deleteById)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val recordId = chain.args.getOrNull(1) as? Long ?: -1L
                        if (recordId > 0L) ConvertIndexer.scheduleDelete(recordId)
                    } catch (t: Throwable) {
                        XLog.w("deleteConvertData(long) after-hook error", t)
                    }
                    result
                }
            XLog.i("hooked ${clazz.name}#deleteConvertData(Context, long) [index-sync]")
            HookStatus.found("index_del_id", "层1", "实际: ${clazz.name}")
        } catch (t: Throwable) {
            XLog.w("deleteConvertData(long) hook failed: ${t.message}")
            HookStatus.optionalMissing("index_del_id", "定位失败: ${t.message}（删除后索引靠对账兜底）")
        }
        try {
            val clazz = Class.forName(CLS_CONVERT_DELETE_UTIL, false, cl)
            val ctxType = android.content.Context::class.java
            val deleteByPath = clazz.getDeclaredMethod(M_DELETE_CONVERT_DATA, ctxType, String::class.java)
            api.hook(deleteByPath)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val mediaPath = chain.args.getOrNull(1) as? String
                        ConvertIndexer.scheduleDeleteByMediaPath(mediaPath)
                    } catch (t: Throwable) {
                        XLog.w("deleteConvertData(String) after-hook error", t)
                    }
                    result
                }
            XLog.i("hooked ${clazz.name}#deleteConvertData(Context, String) [index-sync]")
            HookStatus.found("index_del_path", "层1", "实际: ${clazz.name}")
        } catch (t: Throwable) {
            XLog.w("deleteConvertData(String) hook failed: ${t.message}")
            HookStatus.optionalMissing("index_del_path", "定位失败: ${t.message}（删除后索引靠对账兜底）")
        }
    }
}

/**
 * DexKit 兜底定位器（hook2.md §6.2 目标2/4 特征）。
 *
 * 用法：`DexKitResolver(cl, apkPath).use { ... }`，用完自动释放 native 资源。
 */
internal class DexKitResolver(
    private val cl: ClassLoader,
    apkPath: String
) : AutoCloseable {

    private val bridge: DexKitBridge = DexKitBridge.create(apkPath)

    override fun close() {
        try {
            bridge.close()
        } catch (t: Throwable) {
            XLog.w("close dexkit bridge failed", t)
        }
    }

    /** 目标2：startConvertTask。先按未混淆方法名，再按 Kotlin object 结构特征。 */
    fun findTrigger(): TriggerHandle? {
        // 2a：按方法名（业务 API 名，通常不混淆）
        val byName = bridge.findMethod {
            matcher {
                name(M_START_CONVERT_TASK)
                paramTypes("long")
                returnType("void")
                modifiers(Modifier.PUBLIC)
            }
        }
        XLog.d("dexkit startConvertTask name candidates: ${byName.size}")
        for (data in byName) {
            val handle = triggerFromMethodData(data)
            if (handle != null) return handle
        }

        // 2b：结构特征 —— Kotlin object（INSTANCE 静态字段且类型为自身）+ 唯一 public 方法 (long)→void
        val classes = bridge.findClass {
            matcher {
                fields {
                    add { name(F_INSTANCE) }
                }
                methods {
                    add {
                        paramTypes("long")
                        returnType("void")
                        modifiers(Modifier.PUBLIC)
                    }
                }
            }
        }
        XLog.d("dexkit trigger class candidates: ${classes.size}")
        for (cd in classes) {
            val handle = triggerFromClassData(cd)
            if (handle != null) return handle
        }
        return null
    }

    /** 目标4（防重，可选）：checkAlreadyConvertComplete。层1 失败时按方法名兜底。 */
    fun findCheckComplete(): Method? {
        val list = bridge.findMethod {
            matcher {
                name(M_CHECK_ALREADY_CONVERT_COMPLETE)
                paramTypes("long")
                returnType("boolean")
                modifiers(Modifier.PUBLIC or Modifier.STATIC)
            }
        }
        XLog.d("dexkit checkAlreadyConvertComplete candidates: ${list.size}")
        for (data in list) {
            val method = loadMethod(data)
            if (method != null) {
                XLog.i("dexkit checkAlreadyConvertComplete -> ${data.className}#${data.name}")
                return method
            }
        }
        return null
    }

    // ------------------------------------------------------------ 内部实现

    private fun loadMethod(data: MethodData): Method? {
        return try {
            data.getMethodInstance(cl)
        } catch (t: Throwable) {
            XLog.w("load method ${data.className}#${data.name} failed", t)
            null
        }
    }

    private fun triggerFromMethodData(data: MethodData): TriggerHandle? {
        return try {
            val clazz = Class.forName(data.className, false, cl)
            // 按方法名命中只需 INSTANCE 自引用字段即可，结构从宽
            val instanceField = clazz.getField(F_INSTANCE)
            if (instanceField.type != clazz) {
                XLog.d("candidate ${data.className}: INSTANCE type mismatch")
                return null
            }
            val instance = instanceField.get(null) ?: return null
            val method = clazz.getMethod(M_START_CONVERT_TASK, java.lang.Long.TYPE)
            XLog.i("dexkit startConvertTask -> ${data.className}#${data.name}")
            buildTriggerLoose(instance, method)
        } catch (t: Throwable) {
            XLog.d("candidate ${data.className} rejected: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun triggerFromClassData(cd: ClassData): TriggerHandle? {
        return try {
            val clazz = cd.getInstance(cl)
            if (!isConvertTaskApiShape(clazz)) {
                XLog.d("candidate ${cd.name}: shape mismatch")
                return null
            }
            val instance = clazz.getField(F_INSTANCE).get(null) ?: return null
            val method = clazz.getMethod(M_START_CONVERT_TASK, java.lang.Long.TYPE)
            XLog.i("dexkit startConvertTask -> ${cd.name}#${method.name}")
            buildTriggerLoose(instance, method)
        } catch (t: Throwable) {
            XLog.d("candidate ${cd.name} rejected: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 结构校验（hook2.md §6.2 目标2）：
     * 1. 静态 `INSTANCE` 字段，类型为声明类自身；
     * 2. 恰好 1 个接口，且该接口恰好 1 个方法 `(long) → void`；
     * 3. 声明的方法中 public 的恰好 1 个，且签名 `(long) → void`。
     */
    private fun isConvertTaskApiShape(clazz: Class<*>): Boolean {
        val instanceField = try {
            clazz.getField(F_INSTANCE)
        } catch (t: Throwable) {
            return false
        }
        if (!Modifier.isStatic(instanceField.modifiers) || instanceField.type != clazz) return false

        val interfaces = clazz.interfaces
        if (interfaces.size != 1) return false
        val interfaceMethods = interfaces[0].declaredMethods
        if (interfaceMethods.size != 1) return false
        if (!isLongToVoid(interfaceMethods[0])) return false

        val publicDeclared = clazz.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        if (publicDeclared.size != 1) return false
        return isLongToVoid(publicDeclared[0])
    }

    private fun isLongToVoid(m: Method): Boolean =
        m.returnType == Void.TYPE &&
            m.parameterTypes.size == 1 &&
            m.parameterTypes[0] == java.lang.Long.TYPE

    /** DexKit 路径下组装触发器：可选防重句柄按需补充。 */
    private fun buildTriggerLoose(instance: Any, method: Method): TriggerHandle {
        val checkComplete = findCheckComplete()
        var checkRunningInstance: Any? = null
        var checkRunningMethod: Method? = null
        try {
            val mgrClazz = Class.forName(CLS_CONVERT_TASK_THREAD_MANAGER, false, cl)
            checkRunningInstance = mgrClazz.getField(F_INSTANCE).get(null)
            checkRunningMethod = mgrClazz.getMethod(M_CHECK_IS_TASK_RUNNING, java.lang.Long.TYPE)
        } catch (t: Throwable) {
            XLog.d("dexkit checkIsTaskRunning not found: ${t.message}")
        }
        return TriggerHandle(
            instance,
            method,
            checkComplete,
            checkRunningInstance,
            checkRunningMethod
        )
    }
}
