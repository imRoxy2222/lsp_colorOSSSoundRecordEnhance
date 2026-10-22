package io.github.imroxy2222.lsp_colorOSRecord

import android.net.Uri
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
internal const val CLS_RECORD_MODE_UTIL = "com.soundrecorder.common.utils.RecordModeUtil"
internal const val CLS_MEDIA_DB_UTILS = "com.soundrecorder.common.db.MediaDBUtils"

/** Kotlin object 单例字段名。 */
internal const val F_INSTANCE = "INSTANCE"

/** 目标方法名。 */
internal const val M_START_CONVERT_TASK = "startConvertTask"
internal const val M_CHECK_ALREADY_CONVERT_COMPLETE = "checkAlreadyConvertComplete"
internal const val M_CHECK_IS_TASK_RUNNING = "checkIsTaskRunning"
internal const val M_QUERY_RECORD_BY_REL = "queryRecordByRelativePathAndDisplayName"
internal const val M_GET_RECORD_TYPE_FOR_MEDIA = "getRecordTypeForMediaRecord"
internal const val M_GET_ID = "getId"

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

/** 终极汇聚点：RecorderProvider（所有录音记录写库的 ContentProvider，multiprocess=false 主进程）。 */
internal const val CLS_RECORDER_PROVIDER = "com.soundrecorder.common.db.RecorderProvider"
internal const val M_INSERT = "insert"
internal const val M_BULK_INSERT = "bulkInsert"

/** ContentValues 列名（convertToContentValues 写入的字面量键）。 */
internal const val COL_RECORD_TYPE = "record_type"
internal const val COL_RELATIVE_PATH = "relative_path"
internal const val COL_DISPLAY_NAME = "display_name"

/** MediaDBUtils 直接按文件路径查 MediaStore _id（比反查 Record 更简单可靠）。 */
internal const val M_QUERY_ID_BY_DATA = "queryIdByData"

/** RecorderProvider 查询用常量。 */
internal const val RECORDER_PROVIDER_AUTHORITY = "com.coloros.soundrecorder.provider"
internal const val RECORDER_PATH_RECORDS = "records"
internal const val COL_DATA = "_data"
internal const val COL_ID = "_id"

/** 通话录音的 record_type 值（RecordModeUtil.RECORD_TYPE_SUB_DIR_MAP: 3 = Call Recordings）。 */
internal const val TYPE_CALL = 3

/** 全局触发器持有者：ScanButtonInjector 通过它拿 trigger 调用转文本。 */
internal object TriggerHolder {
    @Volatile
    var trigger: TriggerHandle? = null
}

