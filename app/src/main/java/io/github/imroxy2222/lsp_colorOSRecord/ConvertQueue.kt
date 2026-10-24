package io.github.imroxy2222.lsp_colorOSRecord

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.LinkedBlockingDeque

/**
 * 转文本串行队列 —— 完成事件驱动。
 *
 * 替代早期"固定随机 sleep"方案：扫库后把待转 mediaId 依次入队，逐条触发；
 * 每条转完后由转文本完成 toast 回调（FileAsrController.toastAsrComplete /
 * FakeFileAsrController.toastComplete / ProcessConvert.toastCompletedConvert）
 * 的 after-hook 调用 [onConvertComplete]，队列延迟 [GAP_MS] 后再取下一条触发。
 *
 * 优势：长录音会一直等到真正转完才继续，不再因固定 sleep 时长不足而漏转。
 *
 * 保底：每条最长等待 [GUARD_TIMEOUT_MS]，超时仍未收到完成回调则强制推进，
 * 防止某条异常路径（未弹完成 toast）导致队列永久卡死。
 */
internal object ConvertQueue {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = LinkedBlockingDeque<Long>()
    private val lock = Any()

    @Volatile
    private var current: Long = -1L
    @Volatile
    private var guardScheduled = false
    @Volatile
    private var activityRef: Activity? = null
    @Volatile
    private var trigger: TriggerHandle? = null

    private var total = 0
    private var done = 0

    /** 本轮批量里每条已自动重试的次数（空结果重试用）。 */
    private val attempts = HashMap<Long, Int>()

    private val guardRunnable = Runnable {
        synchronized(lock) {
            guardScheduled = false
            if (current > 0L) {
                XLog.d("convert guard timeout for mediaId=$current, force next")
                current = -1L
                done++
                // 超时路径不知道转写是否真正成功，不联动摘要；
                // 但同样等摘要管道空闲后再转下一条（共用云端 QPS）。
                scheduleNextConvertLocked(GAP_MS)
            }
        }
    }

    private val drainRunnable = Runnable {
        synchronized(lock) { drainNextLocked() }
    }

    /** 开启一轮批量扫描（用户点按钮时调用）：清空队列与状态，绑定上下文。 */
    fun startBatch(activity: Activity, trig: TriggerHandle) {
        synchronized(lock) {
            handler.removeCallbacks(drainRunnable)
            handler.removeCallbacks(guardRunnable)
            pending.clear()
            current = -1L
            guardScheduled = false
            total = 0
            done = 0
            synchronized(attempts) { attempts.clear() }
            activityRef = activity
            trigger = trig
        }
    }

    /**
     * 是否有批量任务进行中（有待处理条目，或当前条正在转）。
     *
     * 供菜单点击防重入：批量进行中再点"转文本/转全部"直接拒绝。
     * 原因：再次点击会 startBatch 清空队列重建，但已下发的转文本任务无法取消——
     * 旧条目继续转 + 新队列又触发下一条，出现并发转文本（易触发服务端限流），
     * 且两次扫描的 total/done 计数互相污染。队列排空（或 240s 守卫超时）后自动恢复可点。
     */
    fun isActive(): Boolean {
        synchronized(lock) { return current > 0L || pending.isNotEmpty() }
    }

    /**
     * 入队一条待转 mediaId；若队列空闲则立即开始处理。
     * 首条触发经由 [SummaryQueue.whenIdle] 节流：摘要管道仍在跑上一轮任务时，
     * 不与新转写并发打云端。
     */
    fun enqueue(mediaId: Long) {
        synchronized(lock) {
            pending.offer(mediaId)
            total++
            if (current <= 0L) {
                scheduleNextConvertLocked(GAP_MS)
            }
        }
    }

