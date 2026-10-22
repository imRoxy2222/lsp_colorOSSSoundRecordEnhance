package io.github.imroxy2222.lsp_colorOSRecord

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 转写索引调度器（见 search-transcript.md §4）。
 *
 * - **单写者模型**：所有索引写入走一个 HandlerThread，搜索只读（SQLite 同连接跨线程由
 *   框架内部串行化，读写并发安全）；
 * - **三层增量同步**：
 *   1. 转写完成 hook（updateCompleteAndFilePathByRecordId / 三个完成 toast）→ [scheduleIndex]；
 *   2. 删除 hook（ConvertDeleteUtil.deleteConvertData）→ [scheduleDelete]；
 *   3. 首次搜索前懒对账 [ensureReconciled]：convert 目录 vs files_meta 的
 *      mtime/size 差集，只重索引变化的文件，清理已删除文件对应的死行；
 * - 同一 recordId 的待索引任务用 pending set 去重（多个 hook 点同时触发只索引一次）。
 */
internal object ConvertIndexer {

    private val writer = HandlerThread("convert-index-writer").apply { start() }
    private val handler = Handler(writer.looper)
    private val reconciled = AtomicBoolean(false)
    private val pendingIndex = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    @Volatile
    private var classLoader: ClassLoader? = null

    /** HookInstaller 安装时注入宿主 classLoader（反射 ConvertDbUtil 用）。 */
    fun attachClassLoader(cl: ClassLoader) {
        if (classLoader == null) classLoader = cl
    }

    // ---------------------------------------------------------------- Context

