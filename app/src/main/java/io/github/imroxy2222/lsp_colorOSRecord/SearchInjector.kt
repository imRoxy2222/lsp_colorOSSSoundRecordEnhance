package io.github.imroxy2222.lsp_colorOSRecord

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 转写正文搜索结果注入（见 search-transcript.md §5）。
 *
 * Hook 点：摘要关键字查询 `SummaryCacheDBHelper.b.k().b(String)` —— Room DAO 封装，
 * SQL 为 `SELECT * FROM summary_cache_data WHERE INSTR(summary_content, ?) > 0 AND choose_state = 0`
 * （17.8.8 中为 `com.soundrecorder.summary.request.database.f#b(String)`，全局仅搜索链路一处调用）。
 *
 * after-hook：用自己的 FTS 索引搜转写正文命中，**伪装成摘要条目**（SummaryCacheEntity，
 * 纯内存对象不落库）追加进返回 List。下游 app 自动完成清洗、关键词高亮（b.a(content, keyword)）、
 * 按 mediaId 合并去重（真摘要优先）、点击跳播放页 —— 零 UI 代码。
 *
 * 定位策略（类名 f/b 均为混淆名，跨版本会漂移）：
 * - 层1a：直接 Class.forName 已知混淆名（对 17.8.8 有效）；
 * - 层1b：经 `b.k()` 的返回值运行时类定位（不依赖 DAO 类名，但依赖 helper 类名）；
 * - 层2：DexKit 按 SQL 字符串锚点定位（跨版本兜底，该 SQL 全局唯一）。
 */