    /**
     * 完成回调入口（hook 转文本完成 toast 方法后调用）。
     *
     * **空结果自动重试**：ASR 任务可能"成功结束但识别结果为空"（弹"录音中未识别到文本"，
     * 批量连发时因云端限流/本地引擎会话未释放完而明显更频繁；app 此时**不写** convert 记录）。
     * 所有完成路径都先写库再弹 toast，所以此处用 checkAlreadyConvertComplete 可靠区分：
     * 未写记录 = 空结果 → 重新入队，冷却 [RETRY_GAP_MS] 后重试，最多 [MAX_ATTEMPTS] 次
     * （手动重试能成功，说明只是瞬时状态，自动重试即可覆盖）。
     *
     * **摘要联动**（SummaryQueue）：转写最终成功的条目，若「生成摘要」开关打开，
     * 紧接入摘要队列；下一条转写等摘要管道空闲后才触发 —— 转写/摘要全程共用一条
     * 云端串行通道，防止 QPS 限流。
     *
     * @param mediaId 完成的录音 mediaId；<=0 表示无法识别，按"当前条"处理。
     * @return 是否由本队列消费了该完成事件（true=批量队列的当前条；
     *         false=队列未在跑 / 完成事件不属于当前条，调用方可据此走独立摘要联动）。
     */
    fun onConvertComplete(mediaId: Long): Boolean {
        synchronized(lock) {
            if (current <= 0L) return false
            if (mediaId > 0L && mediaId != current) return false
            val finished = current
            current = -1L
            handler.removeCallbacks(guardRunnable)
            guardScheduled = false
            // 空结果检测与重试（真实完成路径均先写库后 toast，见方法注释）
            val converted = if (finished > 0L) (trigger?.isConverted(finished) ?: false) else true
            if (!converted) {
                val n = (synchronized(attempts) { attempts[finished] } ?: 0)
                if (n < MAX_ATTEMPTS) {
                    synchronized(attempts) { attempts[finished] = n + 1 }
                    pending.offer(finished)
                    total++
                    XLog.w("convert empty result: mediaId=$finished, retry #${n + 1} in ${RETRY_GAP_MS / 1000}s")
                    toast("第 ${done + 1} 条未识别到文本，稍后自动重试")
                    scheduleNextConvertLocked(RETRY_GAP_MS)
                    return true
                }
                XLog.w("convert empty result: mediaId=$finished, give up after $MAX_ATTEMPTS retries")
                toast("第 ${done + 1} 条多次未识别到文本，已跳过")
            }
            done++
            // 摘要联动：转写成功 → 按开关入摘要队列（SummaryQueue 内部串行 + 条间间隔）
            if (finished > 0L && SummaryPrefs.enabled) {
                SummaryQueue.enqueue(finished)
            }
            // 短延迟后转下一条；若摘要管道仍在跑，等它空闲再发（共用云端 QPS）
            scheduleNextConvertLocked(GAP_MS)
            return true
        }
    }

    /**
     * 取出队首并触发；跳过未真正触发 startConvertTask 的条目（已转/运行中/去重）。
     *
     * 已有转写在跑（current>0）时直接返回：批量入队会让 whenIdle 回调重复 postDelayed
     * 本 runnable，完成回调自会调度下一条，这里不并发触发（防止并发转文本被限流）。
     */
    private fun drainNextLocked() {
        if (current > 0L) return
        val trig = trigger ?: return
        while (true) {
            val next = pending.poll()
            if (next == null) {
                if (current <= 0L && total > 0 && done >= total) {
                    toast("批量转文本完成：共 $done 条")
                    XLog.i("convert queue drained: $done item(s)")
                }
                return
            }
            val triggered = try {
                trig.onMediaIdSynced(next)
            } catch (t: Throwable) {
                XLog.w("queue trigger failed for $next", t)
                false
            }
            if (triggered) {
                current = next
                toast("正在转文本：第 ${done + 1}/$total 条")
                XLog.d("convert queue: start #$next (${done + 1}/$total)")
                scheduleGuardLocked()
                return
            }
            // 已转完 / 运行中 / 去重跳过：直接下一条
            done++
        }
    }

    private fun scheduleGuardLocked() {
        handler.removeCallbacks(guardRunnable)
        guardScheduled = true
        handler.postDelayed(guardRunnable, GUARD_TIMEOUT_MS)
    }

    /**
     * 延迟 [delayMs] 后驱动下一条转写；若摘要管道仍在跑（上一条联动摘要或独立摘要），
     * 先等它空闲再计时 —— 转写与摘要共用一条云端串行通道，防止 QPS 限流。
     * 必须在持有 [lock] 时调用（命名 Locked）。
     */
    private fun scheduleNextConvertLocked(delayMs: Long) {
        SummaryQueue.whenIdle { handler.postDelayed(drainRunnable, delayMs) }
    }

    private fun toast(msg: String) {
        val act = activityRef
        handler.post {
            if (act == null || act.isFinishing || act.isDestroyed) return@post
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private const val GAP_MS = 3000L
    private const val GUARD_TIMEOUT_MS = 240_000L

    /** 空结果自动重试的最大次数（首次 + 重试 MAX_ATTEMPTS 次）。 */
    private const val MAX_ATTEMPTS = 2

    /** 空结果重试的冷却间隔：给云端限流解除 / 本地引擎释放留时间。 */
    private const val RETRY_GAP_MS = 20_000L
}
