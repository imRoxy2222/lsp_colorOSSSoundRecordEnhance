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
        // 结果伪装能力独立上报：不受后续 DAO / bind 任何定位结果影响。
        // 早期把探测放在 installTranscriptLabelFix 的 bind 成功分支之后，
        // 17.9.6 里 bind 失败即提前 return，导致本项长期误显示「定位中」。
        val ctorProbe = entityConstructor()
        if (ctorProbe != null) {
            HookStatus.found("search_entity", "层1", "实际: ${ctorProbe.declaringClass.name}")
        } else {
            HookStatus.missing("search_entity", "23 参构造未找到（转写搜索注入失效）")
        }

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
                HookStatus.found("search_dao", "层1a", "实际: ${daoCls.name}")
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
                        HookStatus.found("search_dao", "层1b", "实际: ${instance.javaClass.name}")
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
                        HookStatus.found("search_dao", "DexKit", "实际: ${data.className}")
                        return m
                    }
                }
                HookStatus.missing("search_dao", "层1a/1b/DexKit 均未定位，转写搜索失效")
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
     * app 原生把摘要内容显示为 "摘要：xxx"（条目 ViewHolder 的 bind 方法里
     * getString(R.string.summary_with_colon) 前缀；17.8.8 = xg.b.D()，17.9.6 = dh.c.F()）。
     * 转写注入条目伪装成摘要走同一条 UI 路径，因此需把标签改回"转文本："。
     *
     * 做法：hook 搜索条目绑定方法，after 阶段取 itemView 里的 summary_text TextView：
     * - 文本以"摘要："开头、且内容匹配本次注入片段指纹 → 原地替换前缀为"转文本："；
     * - 其余情况（真摘要 / 中心搜索结果）一律不动。
     *
     * 幂等：改写后文本以"转文本："开头，startsWith("摘要：") 不再成立。
     */
    private fun installTranscriptLabelFix() {
        val summaryTextId = resolveRId(
            param.classLoader,
            "com.soundrecorder.browsefile.R\$id",
            "summary_text",
        ) ?: run {
            XLog.w("R.id.summary_text not found, transcript label fix disabled")
            HookStatus.missing("search_bind", "R.id.summary_text 未解析（标签修正停用）")
            return
        }
        // summary_with_colon 的资源 id 兼作绑定方法的 DexKit 锚点（bind 会内联该 id 常量）
        val summaryPrefixId = resolveRId(
            param.classLoader,
            "com.soundrecorder.common.R\$string",
            "summary_with_colon",
        ) ?: run {
            XLog.w("R.string.summary_with_colon not found, transcript label fix disabled")
            HookStatus.missing("search_bind", "R.string.summary_with_colon 未解析（标签修正停用）")
            return
        }
        val bindMethod = locateSearchItemBind(summaryPrefixId) ?: run {
            XLog.w("search item viewholder bind not found, transcript label fix disabled")
            // 内层已写入更具体的失败层时，不要用通用文案覆盖它
            HookStatus.missingIfPending(
                "search_bind",
                "定位失败（注入条目会显示为\"摘要：\"前缀，功能不受影响）",
            )
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

    /**
     * 定位搜索条目绑定方法（跨版本自适应）：
     * - 类：混淆名候选仅作快速路径，且必须通过稳定基类 [CLS_ITEM_SEARCH_BASE] 校验。
     *   17.9.6 的 dex 里 xg.b 与 dh.c 同时存在（前者是 RecyclerView behavior 相关类），
     *   早期「能加载即采用」会先命中 xg.b，导致类内方法 / id 锚点全部落空，
     *   诊断报「标签修正缺失」。候选全部不合格时用稳定串 "ItemSearchView" 做 DexKit 兜底。
     * - 方法：主路径从基类 h 的「抽象无参 void」方法名反推（17.8.8 = D，17.9.6 = F），
     *   不依赖混淆方法名；再保留已知候选名与内联资源 id 的 DexKit 兜底。
     */
    private fun locateSearchItemBind(summaryPrefixId: Int): Method? {
        val cl = param.classLoader
        val base = searchItemBase(cl)
        val c = resolveSearchItemClass(cl, base) ?: run {
            HookStatus.missing("search_bind", "类未定位（混淆名候选均未通过基类校验且 DexKit 锚点失效）")
            return null
        }
        XLog.d("search item view class: ${c.name}")

        // 层1：稳定基类 h 中唯一的抽象无参 void 方法即 bind 方法声明
        val baseNames = (base?.declaredMethods ?: emptyArray())
            .filter {
                Modifier.isAbstract(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == Void.TYPE
            }
            .map { it.name }
            .toSet()
        val byBase = c.declaredMethods.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType == Void.TYPE && it.name in baseNames
        }
        if (byBase != null) {
            byBase.isAccessible = true
            XLog.i("search item bind located (base signature): ${c.name}#${byBase.name}")
            HookStatus.found("search_bind", "层1(基类签名)", "实际: ${c.name}#${byBase.name}")
            return byBase
        }

        // 层1b：已知混淆方法名候选（类已通过基类校验，不会误命中同名无关类）
        val byName = c.declaredMethods.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType == Void.TYPE && it.name in M_BIND_SEARCH_ITEM_CANDIDATES
        }
        if (byName != null) {
            byName.isAccessible = true
            HookStatus.found("search_bind", "层1(候选名)", "实际: ${c.name}#${byName.name}")
            return byName
        }

        // 层2：方法名继续漂移 → 用内联资源 id 兜底，优先取已知候选名
        return try {
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                val found = bridge.findMethod {
                    matcher {
                        declaredClass(c.name)
                        usingNumbers(summaryPrefixId)
                        paramCount(0)
                        returnType("void")
                    }
                }
                XLog.d("dexkit bind method candidates: ${found.size}")
                val picked = found.firstOrNull { it.name in M_BIND_SEARCH_ITEM_CANDIDATES }
                    ?: found.firstOrNull()
                picked?.getMethodInstance(cl)?.also {
                    it.isAccessible = true
                    XLog.i("search item bind located (dexkit id-anchor): ${c.name}#${it.name}")
                    HookStatus.found("search_bind", "DexKit(id 锚点)", "实际: ${c.name}#${it.name}")
                } ?: run {
                    HookStatus.missing("search_bind", "id 锚点未命中（类=${c.name}，方法签名可能变化）")
                    null
                }
            }
        } catch (t: Throwable) {
            XLog.w("dexkit bind method locate failed", t)
            HookStatus.missing("search_bind", "DexKit 定位异常: ${t.message}")
            null
        }
    }

    /** 搜索条目 ViewHolder 的稳定抽象基类（未混淆）。 */
    private fun searchItemBase(cl: ClassLoader): Class<*>? = try {
        Class.forName(CLS_ITEM_SEARCH_BASE, false, cl)
    } catch (_: Throwable) {
        null
    }

    /** 候选混淆名必须确为 h 的子类；基类不可用时退化为方法形状校验。 */
    private fun isSearchItemView(c: Class<*>, base: Class<*>?): Boolean {
        if (base != null) {
            var p: Class<*>? = c.superclass
            while (p != null) {
                if (p == base) return true
                p = p.superclass
            }
            return false
        }
        return c.declaredMethods.any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType == Void.TYPE && it.name in M_BIND_SEARCH_ITEM_CANDIDATES
        }
    }

    /** 逐个验证候选类名；全部不合格时用稳定串 "ItemSearchView" 做 DexKit 兜底。 */
    private fun resolveSearchItemClass(cl: ClassLoader, base: Class<*>?): Class<*>? {
        for (name in CLS_ITEM_SEARCH_VIEW_CANDIDATES) {
            val c = try { Class.forName(name, false, cl) } catch (_: Throwable) { null } ?: continue
            if (!isSearchItemView(c, base)) {
                XLog.d("search item candidate rejected: $name (super=${c.superclass?.name})")
                continue
            }
            XLog.i("search item view located (layer1): ${c.name}")
            return c
        }
        return try {
            DexKitBridge.create(param.applicationInfo.sourceDir).use { bridge ->
                bridge.findClass {
                    matcher { usingStrings(ANCHOR_SEARCH_ITEM_VIEW_TAG) }
                }.mapNotNull { data ->
                    try { Class.forName(data.name, false, cl) } catch (_: Throwable) { null }
                }.firstOrNull { c -> isSearchItemView(c, base) }
                    ?.also { XLog.i("search item view located (dexkit tag): ${it.name}") }
            }
        } catch (t: Throwable) {
            XLog.w("dexkit search item view locate failed", t)
            null
        }
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
     * 23 参构造（17.8.8 与 17.9.6 复核完全一致，f.b(String) 的游标映射顺序）：
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
