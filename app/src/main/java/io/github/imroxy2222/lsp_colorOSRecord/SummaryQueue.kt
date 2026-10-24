package io.github.imroxy2222.lsp_colorOSRecord

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------- 常量（17.8.8 反编译结论）

/** AISummaryTaskManager：摘要任务管理器（Kotlin object，静态方法 a~i）。 */
internal const val CLS_SUMMARY_TASK_MANAGER = "com.soundrecorder.summary.request.q"

/** SummaryRequestModel：摘要请求模型（未混淆，public 构造器 10 参含默认值掩码）。 */
internal const val CLS_SUMMARY_REQUEST_MODEL = "com.soundrecorder.summary.model.o"

/** SummaryTheme：摘要模板主题。 */
internal const val CLS_SUMMARY_THEME = "com.soundrecorder.summary.model.SummaryTheme"

/** 摘要任务事件回调接口（6 个方法，用动态代理实现）。 */
internal const val CLS_SUMMARY_CALLBACK = "com.soundrecorder.summary.request.u"

/** SummaryCacheDb：摘要落库 DAO 封装（f(long) 按 mediaId 查 SummaryCacheEntity）。 */
internal const val CLS_SUMMARY_CACHE_DB = "com.soundrecorder.summary.request.database.b"

/** PrefUtil / BaseApplication：读用户默认摘要模板 + 全局 Context。 */
internal const val CLS_PREF_UTIL = "com.soundrecorder.base.utils.PrefUtil"
internal const val CLS_BASE_APPLICATION = "com.soundrecorder.base.BaseApplication"
internal const val F_DEFAULT_THEME_KEY = "KEY_DEFAULT_SUMMARY_THEME_CODE"
internal const val M_GET_INT = "getInt"
internal const val M_APP_CONTEXT = "getAppContext"

/** q 的静态方法（单字母名，层1按 17.8.8 已知名，层2 DexKit 按锚点兜底，见 SummaryDexKitResolver）。 */
internal const val M_SUMMARY_REG_CALLBACK = "g"    // g(long, u) 注册事件回调
internal const val M_SUMMARY_POST = "h"            // h(SummaryRequestModel) 投递任务，返回是否受理
internal const val M_SUMMARY_UNREG_CALLBACK = "i"  // i(long, u) 注销回调
internal const val M_SUMMARY_CANCEL = "b"          // b(long) 取消运行中任务
internal const val M_SUMMARY_CACHE_GET = "f"       // database.b#f(long) 查已生成摘要缓存

/** MediaDBUtils#queryRecordById(long)。 */
internal const val M_QUERY_RECORD_BY_ID = "queryRecordById"

/** 摘要错误码（com.soundrecorder.summarykit.constant.ErrorCode）。 */
internal const val SUMMARY_ERR_BUSY = 10002        // 生成中数量达上限 / 管道忙（可重试）
internal const val SUMMARY_ERR_TOO_SHORT = 10016   // 内容过短，无法生成摘要（跳过）

/**
 * 转写后自动生成摘要的开关状态（持久化在宿主 App 私有 SharedPreferences）。
 *
 * 默认开启（对应用户看到的初始菜单项「生成摘要:开」）。
 * 读到 Context 后会懒加载一次持久化值，覆盖默认值。
 */
internal object SummaryPrefs {
    private const val PREF_FILE = "lsp_enhance_prefs"
    private const val KEY_ENABLED = "auto_summary_enabled"
    private const val TITLE_ON = "生成摘要:开"
    private const val TITLE_OFF = "生成摘要:关"

    @Volatile
    var enabled: Boolean = true
        private set

    private val loadLock = Any()
    private var loaded = false

    /** 懒加载持久化开关（菜单注入 / 队列首次用到 Context 时调用，幂等）。 */
    fun ensureLoaded(context: Context?) {
        if (context == null || loaded) return
        synchronized(loadLock) {
            if (loaded) return
            loaded = true
            try {
                enabled = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true)
            } catch (t: Throwable) {
                XLog.w("summary pref load failed: ${t.message}")
            }
        }
    }

    /** 切换开关并持久化，返回切换后的状态。 */
    fun toggle(context: Context): Boolean {
        synchronized(loadLock) { loaded = true }
        enabled = !enabled
        try {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (t: Throwable) {
            XLog.w("summary pref save failed: ${t.message}")
        }
        return enabled
    }

    /** 菜单标题跟随开关状态。 */
    fun title(): String = if (enabled) TITLE_ON else TITLE_OFF
}