    /**
     * 宿主 Application Context（模块以录音机 uid 运行，直接反射
     * ActivityThread.currentApplication()，不依赖宿主 Application 类名）。
     */
    fun appContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Context
    } catch (t: Throwable) {
        XLog.d("currentApplication failed: ${t.message}")
        null
    }

    // ---------------------------------------------------------------- 层一：转写完成

    /**
     * 转写完成 hook 的入口：索引指定 txt。
     * txt 缺失（写入尚未完成等时序问题）时跳过，由层三对账兜底。
     */
    fun scheduleIndex(recordId: Long, path: String?) {
        if (recordId <= 0L || path.isNullOrEmpty()) return
        if (!pendingIndex.add(recordId)) return
        handler.post {
            pendingIndex.remove(recordId)
            try {
                val ctx = appContext() ?: return@post
                val f = File(path)
                if (f.isFile && f.length() > 0) {
                    val t0 = System.currentTimeMillis()
                    val ok = ConvertIndexDb.indexFile(ctx, recordId, f)
                    XLog.d("index recordId=$recordId file=${f.name} ok=$ok cost=${System.currentTimeMillis() - t0}ms")
                } else {
                    XLog.d("index skip (txt missing): recordId=$recordId path=$path")
                }
            } catch (t: Throwable) {
                XLog.w("index task failed recordId=$recordId", t)
            }
        }
    }

    /**
     * 只有 mediaId 时的索引入口（完成 toast hook）：反射
     * `ConvertDbUtil.selectByRecordId(recordId).getConvertTextfilePath()` 拿 txt 路径。
     */
    fun scheduleIndexByMediaId(recordId: Long) {
        if (recordId <= 0L) return
        handler.post {
            try {
                val path = resolveConvertTextFilePath(recordId)
                if (path.isNullOrEmpty()) {
                    XLog.d("index by mediaId skip (no path): recordId=$recordId")
                } else {
                    scheduleIndex(recordId, path)
                }
            } catch (t: Throwable) {
                XLog.w("index by mediaId failed recordId=$recordId", t)
            }
        }
    }

    // ---------------------------------------------------------------- 层二：删除

    fun scheduleDelete(recordId: Long) {
        if (recordId <= 0L) return
        handler.post {
            try {
                val ctx = appContext() ?: return@post
                ConvertIndexDb.deleteRecord(ctx, recordId)
                XLog.d("index deleted: recordId=$recordId")
            } catch (t: Throwable) {
                XLog.w("delete index failed recordId=$recordId", t)
            }
        }
    }

    /** 删除 hook 只有媒体路径时：反射 selectByMediaPath 反查 recordId 再删。 */
    fun scheduleDeleteByMediaPath(mediaPath: String?) {
        if (mediaPath.isNullOrEmpty()) return
        handler.post {
            try {
                val recordId = resolveRecordIdByMediaPath(mediaPath)
                if (recordId > 0L) scheduleDelete(recordId)
            } catch (t: Throwable) {
                XLog.w("delete by mediaPath failed path=$mediaPath", t)
            }
        }
    }

    // ---------------------------------------------------------------- 层三：对账

    /**
     * 首次搜索前懒触发一次全量对账（AtomicBoolean 保证进程内只跑一次）。
     * 搜索本身不等待对账完成 —— 第一次搜索可能结果不全，属可接受降级。
     */
    fun ensureReconciled() {
        if (!reconciled.compareAndSet(false, true)) return
        handler.post {
            try {
                reconcileNow()
            } catch (t: Throwable) {
                XLog.w("reconcile failed", t)
            }
        }
    }

    /**
     * 对账：
     * 1. convert 目录 vs files_meta：新文件/变化文件（size 或 mtime 不同）→ 重索引；
     * 2. files_meta 有、目录没有 → 删索引行（录音/转写被删但删除 hook 漏掉的兜底）。
     * 首次全量索引（数万条）可能需要几分钟，在写线程后台进行，不影响 app。
     */
    fun reconcileNow() {
        val ctx = appContext() ?: return
        val dir = File(ctx.filesDir, ConvertIndexDb.CONVERT_DIR)
        val txts = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
        val meta = ConvertIndexDb.getFilesMeta(ctx)
        if (txts == null) {
            XLog.d("reconcile: convert dir missing")
            return
        }
        val t0 = System.currentTimeMillis()
        var indexed = 0
        var deleted = 0
        val seen = HashSet<Long>()
        for (f in txts) {
            val rid = recordIdFromFileName(f.name) ?: continue
            seen.add(rid)
            val m = meta[rid]
            if (m == null || m.fileName != f.name || m.fileSize != f.length() || m.fileMtime != f.lastModified()) {
                if (ConvertIndexDb.indexFile(ctx, rid, f)) indexed++
            }
        }
        for (rid in meta.keys) {
            if (!seen.contains(rid)) {
                ConvertIndexDb.deleteRecord(ctx, rid)
                deleted++
            }
        }
        XLog.i(
            "reconcile done: txt=${txts.size} meta=${meta.size} indexed=$indexed deleted=$deleted " +
                "cost=${System.currentTimeMillis() - t0}ms"
        )
    }

    // ---------------------------------------------------------------- 反射辅助

    /** `ConvertDbUtil.selectByRecordId(recordId)` → `getConvertTextfilePath()`。 */
    private fun resolveConvertTextFilePath(recordId: Long): String? {
        val cl = classLoader ?: return null
        val record = Class.forName(CLS_CONVERT_DB_UTIL, false, cl)
            .getMethod(M_SELECT_BY_RECORD_ID, java.lang.Long.TYPE)
            .invoke(null, recordId) ?: return null
        return record.javaClass.getMethod(M_GET_CONVERT_TEXTFILE_PATH).invoke(record) as? String
    }

    /** `ConvertDbUtil.selectByMediaPath(mediaPath)` → `getRecordId()`。 */
    private fun resolveRecordIdByMediaPath(mediaPath: String): Long {
        val cl = classLoader ?: return -1L
        val record = Class.forName(CLS_CONVERT_DB_UTIL, false, cl)
            .getMethod(M_SELECT_BY_MEDIA_PATH, String::class.java)
            .invoke(null, mediaPath) ?: return -1L
        return record.javaClass.getMethod(M_GET_RECORD_ID).invoke(record) as? Long ?: -1L
    }

    /**
     * 从文件名提取 recordId：`convert_<id>_<标题>.txt`。
     * （NewConvertResultUtil.genFileName：TRANS_PREFIX=convert_，TRANS_SPLIT=_）
     */
    fun recordIdFromFileName(name: String): Long? {
        if (!name.startsWith("convert_")) return null
        val end = name.indexOf('_', "convert_".length)
        if (end <= "convert_".length) return null
        return name.substring("convert_".length, end).toLongOrNull()
    }
}
