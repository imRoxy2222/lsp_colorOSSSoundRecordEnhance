package io.github.imroxy2222.lsp_colorOSRecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 批量转文本 / 摘要的系统通知（替代原 toast 提醒）。
 *
 * 样式对齐宿主原生：宿主播放页转文本本身就有一条进度通知（ConvertNotificationManager，
 * 渠道 "convert_text_progress"、图标 ic_launcher_recorder、importance=DEFAULT、
 * 无声音/无振动/无呼吸灯/无角标）。本通知复用**同一渠道与同一图标**，在通知栏里
 * 与宿主原生通知风格完全一致。
 *
 * 布局（与原生通知同构的两行）：
 * - 第一行 contentTitle：批次进度，如「转文本中 5/26」；
 * - 第二行 contentText：当前条目，如「xxx.m4a 转写中」/「xxx.m4a 生成摘要中」。
 * total>1 时附带整体进度条（done/total）。
 *
 * id 策略：宿主用 mediaId（正数）作通知 id，本通知用固定负数 id，保证互不冲突；
 * 固定 id 使进度变化时原位更新（notify 同 id 覆盖），不会在通知栏堆一排。
 *
 * 上下文：模块运行在宿主进程内，Context 通过反射取宿主 BaseApplication.getAppContext
 * （与 SummaryQueue 同款），通知权限沿用宿主自身的（宿主发录音/转写通知必需，通常已授予）；
 * 未授予时自动回退为 toast，行为不劣化。
 */
internal object BatchNotifier {

    /** 复用宿主原生"转文本进度"渠道（不存在时按宿主同款参数创建）。 */
    private const val CHANNEL_ID = "convert_text_progress"

    /** 负数 id 避开宿主以 mediaId（正数）作通知 id 的空间。 */
    private const val PROGRESS_ID = -20000101
    private const val DONE_ID = -20000102

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var hostCl: ClassLoader? = null

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var iconResolved = false

    @Volatile
    private var iconRes = 0

    /** 第一行标题：最近一次批量进度（如「转文本中 5/26」），摘要阶段沿用。 */
    @Volatile
    private var baseTitle: String? = null

    /** 转文本队列排空时的汇总文案（最终完成通知里与摘要统计拼接）。 */
    @Volatile
    private var convertSummaryText: String? = null

    /** 本轮已发过进度通知（finish 幂等防重、cancelOnly 判断用）。 */
    @Volatile
    private var progressPosted = false

    /** 已发过完成通知（finish 幂等：转写侧与摘要侧都会触发收尾，只让先到者生效）。 */
    @Volatile
    private var finishedRound = false

    @Volatile
    private var lastDone = 0

    @Volatile
    private var lastTotal = 0

    /** mediaId → 显示名缓存（扫描时由 ScanButtonInjector 预填，摘要路径反射兜底）。 */
    private val nameCache = HashMap<Long, String>()
    private val nameLock = Any()

    // ------------------------------------------------------------ 初始化

    /** 传入宿主 classLoader（反射查 Record 名 / 拿全局 Context 用），HookInstaller 启动时调用。 */
    fun attachClassLoader(cl: ClassLoader) {
        hostCl = cl
    }

    /** 扫描时预填显示名缓存，避免通知阶段再查库。 */
    fun cacheName(mediaId: Long, name: String?) {
        if (mediaId <= 0L || name.isNullOrEmpty()) return
        synchronized(nameLock) {
            if (nameCache.size > 512) nameCache.clear()
            nameCache[mediaId] = name
        }
    }

    // ------------------------------------------------------------ 转文本阶段

    /**
     * 转文本进行中。index 从 1 计；detail 覆盖默认「转写中」文案
     * （空结果重试 / 放弃跳过等提示复用同一方法，原位更新）。
     */
    fun convertProgress(index: Int, total: Int, mediaId: Long, detail: String = "转写中") {
        finishedRound = false
        baseTitle = if (total > 1) "转文本中 $index/$total" else "转文本中"
        show(baseTitle!!, "${nameOf(mediaId)} $detail", index - 1, total)
    }

    /** 仅更新第二行文案（标题与进度条保持不变），如「转写完成，正在生成摘要…」。 */
    fun note(text: String) {
        show(baseTitle ?: "转文本", text, lastDone, lastTotal)
    }

    /** 记录转文本队列排空汇总（最终完成通知拼接用；新批次开始时由 convertProgress 重置 finishedRound）。 */
    fun setConvertSummary(text: String) {
        finishedRound = false
        convertSummaryText = text
    }

    // ------------------------------------------------------------ 摘要阶段

    /** 摘要进行中：第一行沿用转文本批次进度（无批次则「生成摘要中」），第二行「xxx 生成摘要中」。 */
    fun summaryProgress(mediaId: Long, index: Int, total: Int) {
        finishedRound = false
        show(
            baseTitle ?: "生成摘要中",
            "${nameOf(mediaId)} 生成摘要中",
            index - 1, total
        )
    }

    /** 单条摘要落账后的第二行更新（不再逐条 toast）。 */
    fun summarySettled(index: Int, total: Int, outcome: String) {
        show(baseTitle ?: "生成摘要中", "第 $index/$total 条$outcome", index - 1, total)
    }

    // ------------------------------------------------------------ 收尾