/**
 * 摘要串行队列 —— 转写完成后按开关自动生成摘要。
 *
 * **无 UI 生成路径**（17.8.8 反编译结论）：官方 AI 指令 `SummaryGenerateAiCommand`
 * 就是这样无界面生成摘要的，与播放页点 tv_start_summary 走同一条管道：
 *
 * ```
 * MediaDBUtils.queryRecordById(mediaId)                       → Record
 * new SummaryRequestModel(mediaId, recordType, recordTime,    → 模型（掩码 776：
 *     theme, duration, callName, data, 0, false, 776)            theme 用默认值，后续补)
 * PrefUtil.getInt(ctx, KEY_DEFAULT_SUMMARY_THEME_CODE, -1)    → 用户默认模板 → SummaryTheme
 * q.g(mediaId, callback)                                      → 注册事件回调（动态代理 u）
 * q.h(model)                                                  → 投递（内部限 1 条并发）
 * onAISummaryFinished / onAISummaryError                      → CountDownLatch 等待结果
 * ```
 *
 * 结果落库由宿主自己完成（persistFinishedSummary → files/summary_bundles/bundle_xxx +
 * summary_cache_data 表），无需模块干预。
 *
 * **串行与限流**：云端 API，一条一条生成；每条之间隔 [GAP_MS]；
 * 管道忙（10002）自动重试，最多 [MAX_ATTEMPTS] 次、冷却 [RETRY_GAP_MS]；
 * 内容过短（10016）直接跳过（宿主自己也会弹「内容过短」toast）；
 * 已有摘要缓存 / 未完成转写的记录直接跳过。
 *
 * **与 ConvertQueue 的协同**：ConvertQueue 每条转写成功后把 mediaId 交到这里，
 * 并通过 [whenIdle] 等摘要管道空闲后才触发下一条转写 —— 转写与摘要全程共用一条云端
 * 串行通道，避免 QPS 限流。独立转写（无批量队列）完成时由 HookInstaller 的完成回调
 * 直接入队本队列。
 *
 * **两层定位**：摘要管道的核心类/方法（AISummaryTaskManager 的 g/h/i/b、回调接口 u、
 * 请求模型、缓存 DAO）在宿主里是单字母混淆名，跨版本会漂移。层1 按 17.8.8 已知名直接
 * 反射；层2 失败时用 DexKit 按日志串/SQL 串锚点 + 构造器签名重定位
 * （见 [SummaryDexKitResolver]），结果进程内缓存 —— 每进程至多解析一次，且层2 仅在
 * 层1 失败时才启动，正常路径零额外开销。两层都失败则摘要功能降级关闭，不影响转文本。
 */
internal object SummaryQueue {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = LinkedBlockingDeque<Long>()
    private val lock = Any()

    @Volatile
    private var current: Long = -1L

    /** 已 postDelayed(drainRunnable) 但还没执行（含条间间隔 / 重试冷却窗口）。 */
    @Volatile
    private var drainScheduled = false

    private var total = 0
    private var done = 0
    private var ok = 0
    private var skip = 0
    private var fail = 0

    /** 每条已自动重试的次数（管道忙 10002 用）。 */
    private val attempts = HashMap<Long, Int>()
    private val attemptsLock = Any()

    /** 管道空闲回调（ConvertQueue 的下一条转写等这里放行）。 */
    private val idleCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var classLoader: ClassLoader? = null

    /** 宿主 APK 路径（层2 DexKit 定位用，可为 null = 不启用层2）。 */
    @Volatile
    private var apkPath: String? = null

    @Volatile
    private var resolved: Resolved? = null

    @Volatile
    private var resolveFailed = false