internal class SearchInjector(
    private val api: XposedInterface,
    private val param: XposedModuleInterface.PackageReadyParam
) {

    /** 注入条目上限（搜索结果摘要区一次最多混入多少条录音）。 */
    private val MAX_ENTITIES = 50

    /** 原始命中行数上限（同一条录音多行命中占多个名额）。 */
    private val MAX_RAW_HITS = 400

    /** 片段长度上限（字符）。 */
    private val SNIPPET_MAX = 200

    /** 注入条目"转文本"标签（替换原生"摘要："前缀；原生 convert 行的资源值是"文本："，不符合需求）。 */
    private val TRANSCRIPT_PREFIX = "转文本："

    /** 注入片段指纹池：搜索结果绑定 UI 时据此识别"这是转写结果"，把标签从"摘要"改成"转文本"。 */
    private val snippetRegistry = HashSet<String>()

    fun install() {
        val method = locateQueryMethod() ?: run {
            XLog.w("summary keyword query not found, transcript search disabled")
            return
        }
        api.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    injectTranscriptResults(chain.args.getOrNull(0) as? String, result)
                } catch (t: Throwable) {
                    XLog.w("transcript search inject failed", t)
                }
                result
            }
        XLog.i("hooked ${method.declaringClass.name}#${method.name}(String) [transcript-search]")
        // 注入条目标签修正：摘要 → 转文本
        installTranscriptLabelFix()
    }

    // ---------------------------------------------------------------- 定位

    private fun locateQueryMethod(): Method? {
        val cl = param.classLoader
        // 层1a：直接类名
        try {
            val daoCls = Class.forName(CLS_SUMMARY_DAO, false, cl)
            findKeywordQuery(daoCls)?.let {
                XLog.i("summary dao located (layer1a): ${daoCls.name}")
                return it
            }
        } catch (t: Throwable) {
            XLog.d("layer1a summary dao not found: ${t.javaClass.simpleName}")
        }
        // 层1b：经 helper.k() 返回值的运行时类
        try {
            val helper = Class.forName(CLS_SUMMARY_DB_HELPER, false, cl)
            val k = helper.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType != Void.TYPE && !it.returnType.isPrimitive
            }
            if (k != null) {
                val instance = k.invoke(null)
                if (instance != null) {
                    findKeywordQuery(instance.javaClass)?.let {
                        XLog.i("summary dao located (layer1b): ${instance.javaClass.name}")
                        return it
                    }
                }
            }
        } catch (t: Throwable) {
            XLog.d("layer1b summary dao not found: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 层2：DexKit SQL 锚点
        return try {
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                val found = bridge.findMethod {
                    matcher { usingStrings(ANCHOR_SUMMARY_KEYWORD_QUERY) }
                }
                for (data in found) {
                    val m = try {
                        data.getMethodInstance(cl)
                    } catch (t: Throwable) {
                        XLog.w("dexkit load ${data.className}#${data.name} failed", t)
                        continue
                    }
                    if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                        XLog.i("summary dao located (dexkit): ${data.className}#${data.name}")
                        return m
                    }
                }
                null
            }
        } catch (t: Throwable) {
            XLog.w("dexkit summary dao locate failed", t)
            null
        }
    }

    /** DAO 类里"唯一 (String) → List"的方法即关键字查询。 */
    private fun findKeywordQuery(daoCls: Class<*>): Method? {
        val candidates = daoCls.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java &&
                List::class.java.isAssignableFrom(it.returnType)
        }
        return if (candidates.size == 1) {
            candidates[0].isAccessible = true
            candidates[0]
        } else {
            XLog.d("keyword query candidates=${candidates.size} in ${daoCls.name}")
            null
        }
    }

    // ---------------------------------------------------------------- 注入

    private fun injectTranscriptResults(keyword: String?, result: Any?) {
        if (keyword.isNullOrEmpty() || keyword.isBlank()) return
        val list = result as? MutableList<Any> ?: return
        val ctx = ConvertIndexer.appContext() ?: return
        // 首次搜索懒触发对账（不等待，第一次搜索可能不全）
        ConvertIndexer.ensureReconciled()

        val hits = ConvertIndexDb.search(ctx, keyword, MAX_RAW_HITS)
        if (hits.isEmpty()) return

        // 按录音聚合（查询已按 record_id DESC 排序，新录音在前）
        val byRecord = LinkedHashMap<Long, MutableList<ConvertIndexDb.Hit>>()
        for (h in hits) byRecord.getOrPut(h.recordId) { ArrayList() }.add(h)

        val ctor = entityConstructor() ?: run {
            XLog.w("SummaryCacheEntity ctor not found, skip inject")
            return
        }
        val mediaIdGetter = try {
            entityClass()?.getMethod(M_GET_MEDIA_ID)
        } catch (t: Throwable) {
            null
        }

        var added = 0
        for ((recordId, recordHits) in byRecord) {
            if (added >= MAX_ENTITIES) break
            // 同一录音已有真摘要命中：下游按 mediaId 去重只留先入的（真摘要），跳过注入
            if (mediaIdGetter != null && list.any { (mediaIdGetter.invoke(it) as? Long) == recordId }) {
                continue
            }
            recordHits.sortBy { it.startUs }
            val snippet = buildSnippet(recordHits.map { it.text }, keyword)
            val entity = newEntity(ctor, recordId, recordHits.first().fileMtime, snippet) ?: continue
            list.add(entity)
            rememberSnippet(snippet)
            added++
        }
        if (added > 0) {
            XLog.i("transcript search: \"$keyword\" injected into $added record(s) (hits=${hits.size})")
        }
    }

    // ---------------------------------------------------------------- 注入条目标签修正（摘要 → 转文本）

    /**
     * app 原生把摘要内容显示为 "摘要：xxx"（xg.b.D() 里 getString(R.string.summary_with_colon) 前缀）。
     * 转写注入条目伪装成摘要走同一条 UI 路径，因此需把标签改回"转文本："。
     *
     * 做法：hook 搜索条目绑定方法（17.8.8 为 xg.b.D()，无参 void，DexKit 有唯一日志锚点兜底），
     * after 阶段取 itemView 里的 summary_text TextView：
     * - 文本以"摘要："开头、且内容匹配本次注入片段指纹 → 原地替换前缀为"转文本："
     *   （SpannableStringBuilder 复制后 replace，前缀样式 span 重新铺上，关键词高亮位置随 replace 自动平移）；
     * - 其余情况（真摘要 / 中心搜索结果）一律不动。
     *
     * 幂等：改写后文本以"转文本："开头，startsWith("摘要：") 不再成立，重复绑定不会二次处理。
     */
    private fun installTranscriptLabelFix() {
        val summaryTextId = resolveRId(
            param.classLoader,
            "com.soundrecorder.browsefile.R\$id",
            "summary_text",
        ) ?: run {
            XLog.w("R.id.summary_text not found, transcript label fix disabled")
            return
        }
        val bindMethod = locateSearchItemBind() ?: run {
            XLog.w("search item viewholder bind not found, transcript label fix disabled")
            return
        }
        api.hook(bindMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                chain.proceed()
                try {
                    rewriteTranscriptLabel(chain.thisObject, summaryTextId)
                } catch (t: Throwable) {
                    XLog.w("transcript label rewrite failed", t)
                }
            }
        XLog.i("hooked ${bindMethod.declaringClass.name}#${bindMethod.name}() [transcript-label]")
    }

    /** 定位搜索条目绑定方法：层1 已知混淆名 xg.b#D；层2 DexKit 日志锚点找类再取 D。 */
    private fun locateSearchItemBind(): Method? {
        val cl = param.classLoader
        var cls: Class<*>? = try {
            Class.forName(CLS_ITEM_SEARCH_VIEW, false, cl)
        } catch (t: Throwable) {
            null
        }
        if (cls == null) {
            cls = try {
                DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                    val found = bridge.findClass {
                        matcher { usingStrings(ANCHOR_SEARCH_ITEM_VIEW) }
                    }
                    found.firstOrNull()?.let { Class.forName(it.name, false, cl) }
                }
            } catch (t: Throwable) {
                XLog.w("dexkit search item viewholder locate failed", t)
                null
            }
        }
        val c = cls ?: return null
        val m = c.declaredMethods.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType == Void.TYPE && it.name == M_BIND_SEARCH_ITEM
        } ?: return null
        m.isAccessible = true
        return m
    }

    private fun rewriteTranscriptLabel(viewHolder: Any?, summaryTextId: Int) {
        if (viewHolder == null) return
        synchronized(snippetRegistry) {
            if (snippetRegistry.isEmpty()) return
        }
        // RecyclerView.ViewHolder.itemView 是框架稳定字段
        val itemView = try {
            viewHolder.javaClass.getField("itemView").get(viewHolder) as? View
        } catch (t: Throwable) {
            null
        } ?: return
        val tv = itemView.findViewById<View>(summaryTextId) as? TextView ?: return
        val text = tv.text ?: return
        val str = text.toString()
        // 原生前缀（摘要：/Summary: ），从当前上下文实时取，兼容多语言
        val prefix = tv.context.getString(
            resolveRId(tv.context.classLoader, "com.soundrecorder.common.R\$string", "summary_with_colon")
                ?: return,
        )
        if (prefix.isEmpty() || !str.startsWith(prefix)) return
        val normalized = fingerprint(str.removePrefix(prefix))
        val isOurs = synchronized(snippetRegistry) {
            snippetRegistry.any { it.isNotEmpty() && normalized.contains(it) }
        }
        if (!isOurs) return

        // SpannableStringBuilder(CharSequence) 会连同 span 一起拷贝，直接在副本上改
        val sb = SpannableStringBuilder(text)
        val pl = prefix.length
        val spans = sb.getSpans(0, pl, Any::class.java)
        val spanFlags = spans.map { it to sb.getSpanFlags(it) }
        sb.replace(0, pl, TRANSCRIPT_PREFIX)
        val nl = TRANSCRIPT_PREFIX.length
        for ((sp, flags) in spanFlags) {
            sb.removeSpan(sp)
            sb.setSpan(sp, 0, nl, flags)
        }
        tv.text = sb
        XLog.d("transcript label rewritten: \"${prefix.trim()}\" -> \"$TRANSCRIPT_PREFIX\"")
    }

    /** 注入片段指纹：只保留字母/数字（app 映射时会剥掉冒号等标点，指纹需容忍），截前 16 字符。 */
    private fun rememberSnippet(snippet: String) {
        synchronized(snippetRegistry) {
            if (snippetRegistry.size > 1024) snippetRegistry.clear()
            snippetRegistry.add(fingerprint(snippet))
        }
    }

    private fun fingerprint(s: String): String = s.filter { it.isLetterOrDigit() }.take(16)

    /** 反射取 app 资源 id（R$xx.FIELD），失败返回 null。 */
    private fun resolveRId(cl: ClassLoader?, rCls: String, field: String): Int? = try {
        cl?.loadClass(rCls)?.getField(field)?.getInt(null)
    } catch (t: Throwable) {
        null
    }

    /** 片段：命中行按时间序拼接；超长时以首次命中位置为中心截窗（保证关键词在窗口内）。 */
    private fun buildSnippet(lines: List<String>, keyword: String): String {
        val joined = lines.joinToString(" ")
        if (joined.length <= SNIPPET_MAX) return joined
        val kwIdx = joined.indexOf(keyword).coerceAtLeast(0)
        val start = (kwIdx - SNIPPET_MAX / 4).coerceAtLeast(0)
        val end = (start + SNIPPET_MAX).coerceAtMost(joined.length)
        val window = joined.substring(start, end)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < joined.length) "…" else ""
        return prefix + window + suffix
    }

    // ---------------------------------------------------------------- SummaryCacheEntity 构造

    private fun entityClass(): Class<*>? = try {
        Class.forName(CLS_SUMMARY_CACHE_ENTITY, false, param.classLoader)
    } catch (t: Throwable) {
        XLog.d("SummaryCacheEntity not found: ${t.message}")
        null
    }

    /**
     * 23 参构造（17.8.8，f.b(String) 的游标映射顺序）：
     * (long id, long mediaId, long timeStamp, int chooseState, String summaryContent,
     *  String summaryAgent, String summaryEntity, String summaryTrace, int summaryStyle,
     *  String summaryTheme, String title, String language, long lastSelectTime, int userStop,
     *  String summaryData, String summaryExportData, int tagHasChange, String summaryThemeInfo,
     *  String bundleDirName, String templateId, String templateVersion, int isRecycle, int vipLevel)
     * 下游真正用到的只有 getId()/getTimeStamp()/getMediaId()/getSummaryContent()。
     */
    private fun entityConstructor(): Constructor<*>? {
        val cls = entityClass() ?: return null
        return cls.declaredConstructors.firstOrNull { it.parameterTypes.size == 23 }?.apply {
            isAccessible = true
        }
    }

    private fun newEntity(ctor: Constructor<*>, recordId: Long, fileMtime: Long, snippet: String): Any? {
        val args = arrayOfNulls<Any?>(23)
        for (i in args.indices) {
            when (ctor.parameterTypes[i]) {
                java.lang.Long.TYPE -> args[i] = when (i) {
                    0 -> -recordId      // id：负数避免与真实摘要 _id 撞车
                    1 -> recordId       // mediaId：搜索结果合并/点击跳转的键
                    2 -> fileMtime      // timeStamp：展示/排序用
                    else -> 0L
                }
                java.lang.Integer.TYPE -> args[i] = 0
                else -> args[i] = if (i == 4) snippet else null  // 4 = summaryContent
            }
        }
        return try {
            ctor.newInstance(*args)
        } catch (t: Throwable) {
            XLog.w("build SummaryCacheEntity failed", t)
            null
        }
    }
}
