package io.github.imroxy2222.lsp_colorOSRecord

import java.lang.reflect.Method

/**
 * 转文本触发器（供 ConvertQueue 串行队列逐条调用）。
 *
 * 核心逻辑（由 ConvertQueue.drainNextLocked 对每条待转 mediaId 调用）：
 * 1. 60s 时间窗去重（同一记录可能被重复入队/触发）；
 * 2. `ConvertDbUtil.checkAlreadyConvertComplete(mediaId)` 已转完则跳过；
 *    （可选加固）`ConvertTaskThreadManager.checkIsTaskRunning(mediaId)` 正在跑则跳过；
 * 3. 反射调用 `ConvertTaskApi.INSTANCE.startConvertTask(mediaId)`。
 *
 * 所有反射调用与内部逻辑整体包在 try-catch 中，异常只进 Xposed 日志、绝不抛出，
 * 避免影响录音机原有流程。
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

    /**
     * hook 点（RecorderProvider.insert/bulkInsert）的 after 回调入口。
     *
     * mediaId 为 MediaStore `_ID`（转文本管道全程使用它）。
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
     * 去重原因：同一记录可能被多条上游链路 / 多次写库触发（如蜂窝链路 doSync 对同一
     * Uri 会先插记录、解码波形后再 update 一次）。
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