    /** 与 ConvertIndexer 同款：从 HookInstaller 传入宿主 classLoader 与 APK 路径。 */
    fun attachClassLoader(cl: ClassLoader, apkPath: String? = null) {
        classLoader = cl
        if (apkPath != null) this.apkPath = apkPath
    }

    // ------------------------------------------------------------ 对外入口

    /** 管道是否仍有任务（含条间间隔窗口），供下游节流判断。 */
    fun isActive(): Boolean {
        synchronized(lock) { return current > 0L || pending.isNotEmpty() || drainScheduled }
    }

    /**
     * 管道空闲时执行回调：空闲则立刻（主线程）执行；
     * 忙则挂起，等队列真正排空（done>=total 且无在途条目）时执行。
     * 回调在锁外 / 主线程执行，回调内不得再进入本对象锁。
     */
    fun whenIdle(cb: () -> Unit) {
        val runNow: Boolean
        synchronized(lock) {
            runNow = !(current > 0L || pending.isNotEmpty() || drainScheduled)
            if (!runNow) idleCallbacks.add(cb)
        }
        if (runNow) handler.post(cb)
    }

    /**
     * 入队一条待生成摘要的 mediaId（调用方需先检查 [SummaryPrefs.enabled]）。
     * 队列空闲时延迟 [GAP_MS] 再启动 —— 刚转写完立刻又打云端，容易撞限流。
     */
    fun enqueue(mediaId: Long) {
        if (mediaId <= 0L) return
        synchronized(lock) {
            // 去重：已排队 / 正在跑的同一条目不重复入队
            if (current == mediaId || pending.contains(mediaId)) return
            pending.offer(mediaId)
            total++
            if (current <= 0L && !drainScheduled) {
                drainScheduled = true
                handler.postDelayed(drainRunnable, GAP_MS)
            }
        }
    }

    // ------------------------------------------------------------ 队列驱动

    private val drainRunnable = Runnable {
        var next: Long = -1L
        var startToast: String? = null
        val idleCbs = mutableListOf<() -> Unit>()
        synchronized(lock) {
            drainScheduled = false
            next = pending.poll() ?: -1L
            if (next <= 0L) {
                if (total > 0 && done >= total) {
                    if (total > 1) {
                        startToast = "摘要全部完成：成功$ok 跳过$skip 失败$fail"
                    }
                    total = 0; done = 0; ok = 0; skip = 0; fail = 0
                }
                if (current <= 0L && idleCallbacks.isNotEmpty()) {
                    idleCbs.addAll(idleCallbacks)
                    idleCallbacks.clear()
                }
            } else {
                current = next
                startToast =
                    if (total > 1) "正在生成摘要：第 ${done + 1}/$total 条" else "正在生成摘要…"
            }
        }
        idleCbs.forEach { runCatching { it() } }
        startToast?.let { toast(it) }
        if (next > 0L) {
            Thread { processItem(next) }.apply {
                isDaemon = true
                name = "SummaryQueue-Worker"
            }.start()
        }
    }
    // ------------------------------------------------------------ 单条生成

    private sealed class Outcome {
        object Ok : Outcome()
        data class Skip(val reason: String) : Outcome()
        data class Error(val code: Int) : Outcome()
        object Retry : Outcome()
    }

    private fun processItem(mediaId: Long) {
        val outcome: Outcome = try {
            generateOne(mediaId)
        } catch (t: Throwable) {
            XLog.w("summary generate failed for $mediaId", t)
            Outcome.Error(-1)
        }
        XLog.d("summary[$mediaId] outcome=$outcome")
        when (outcome) {
            is Outcome.Ok -> settle(mediaId, "ok", null)
            is Outcome.Skip -> settle(mediaId, "skip", outcome.reason)
            is Outcome.Error -> settle(mediaId, "fail", "${outcome.code}")
            is Outcome.Retry -> retryOrGiveUp(mediaId)
        }
    }

