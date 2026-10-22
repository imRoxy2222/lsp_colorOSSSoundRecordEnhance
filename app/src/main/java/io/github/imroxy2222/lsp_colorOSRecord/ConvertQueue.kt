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

    @Volatile private var current: Long = -1L
    @Volatile private var guardScheduled = false
    @Volatile private var activityRef: Activity? = null
    @Volatile private var trigger: TriggerHandle? = null

    private var total = 0
    private var done = 0

    private val guardRunnable = Runnable {
        synchronized(lock) {
            guardScheduled = false
            if (current > 0L) {
                XLog.d("convert guard timeout for mediaId=$current, force next")
                current = -1L
                done++
                drainNextLocked()
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

    /** 入队一条待转 mediaId；若队列空闲则立即开始处理。 */
    fun enqueue(mediaId: Long) {
        synchronized(lock) {
            pending.offer(mediaId)
            total++
            if (current <= 0L) {
                drainNextLocked()
            }
        }
    }

    /**
     * 完成回调入口（hook 转文本完成 toast 方法后调用）。
     *
     * @param mediaId 完成的录音 mediaId；<=0 表示无法识别，按"当前条"处理。
     */
    fun onConvertComplete(mediaId: Long) {
        synchronized(lock) {
            if (current <= 0L) return
            if (mediaId > 0L && mediaId != current) return
            current = -1L
            done++
            handler.removeCallbacks(guardRunnable)
            guardScheduled = false
            // 短延迟后转下一条，避免请求过频被服务端限流
            handler.postDelayed(drainRunnable, GAP_MS)
        }
    }

    /** 取出队首并触发；跳过未真正触发 startConvertTask 的条目（已转/运行中/去重）。 */
    private fun drainNextLocked() {
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

    private fun toast(msg: String) {
        val act = activityRef
        handler.post {
            if (act == null || act.isFinishing || act.isDestroyed) return@post
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private const val GAP_MS = 3000L
    private const val GUARD_TIMEOUT_MS = 240_000L
}