/**
 * Hook 安装器：定位目标方法并安装 hook。
 *
 * 触发架构（唯一 hook 点 = 终极汇聚点）：
 * - `RecorderProvider.insert(Uri, ContentValues)` / `bulkInsert(Uri, ContentValues[])` 是
 *   **所有录音记录写库的唯一汇聚点**（ContentProvider，manifest 声明 multiprocess=false 主进程）。
 *   蜂窝广播（insertOrUpdateCallRecord）、VoIP 解密导入（processDecrypAudioFile）、
 *   全量同步（processInsertData/bulkInsert）、乃至 audiomonitor 跨进程直调
 *   ContentResolver.insert，最终全部经此写库 —— hook 此处即覆盖所有链路，
 *   无需再挂任何上游 hook 点（已验证 convertToContentValues 必写
 *   record_type / relative_path / display_name 三个列）。
 * - after 阶段按 `record_type == 3`（Call Recordings）过滤，仅触发通话录音转文本。
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

    /** 定位结果：ContentProvider 汇聚点 + 触发器。 */
    private class Resolution {
        var recorderInsert: Method? = null  // RecorderProvider#insert(Uri, ContentValues)（终极汇聚点，覆盖所有写库路径）
        var recorderBulkInsert: Method? = null // RecorderProvider#bulkInsert(Uri, ContentValues[])（批量汇聚点）
        var trigger: TriggerHandle? = null  // startConvertTask + 可选防重句柄
    }

    fun install() {
        val cl = param.classLoader
        val r = Resolution()

        // ---------- 层1：未混淆类名直接定位 ----------
        r.recorderInsert = resolveRecorderProviderInsertDirect(cl)
        r.recorderBulkInsert = resolveRecorderProviderBulkInsertDirect(cl)
        r.trigger = resolveTriggerDirect(cl)
        XLog.d(
            "layer1: recorderInsert=${r.recorderInsert != null}, " +
                "bulkInsert=${r.recorderBulkInsert != null}, " +
                "trigger=${r.trigger != null}"
        )

        if (r.trigger == null) {
            // ---------- 层2：DexKit 兜底（后台线程，避免阻塞 App 启动） ----------
            XLog.i("layer1 incomplete, fallback to DexKit")
            Thread {
                try {
                    DexKitResolver(cl, param.applicationInfo.sourceDir).use { resolver ->
                        if (r.trigger == null) {
                            r.trigger = resolver.findTrigger()
                        }
                    }
                    finishInstall(r)
                } catch (t: Throwable) {
                    XLog.w("dexkit fallback failed", t)
                }
            }.apply {
                isDaemon = true
                name = "CallRecConvert-DexKit"
            }.start()
        } else {
            finishInstall(r)
        }
    }

    private fun finishInstall(r: Resolution) {
        val trigger = r.trigger
        if (trigger == null) {
            XLog.w("trigger unresolved, module inactive")
            return
        }

        // 终极汇聚点（唯一 hook 点）：RecorderProvider.insert/bulkInsert。
        // 不管通话录音经哪条上游链路（蜂窝广播 / VoIP 解密导入 / 全量同步 /
        // audiomonitor 跨进程直调），最终都必经此 ContentProvider 写库。
        // record_type==3 过滤后仅触发通话录音。
        val insertHooked = r.recorderInsert?.let {
            installRecorderProviderInsertHook(it, trigger); true
        } ?: false
        val bulkHooked = r.recorderBulkInsert?.let {
            installRecorderProviderBulkInsertHook(it, trigger); true
        } ?: false
        if (!insertHooked && !bulkHooked) {
            XLog.w("RecorderProvider insert/bulkInsert unresolved, module cannot trigger")
        }

        // 存 trigger 供 ScanButtonInjector（一键转文本按钮）使用
        TriggerHolder.trigger = trigger
        // 转文本完成回调 hook：驱动 ConvertQueue 逐条推进（替代固定 sleep）
        installConvertCompleteHooks()
        // 注入一键转文本按钮到通话录音列表界面
        ScanButtonInjector(api, param).install()
    }

    // ---------------------------------------------------------------- 层1：直接定位

    /**
     * 终极汇聚点：`RecorderProvider.insert(Uri, ContentValues)`（public，ContentProvider 覆盖方法）。
     *
     * 所有录音记录写库——蜂窝广播 insertOrUpdateCallRecord、VoIP 解密导入 processDecrypAudioFile、
     * 全量同步 processInsertData/bulkInsert、甚至 audiomonitor 跨进程直调 ContentResolver.insert——
     * 最终都汇聚到本方法。manifest 声明 `multiprocess=false`，确保只在录音机主进程跑、hook 必捕获。
     *
     * after 阶段从 ContentValues 取 `record_type`，==3（Call Recordings）才反查 MediaStore 拿 mediaId。
     */
    private fun resolveRecorderProviderInsertDirect(cl: ClassLoader): Method? {
        return try {
            val clazz = Class.forName(CLS_RECORDER_PROVIDER, false, cl)
            clazz.getDeclaredMethod(
                M_INSERT,
                Uri::class.java, android.content.ContentValues::class.java
            )
        } catch (t: Throwable) {
            XLog.d("layer1 RecorderProvider.insert not found: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 批量汇聚点：`RecorderProvider.bulkInsert(Uri, ContentValues[])`（public）。 */
    private fun resolveRecorderProviderBulkInsertDirect(cl: ClassLoader): Method? {
        return try {
            val clazz = Class.forName(CLS_RECORDER_PROVIDER, false, cl)
            clazz.getDeclaredMethod(
                M_BULK_INSERT,
                Uri::class.java, Array<android.content.ContentValues>::class.java
            )
        } catch (t: Throwable) {
            XLog.d("layer1 RecorderProvider.bulkInsert not found: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

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
        } catch (t: Throwable) {
            XLog.d("layer1 checkAlreadyConvertComplete not found: ${t.message}")
            null
        }
    }

    private fun resolveCheckRunningDirect(cl: ClassLoader): Pair<Any, Method>? {
        return try {
            val clazz = Class.forName(CLS_CONVERT_TASK_THREAD_MANAGER, false, cl)
            val instance = clazz.getField(F_INSTANCE).get(null) ?: return null
            val method = clazz.getMethod(M_CHECK_IS_TASK_RUNNING, java.lang.Long.TYPE)
            instance to method
        } catch (t: Throwable) {
            XLog.d("layer1 checkIsTaskRunning not found: ${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------- Hook 安装

    /**
     * 终极汇聚点 hook：`RecorderProvider.insert(Uri, ContentValues)` 的 after-hook。
     *
     * 覆盖所有单条写库路径（含 audiomonitor 跨进程直调）。after 阶段：
     * 1. 返回 Uri 非空（插入成功）才处理；
     * 2. 从 ContentValues 取 `record_type`，==3（Call Recordings）才继续——
     *    非 records 表的 insert（convert/upload/status…）不含此列，自动跳过；
     * 3. 用 `relative_path`+`display_name` 反查 MediaStore 拿 mode-1 Record，
     *    `record.getId()` 即 MediaStore `_ID`（转文本管道所需 mediaId）。
     *
     * 同一记录若被多条上游链路/多次写库触发，由 TriggerHandle 的 60s 时间窗去重兜住。
     */
    private fun installRecorderProviderInsertHook(method: Method, trigger: TriggerHandle) {
        val cl = param.classLoader
        val mediaDbCls = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
        val recordCls = Class.forName(CLS_RECORD, false, cl)
        val modeUtilCls = Class.forName(CLS_RECORD_MODE_UTIL, false, cl)
        val queryByRel = mediaDbCls.getMethod(M_QUERY_RECORD_BY_REL, String::class.java, String::class.java)
        val getType = modeUtilCls.getMethod(M_GET_RECORD_TYPE_FOR_MEDIA, recordCls)
        val getId = recordCls.getMethod(M_GET_ID)
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    if (result != null) {
                        val cv = chain.args.getOrNull(1) as? android.content.ContentValues
                        val type = cv?.getAsInteger(COL_RECORD_TYPE)
                        if (type != null && type == TYPE_CALL) {
                            val relPath = cv.getAsString(COL_RELATIVE_PATH)
                            val displayName = cv.getAsString(COL_DISPLAY_NAME)
                            if (!displayName.isNullOrEmpty() && !relPath.isNullOrEmpty()) {
                                val record = queryByRel.invoke(null, relPath, displayName)
                                if (record != null) {
                                    val typeCheck = getType.invoke(null, record) as? Int ?: -1
                                    val mediaId = getId.invoke(record) as? Long ?: -1L
                                    XLog.d(
                                        "RecorderProvider.insert after: $displayName type=$typeCheck mediaId=$mediaId"
                                    )
                                    if (typeCheck == TYPE_CALL && mediaId > 0L) {
                                        trigger.onMediaIdSynced(mediaId)
                                    }
                                } else {
                                    XLog.d("RecorderProvider.insert after: media not found for $displayName")
                                }
                            } else {
                                XLog.d("RecorderProvider.insert after: call record but relPath/displayName empty")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    XLog.w("RecorderProvider.insert after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${method.declaringClass.name}#insert(Uri, ContentValues) [provider-sink]")
    }

    /**
     * 批量汇聚点 hook：`RecorderProvider.bulkInsert(Uri, ContentValues[])` 的 after-hook。
     *
     * 覆盖全量同步 processInsertData 的批量写库路径。after 阶段遍历 ContentValues[]，
     * 对每条 record_type==3 的记录反查 MediaStore 拿 mediaId 后触发转文本。
     */
    private fun installRecorderProviderBulkInsertHook(method: Method, trigger: TriggerHandle) {
        val cl = param.classLoader
        val mediaDbCls = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
        val recordCls = Class.forName(CLS_RECORD, false, cl)
        val modeUtilCls = Class.forName(CLS_RECORD_MODE_UTIL, false, cl)
        val queryByRel = mediaDbCls.getMethod(M_QUERY_RECORD_BY_REL, String::class.java, String::class.java)
        val getType = modeUtilCls.getMethod(M_GET_RECORD_TYPE_FOR_MEDIA, recordCls)
        val getId = recordCls.getMethod(M_GET_ID)
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val arr = chain.args.getOrNull(1) as? Array<*>
                    if (!arr.isNullOrEmpty()) {
                        var callCount = 0
                        for (item in arr) {
                            val cv = item as? android.content.ContentValues ?: continue
                            val type = cv.getAsInteger(COL_RECORD_TYPE) ?: continue
                            if (type != TYPE_CALL) continue
                            val relPath = cv.getAsString(COL_RELATIVE_PATH)
                            val displayName = cv.getAsString(COL_DISPLAY_NAME)
                            if (displayName.isNullOrEmpty() || relPath.isNullOrEmpty()) continue
                            val record = queryByRel.invoke(null, relPath, displayName) ?: continue
                            val typeCheck = getType.invoke(null, record) as? Int ?: continue
                            if (typeCheck != TYPE_CALL) continue
                            val mediaId = getId.invoke(record) as? Long ?: continue
                            if (mediaId > 0L) {
                                trigger.onMediaIdSynced(mediaId)
                                callCount++
                            }
                        }
                        if (callCount > 0) {
                            XLog.d("RecorderProvider.bulkInsert after: ${arr.size} row(s), $callCount call")
                        }
                    }
                } catch (t: Throwable) {
                    XLog.w("RecorderProvider.bulkInsert after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${method.declaringClass.name}#bulkInsert(Uri, ContentValues[]) [provider-sink]")
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
        } catch (t: Throwable) {
            XLog.d("FileAsrController.toastAsrComplete not found: ${t.message}")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val mid = getMediaId.invoke(chain.thisObject) as? Long ?: -1L
                    if (mid > 0L) ConvertQueue.onConvertComplete(mid)
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
        } catch (t: Throwable) {
            XLog.d("FakeFileAsrController.toastComplete not found: ${t.message}")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val mid = getMediaId.invoke(chain.thisObject) as? Long ?: -1L
                    if (mid > 0L) ConvertQueue.onConvertComplete(mid)
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
        } catch (t: Throwable) {
            XLog.d("ProcessConvert.toastCompletedConvert not found: ${t.message}")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val record = chain.args.getOrNull(0)
                    val mid = if (record != null) getRecordId.invoke(record) as? Long ?: -1L else -1L
                    if (mid > 0L) ConvertQueue.onConvertComplete(mid)
                } catch (t: Throwable) {
                    XLog.w("ProcessConvert.toastCompletedConvert after-hook error", t)
                }
                result
            }
        XLog.i("hooked ${clazz.name}#toastCompletedConvert(ConvertRecord, boolean) [convert-complete]")
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