    private fun generateOne(mediaId: Long): Outcome {
        val r = resolve()
            ?: return Outcome.Skip("引擎不可用")

        // 开关持久化值可能尚未加载（本轮会话还没打开过列表菜单），这里补一次：
        // 用户此前已关掉自动摘要时，静默丢弃本条（开关生效以持久化值为准）
        try { SummaryPrefs.ensureLoaded(r.ctx()) } catch (_: Throwable) {}
        if (!SummaryPrefs.enabled) return Outcome.Skip("已关闭")

        // 已有摘要缓存 → 跳过（与官方 CardSummaryDispatcher 同判据）
        val cached = try {
            r.cacheGet.invoke(null, mediaId)
        } catch (t: Throwable) {
            XLog.d("summary cache query failed: ${t.message}")
            null
        }
        if (cached != null) return Outcome.Skip("已有摘要")

        // 未完成转写（空结果重试耗尽 / 转写失败）→ 没有正文可摘要
        val converted = try {
            r.checkConvertComplete.invoke(null, mediaId) as? Boolean ?: false
        } catch (t: Throwable) {
            XLog.d("checkAlreadyConvertComplete failed: ${t.message}")
            true // 检查失败不拦截，交给云端报错
        }
        if (!converted) return Outcome.Skip("未转写")

        val record = try {
            r.queryRecordById.invoke(null, mediaId)
        } catch (t: Throwable) {
            XLog.w("queryRecordById($mediaId) failed", t)
            null
        } ?: return Outcome.Skip("记录不存在")

        // 用户默认摘要模板（官方列表卡片批量生成同款取法）；读不到则用 App 默认
        val theme: Any? = try {
            val code = r.prefGetInt.invoke(null, r.ctx(), r.prefKey, -1) as? Int ?: -1
            if (code >= 0) r.themeCtor.newInstance(code, null) else null
        } catch (t: Throwable) {
            XLog.d("default theme resolve failed: ${t.message}")
            null
        }

        val model = r.modelCtor.newInstance(
            mediaId,
            r.getRecordType.invoke(record) as Int,
            r.getDateCreated.invoke(record) as Long,
            theme,
            r.getDuration.invoke(record) as Long,
            (r.getCallName.invoke(record) as? String) ?: "",
            (r.getData.invoke(record) as? String) ?: "",
            0,           // whereFrom = 0（非卡片入口）
            false,
            776          // Kotlin 默认参数掩码：theme / usedDefaultTemplate 走默认（与官方一致）
        )

        // 事件回调动态代理：Finished → 成功；Error → 记错误码。
        // 注意必须正确代理 Object 方法（equals/hashCode/toString）——
        // q.g 的回调容器（CopyOnWriteArrayList.contains）会对代理调 equals，
        // InvocationHandler 对 boolean 返回 null 会直接 NPE。
        val result = AtomicReference<Pair<Int, Int>?>(null) // (1=ok/0=fail, code)
        val latch = CountDownLatch(1)
        val callback = Proxy.newProxyInstance(
            r.uClass.classLoader, arrayOf(r.uClass)
        ) { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> args?.getOrNull(0) === proxy
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "SummaryCallbackProxy(mediaId=$mediaId)"
                }
                method.declaringClass == r.uClass -> when (method.name) {
                    "onAISummaryFinished" -> {
                        result.set(1 to 0); latch.countDown(); null
                    }
                    "onAISummaryError" -> {
                        result.set(0 to ((args?.getOrNull(1) as? Int) ?: -1))
                        latch.countDown(); null
                    }
                    else -> null
                }
                else -> null
            }
        }

        r.register.invoke(null, mediaId, callback)
        XLog.d("summary[$mediaId] posting q.h(model)")
        val started = try {
            r.post.invoke(null, model) as? Boolean ?: false
        } catch (t: Throwable) {
            XLog.w("q.h(model) invoke failed", t)
            false
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SUMMARY_TIMEOUT_MS)
        if (!started) {
            // 管道忙被拒：官方会先向回调发 onAISummaryError(10002) 再返回 false
            val waitMs = minOf(3000L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
            if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                unregisterSafe(r, mediaId, callback)
                return Outcome.Retry
            }
        }
        if (result.get() == null) {
            val remainMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainMs <= 0 || !latch.await(remainMs, TimeUnit.MILLISECONDS)) {
                // 超时保底：取消任务防管道被占死，注销回调防泄漏
                try { r.cancel.invoke(null, mediaId) } catch (_: Throwable) {}
                unregisterSafe(r, mediaId, callback)
                return Outcome.Error(-1)
            }
        }
        unregisterSafe(r, mediaId, callback)

        val res = result.get()
        return if (res != null && res.first == 1) {
            Outcome.Ok
        } else {
            when (val code = res?.second ?: -1) {
                SUMMARY_ERR_BUSY -> Outcome.Retry
                else -> Outcome.Error(code) // 10016 内容过短等 → 跳过
            }
        }
    }

    private fun unregisterSafe(r: Resolved, mediaId: Long, callback: Any) {
        try { r.unregister.invoke(null, mediaId, callback) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ 落账 / 重试

    /** 单条结果落账 + 驱动下一条（间隔 [GAP_MS]，防止 QPS 限流）。 */
    private fun settle(mediaId: Long, kind: String, detail: String?) {
        var msg: String? = null
        val idleCbs = mutableListOf<() -> Unit>()
        synchronized(lock) {
            current = -1L
            done++
            when (kind) {
                "ok" -> ok++
                "skip" -> skip++
                else -> fail++
            }
            val pos = if (total > 1) "：第 $done/$total 条" else ""
            msg = when (kind) {
                "ok" -> "摘要生成完成$pos"
                "skip" -> "摘要跳过（$detail）$pos"
                else -> "摘要失败($detail)$pos"
            }
            if (pending.isNotEmpty()) {
                drainScheduled = true
                handler.postDelayed(drainRunnable, GAP_MS)
            } else {
                idleCbs.addAll(idleCallbacks)
                idleCallbacks.clear()
            }
        }
        msg?.let { toast(it) }
        idleCbs.forEach { runCatching { it() } }
    }

    /** 管道忙重试：冷却 [RETRY_GAP_MS] 后重新入队；超过 [MAX_ATTEMPTS] 次按失败落账。 */
    private fun retryOrGiveUp(mediaId: Long) {
        var msg: String? = null
        val idleCbs = mutableListOf<() -> Unit>()
        synchronized(lock) {
            current = -1L
            done++
            val n = synchronized(attemptsLock) { attempts[mediaId] } ?: 0
            if (n < MAX_ATTEMPTS) {
                synchronized(attemptsLock) { attempts[mediaId] = n + 1 }
                pending.offer(mediaId)
                total++
                drainScheduled = true
                handler.postDelayed(drainRunnable, RETRY_GAP_MS)
                msg = "摘要请求受限，稍后自动重试（第 ${n + 1} 次）"
            } else {
                synchronized(attemptsLock) { attempts.remove(mediaId) }
                fail++
                msg = if (total > 1) "摘要失败：第 $done/$total 条" else "摘要生成失败"
                if (pending.isNotEmpty()) {
                    drainScheduled = true
                    handler.postDelayed(drainRunnable, GAP_MS)
                } else {
                    idleCbs.addAll(idleCallbacks)
                    idleCallbacks.clear()
                }
            }
        }
        msg?.let { toast(it) }
        idleCbs.forEach { runCatching { it() } }
    }

    // ------------------------------------------------------------ 反射解析（懒加载 + 两层定位）

    /** 未混淆符号（Kotlin 业务类保留原名，跨版本稳定；层1/层2 共用）。 */
    internal class CommonDeps(
        val checkConvertComplete: Method,
        val queryRecordById: Method,
        val themeCls: Class<*>,
        val themeCtor: Constructor<*>,
        val prefGetInt: Method,
        val prefKey: String,
        val appContextMethod: Method,
        val getRecordType: Method,
        val getDateCreated: Method,
        val getDuration: Method,
        val getCallName: Method,
        val getData: Method
    ) {
        fun ctx(): Context = appContextMethod.invoke(null) as Context
    }

    /**
     * 混淆符号集 —— 跨版本漂移风险所在：
     * - 层1：按 17.8.8 已知混淆名直接 `Class.forName` + `getMethod`；
     * - 层2：层1 失败时由 [SummaryDexKitResolver] 按字符串锚点/结构特征重定位。
     *
     * 两个类名（u 接口 / model）也会漂移，所以一并放进本集合。
     */
    internal class ObfuscatedDeps(
        val uClass: Class<*>,
        val register: Method,
        val unregister: Method,
        val post: Method,
        val cancel: Method,
        val cacheGet: Method,
        val modelCtor: Constructor<*>
    )

    internal class Resolved(
        val source: String,
        val common: CommonDeps,
        val obf: ObfuscatedDeps
    ) {
        val uClass: Class<*> get() = obf.uClass
        val register: Method get() = obf.register
        val unregister: Method get() = obf.unregister
        val post: Method get() = obf.post
        val cancel: Method get() = obf.cancel
        val cacheGet: Method get() = obf.cacheGet
        val checkConvertComplete: Method get() = common.checkConvertComplete
        val queryRecordById: Method get() = common.queryRecordById
        val modelCtor: Constructor<*> get() = obf.modelCtor
        val themeCtor: Constructor<*> get() = common.themeCtor
        val prefGetInt: Method get() = common.prefGetInt
        val prefKey: String get() = common.prefKey
        val getRecordType: Method get() = common.getRecordType
        val getDateCreated: Method get() = common.getDateCreated
        val getDuration: Method get() = common.getDuration
        val getCallName: Method get() = common.getCallName
        val getData: Method get() = common.getData

        fun ctx(): Context = common.ctx()
    }

    private fun resolve(): Resolved? {
        resolved?.let { return it }
        if (resolveFailed) return null
        val cl = classLoader ?: return null
        return synchronized(lock) {
            resolved?.let { return it }
            if (resolveFailed) return null
            val r = tryResolve(cl)
            if (r == null) {
                XLog.w("summary engine resolve failed (layer1 + dexkit), summary disabled")
                resolveFailed = true
                HookStatus.missing("summary_pipe", "层1 + DexKit 均未定位到摘要管道，摘要功能停用")
                null
            } else {
                resolved = r
                XLog.i("summary engine resolved via ${r.source}")
                HookStatus.found(
                    "summary_pipe",
                    if (r.source.startsWith("layer1")) "层1" else "DexKit",
                    "摘要管道已就绪"
                )
                r
            }
        }
    }

    private fun tryResolve(cl: ClassLoader): Resolved? {
        val common = resolveCommon(cl) ?: return null
        // 层1：17.8.8 已知混淆名
        val known = resolveKnownNames(cl, common.themeCls)
        // 层2：DexKit 兜底（仅层1失败才启动；结果进程内缓存，每进程至多一次）
        val obf = known ?: apkPath?.let { SummaryDexKitResolver.find(cl, it, common.themeCls) }
            ?: return null
        return Resolved(
            source = if (known != null) "layer1 (known names)" else "layer2 (dexkit)",
            common = common,
            obf = obf
        )
    }

    /** 摘要管道是懒解析（首次用到才 resolve）：状态面板打开时后台触发一次，把定位结果上报到面板。 */
    fun prefetchResolveAsync() {
        Thread {
            runCatching { resolve() }
        }.apply {
            isDaemon = true
            name = "SummaryQueue-Prefetch"
        }.start()
    }

    /** 队列运行状态（状态面板用）。 */
    fun statusText(): String = synchronized(lock) {
        if (current <= 0L && pending.isEmpty() && !drainScheduled) "空闲"
        else "进行中：待处理 ${pending.size}" + (if (current > 0L) "，当前条生成中" else "，间隔/冷却中")
    }

    /** 未混淆符号解析：任何一项缺失都整体失败（这些名字不混淆，失败说明版本大改）。 */
    private fun resolveCommon(cl: ClassLoader): CommonDeps? {
        return try {
            val themeCls = Class.forName(CLS_SUMMARY_THEME, false, cl)
            val recordCls = Class.forName(CLS_RECORD, false, cl)
            val prefCls = Class.forName(CLS_PREF_UTIL, false, cl)
            val appCls = Class.forName(CLS_BASE_APPLICATION, false, cl)
            CommonDeps(
                checkConvertComplete = Class.forName(CLS_CONVERT_DB_UTIL, false, cl)
                    .getMethod(M_CHECK_ALREADY_CONVERT_COMPLETE, java.lang.Long.TYPE),
                queryRecordById = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
                    .getMethod(M_QUERY_RECORD_BY_ID, java.lang.Long.TYPE),
                themeCls = themeCls,
                themeCtor = themeCls.getConstructor(Integer.TYPE, String::class.java),
                prefGetInt = prefCls.getMethod(
                    M_GET_INT, Context::class.java, String::class.java, Integer.TYPE
                ),
                prefKey = prefCls.getField(F_DEFAULT_THEME_KEY).get(null) as String,
                appContextMethod = appCls.getMethod(M_APP_CONTEXT),
                getRecordType = recordCls.getMethod("getRecordType"),
                getDateCreated = recordCls.getMethod("getDateCreated"),
                getDuration = recordCls.getMethod("getDuration"),
                getCallName = recordCls.getMethod("getCallName"),
                getData = recordCls.getMethod("getData")
            ).also { HookStatus.found("summary_common", "层1", "公共依赖就绪") }
        } catch (t: Throwable) {
            XLog.w("summary common deps resolve failed: ${t.message}")
            HookStatus.missing("summary_common", t.message ?: "公共依赖反射失败")
            null
        }
    }

    /** 层1：按 17.8.8 观测到的混淆名直接解析。 */
    private fun resolveKnownNames(cl: ClassLoader, themeCls: Class<*>): ObfuscatedDeps? {
        return try {
            val qCls = Class.forName(CLS_SUMMARY_TASK_MANAGER, false, cl)
            val uCls = Class.forName(CLS_SUMMARY_CALLBACK, false, cl)
            val modelCls = Class.forName(CLS_SUMMARY_REQUEST_MODEL, false, cl)
            val cacheDbCls = Class.forName(CLS_SUMMARY_CACHE_DB, false, cl)
            ObfuscatedDeps(
                uClass = uCls,
                register = qCls.getMethod(M_SUMMARY_REG_CALLBACK, java.lang.Long.TYPE, uCls),
                unregister = qCls.getMethod(M_SUMMARY_UNREG_CALLBACK, java.lang.Long.TYPE, uCls),
                post = qCls.getMethod(M_SUMMARY_POST, modelCls),
                cancel = qCls.getMethod(M_SUMMARY_CANCEL, java.lang.Long.TYPE),
                cacheGet = cacheDbCls.getMethod(M_SUMMARY_CACHE_GET, java.lang.Long.TYPE),
                modelCtor = modelCls.getConstructor(
                    java.lang.Long.TYPE,    // mediaId
                    Integer.TYPE,           // recordType
                    java.lang.Long.TYPE,    // recordTime
                    themeCls,               // theme（可 null）
                    java.lang.Long.TYPE,    // recordDuration
                    String::class.java,     // recordCallName
                    String::class.java,     // recordFilePath
                    Integer.TYPE,           // whereFrom
                    java.lang.Boolean.TYPE, // usedDefaultTemplate
                    Integer.TYPE            // 默认参数掩码
                )
            )
        } catch (t: Throwable) {
            XLog.d("summary layer1 (known names) failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------ 杂项

    private fun toast(msg: String) {
        handler.post {
            try {
                // 首条开始 toast 可能先于 worker 的 resolve()，这里懒解析一次拿 Context
                val ctx = resolve()?.ctx() ?: return@post
                SummaryPrefs.ensureLoaded(ctx)
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private const val GAP_MS = 5000L

    /** 摘要任务超时保底（官方 OclawSummaryGenerateApi.GENERATE_TIMEOUT_MS 同值）。 */
    private const val SUMMARY_TIMEOUT_MS = 180_000L

    /** 管道忙自动重试的最大次数（首次 + 重试 MAX_ATTEMPTS 次）。 */
    private const val MAX_ATTEMPTS = 2

    /** 重试冷却间隔。 */
    private const val RETRY_GAP_MS = 30_000L
}
