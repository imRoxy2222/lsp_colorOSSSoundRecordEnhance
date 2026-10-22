package io.github.imroxy2222.lsp_colorOSRecord

import android.content.ContentUris
import android.net.Uri
import java.lang.reflect.Method

/**
 * 通话录音同步入库后的转文本触发器。
 *
 * 对应 hook2.md §5.2 的 after-hook 逻辑：
 * 1. 调用结果必须为 `true`（插入失败/记录无效时原方法自动短路，hook 逻辑同样跳过）；
 * 2. `mediaId = ContentUris.parseId(uri)`（MediaStore 的 `_ID`，转文本管道全程使用它）；
 * 3. 60s 时间窗去重（`doSync` 会对同一 Uri 调用两次：首轮 amp=null、波形解码后补 amp）；
 * 4. `ConvertDbUtil.checkAlreadyConvertComplete(mediaId)` 已转完则跳过；
 *    （可选加固）`ConvertTaskThreadManager.checkIsTaskRunning(mediaId)` 正在跑则跳过；
 * 5. 反射调用 `ConvertTaskApi.INSTANCE.startConvertTask(mediaId)`。
 *
 * 所有反射调用与内部逻辑整体包在 try-catch 中，异常只进 Xposed 日志、绝不抛出，
 * 避免影响系统通话录音同步流程。
 */
class TriggerHandle(
    private val startConvertInstance: Any,
    private val startConvertMethod: Method,
    private val checkCompleteMethod: Method?,
    private val checkRunningInstance: Any?,
    private val checkRunningMethod: Method?
) {

    private val lastTriggerAt = HashMap<Long, Long>()
    private val lock = Any()

    /** 主 hook 点（insertOrUpdateCallRecord）的 after 回调入口。 */
    fun onCallRecordSynced(uri: Uri?) {
        try {
            if (uri == null) {
                XLog.d("uri is null, skip")
                return
            }
            val mediaId = try {
                ContentUris.parseId(uri)
            } catch (t: Throwable) {
                XLog.d("parseId failed for $uri: ${t.message}")
                -1L
            }
            triggerByMediaId(mediaId)
        } catch (t: Throwable) {
            XLog.w("trigger convert failed", t)
        }
    }

    /**
     * 全量同步 hook 点（RecordDataSync.processInsertData）的 after 回调入口。
     *
     * VoIP 通话录音（QQ/微信，由 com.oplus.audiomonitor 录制）不走广播 doSync 链路，
     * 而是在 App 启动/同步时经 [RecordDataSync] 全量比对从 MediaStore 导入。该路径下
     * Record 来自 MediaStore 游标（mode 1），其 [Record.getId] 即为 MediaStore `_ID`，
     * 可直接作为 mediaId 下发转文本。
     *
     * @return 是否真正触发了 startConvertTask（去重/已转完/运行中均返回 false）。
     *   ConvertQueue 据此判断是否需要等待转文本完成回调，避免在已转完的条目上空等卡死。
     */
    fun onMediaIdSynced(mediaId: Long): Boolean {
        return try {
            triggerByMediaId(mediaId)
        } catch (t: Throwable) {
            XLog.w("trigger convert failed", t)
            false
        }
    }

    /**
     * 供菜单扫描器预过滤：该 mediaId 是否已转文本完成。
     * [checkCompleteMethod] 不可用时返回 false（不拦截，由队列兜底跳过）。
     */
    fun isConverted(mediaId: Long): Boolean {
        if (mediaId <= 0L) return false
        return isAlreadyConverted(mediaId)
    }

    /** 备用 hook 点（SyncCallRecordService.doSync）的 after 回调入口。 */
    fun onUrisSynced(uris: List<Uri>) {
        for (uri in uris) {
            onCallRecordSynced(uri)
        }
    }

    /** 核心触发逻辑：去重 → 已转完检查 → 任务运行检查 → 反射 startConvertTask。返回是否真正触发。 */
    private fun triggerByMediaId(mediaId: Long): Boolean {
        if (mediaId <= 0L) {
            XLog.d("invalid mediaId=$mediaId, skip")
            return false
        }
        if (!claimTrigger(mediaId)) return false
        if (isAlreadyConverted(mediaId)) {
            XLog.d("mediaId=$mediaId already converted, skip")
            return false
        }
        if (isTaskRunning(mediaId)) {
            XLog.d("mediaId=$mediaId task already running, skip")
            return false
        }
        startConvertMethod.invoke(startConvertInstance, mediaId)
        XLog.conversionTriggered(mediaId)
        return true
    }

    /**
     * 时间窗去重。返回 true 表示本次调用获得触发权。
     *
     * 去重原因：`doSync` 里同一 Uri 会被调用两次（首轮 amp=null，波形解码后 amp=波形串）。
     * 不用 "amp==null 判首次"：`doDecodeAmplitude` 里 waveMarkApi 为 null 时 amp 也可能是 null。
     */
    private fun claimTrigger(mediaId: Long): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = lastTriggerAt[mediaId] ?: 0L
            if (last != 0L && now - last < DEDUP_WINDOW_MS) {
                XLog.d("mediaId=$mediaId triggered ${now - last}ms ago, dedup skip")
                return false
            }
            lastTriggerAt[mediaId] = now
            // 防止 Map 无限增长：清理过期条目
            if (lastTriggerAt.size > MAX_ENTRIES) {
                val iter = lastTriggerAt.entries.iterator()
                while (iter.hasNext()) {
                    if (now - iter.next().value > DEDUP_WINDOW_MS * 10) iter.remove()
                }
            }
            return true
        }
    }

    private fun isAlreadyConverted(mediaId: Long): Boolean {
        val m = checkCompleteMethod ?: return false
        return try {
            m.invoke(null, mediaId) as? Boolean ?: false
        } catch (t: Throwable) {
            XLog.w("checkAlreadyConvertComplete($mediaId) failed", t)
            false
        }
    }

    private fun isTaskRunning(mediaId: Long): Boolean {
        val instance = checkRunningInstance ?: return false
        val m = checkRunningMethod ?: return false
        return try {
            m.invoke(instance, mediaId) as? Boolean ?: false
        } catch (t: Throwable) {
            XLog.w("checkIsTaskRunning($mediaId) failed", t)
            false
        }
    }

    companion object {
        private const val DEDUP_WINDOW_MS = 60_000L
        private const val MAX_ENTRIES = 64
    }
}