    /**
     * 批次完成：取消进度通知，发一条可清除的完成通知。
     * 转写汇总（[setConvertSummary]）与摘要统计拼接为正文；两者皆空且从未发过进度时只清理。
     * 幂等：转写侧与摘要侧的收尾竞态下只有第一次生效。
     *
     * 门禁：摘要队列在批量过程中会"中途排空"（转1→摘要1→转2→摘要2…），
     * 此时转写队列仍有任务（或反之），直接收尾会提前弹完成通知 ——
     * 任一队列仍在推进时静默跳过，把收尾留给真正的终点。
     */
    fun finish(summaryStats: String? = null) {
        if (finishedRound) {
            cancelQuietly(PROGRESS_ID)
            return
        }
        if (ConvertQueue.isActive() || SummaryQueue.isActive()) return
        finishedRound = true
        val convertText = convertSummaryText
        convertSummaryText = null
        baseTitle = null
        lastDone = 0
        lastTotal = 0
        progressPosted = false
        val parts = mutableListOf<String>()
        convertText?.let { parts.add(it) }
        summaryStats?.let { parts.add(it) }
        cancelQuietly(PROGRESS_ID)
        if (parts.isNotEmpty()) {
            finishNotification("任务完成", parts.joinToString("；"))
        }
    }

    // ------------------------------------------------------------ 内部：构建与投递

    private fun show(title: String, text: String, done: Int, total: Int) {
        lastDone = done
        lastTotal = total
        post(PROGRESS_ID, build(title, text, ongoing = true, done = done, total = total))
        progressPosted = true
    }

    private fun finishNotification(title: String, text: String) {
        post(DONE_ID, build(title, text, ongoing = false))
    }

    private fun build(
        title: String,
        text: String,
        ongoing: Boolean,
        done: Int = 0,
        total: Int = 0
    ): Notification? {
        val ctx = ctx() ?: return null
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) {
                // 通知权限未授予：回退 toast（app context 可弹），行为不劣化
                showToast(ctx, "$title：$text")
                return null
            }
            ensureChannel(nm)
            val b = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon(ctx))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(ctx))
            if (ongoing) {
                b.setOngoing(true)
                if (total > 1) b.setProgress(total, done.coerceIn(0, total), false)
            } else {
                b.setAutoCancel(true)
            }
            return b.build()
        } catch (t: Throwable) {
            XLog.w("batch notifier build failed: ${t.message}")
            return null
        }
    }

    private fun post(id: Int, n: Notification?) {
        if (n == null) return
        handler.post {
            try {
                val ctx = ctx() ?: return@post
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(id, n)
            } catch (t: Throwable) {
                XLog.w("batch notifier notify failed: ${t.message}")
            }
        }
    }

    private fun cancelQuietly(id: Int) {
        handler.post {
            try {
                val ctx = ctx() ?: return@post
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(id)
            } catch (_: Throwable) {
            }
        }
    }

    /** 创建/复用渠道：参数与宿主 ConvertNotificationManager 完全一致。 */
    private fun ensureChannel(nm: NotificationManager) {
        try {
            val channel = nm.getNotificationChannel(CHANNEL_ID) ?: NotificationChannel(
                CHANNEL_ID, "Convert Text Progress", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows progress during text conversion"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            channel.importance = NotificationManager.IMPORTANCE_DEFAULT
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            XLog.w("ensure channel failed: ${t.message}")
        }
    }

    /** 宿主原生转文本通知同款图标 ic_launcher_recorder；找不到再退 app 图标。 */
    private fun icon(ctx: Context): Int {
        if (iconResolved) return iconRes
        var id = try {
            ctx.resources.getIdentifier("ic_launcher_recorder", "drawable", ctx.packageName)
        } catch (_: Throwable) {
            0
        }
        if (id == 0) id = ctx.applicationInfo.icon
        iconRes = id
        iconResolved = true
        return id
    }

    /** 点击通知回到录音机（宿主启动入口）。 */
    private fun contentIntent(ctx: Context): PendingIntent? {
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun showToast(ctx: Context, msg: String) {
        handler.post {
            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------ 上下文 / 录音名

    private fun ctx(): Context? {
        appCtx?.let { return it }
        val cl = hostCl ?: return null
        return try {
            val ctx = Class.forName(CLS_BASE_APPLICATION, false, cl)
                .getMethod(M_APP_CONTEXT).invoke(null) as? Context
            if (ctx != null) appCtx = ctx
            ctx
        } catch (t: Throwable) {
            XLog.d("batch notifier ctx resolve failed: ${t.message}")
            null
        }
    }

    /** 录音显示名：缓存 → queryRecordById 反射（displayName/originalName/文件名兜底）→ 「录音 #id」。 */
    private fun nameOf(mediaId: Long): String {
        if (mediaId <= 0L) return "当前录音"
        synchronized(nameLock) { nameCache[mediaId] }?.let { return it }
        val name = resolveNameReflectively(mediaId)
        synchronized(nameLock) { nameCache[mediaId] = name }
        return name
    }

    private fun resolveNameReflectively(mediaId: Long): String {
        val cl = hostCl ?: return "录音 $mediaId"
        return try {
            val record = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
                .getMethod(M_QUERY_RECORD_BY_ID, java.lang.Long.TYPE)
                .invoke(null, mediaId) ?: return "录音 $mediaId"
            val recordCls = record.javaClass
            val displayName = runCatching {
                recordCls.getMethod("getDisplayName").invoke(record) as? String
            }.getOrNull()
            val originalName = runCatching {
                recordCls.getMethod("getOriginalName").invoke(record) as? String
            }.getOrNull()
            val data = runCatching {
                recordCls.getMethod("getData").invoke(record) as? String
            }.getOrNull()
            displayName?.takeIf { it.isNotBlank() }
                ?: originalName?.takeIf { it.isNotBlank() }
                ?: data?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "录音 $mediaId"
        } catch (t: Throwable) {
            XLog.d("record name resolve failed for $mediaId: ${t.message}")
            "录音 $mediaId"
        }
    }
}
