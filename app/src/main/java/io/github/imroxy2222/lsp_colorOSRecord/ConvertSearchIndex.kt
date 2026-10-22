package io.github.imroxy2222.lsp_colorOSRecord

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 转写正文搜索索引库（模块私有 SQLite，见 search-transcript.md §3）。
 *
 * - 位置：`context.filesDir/module_search_index/convert_index.db`，完全不碰 app 自己的库；
 * - `lines` 存正文行（原文，用于结果片段），`lines_fts` 是全文索引，`files_meta` 存
 *   文件级元数据（增量对账的比对依据）；
 * - 中文子串匹配：FTS 默认分词器对连续中文只生成整段 token，搜不到子串。两种模式：
 *   1. **FTS4 + 逐字空格**（全版本可用）：入库文本逐字符插空格，查询词同样逐字空格化，
 *      unicode61 分词后每字独立 token，短语查询即子串匹配；
 *   2. **FTS5 trigram**（SQLite ≥ 3.34，Android 14+）：原生子串匹配，存原文。
 *      运行时探测可用则优先，否则回退模式 1；
 * - 模式持久化：按 sqlite_master 里 `lines_fts` 的建表 SQL 判断，跨版本升级不重建错乱；
 * - 索引 docid/rowid == `lines.id`（FTS4 用 docid，FTS5 用 rowid，删除/插入语句区分）。
 */
internal object ConvertIndexDb {

    private const val DB_DIR = "module_search_index"
    private const val DB_NAME = "convert_index.db"
    /** app 转写正文目录名（NewConvertResultUtil.CONVERT_PATH_FLAG = "/convert"）。 */
    const val CONVERT_DIR = "convert"

    private const val MODE_FTS4_SPACED = 0
    private const val MODE_FTS5_TRIGRAM = 1

    /** 单条命中：一次录音里的一句转写正文。 */
    internal data class Hit(
        val recordId: Long,
        val startUs: Long,
        val endUs: Long,
        val roleName: String?,
        val text: String,
        val fileMtime: Long
    )

    /** files_meta 一行：增量对账的比对依据。 */
    internal data class FileMeta(
        val recordId: Long,
        val fileName: String,
        val fileSize: Long,
        val fileMtime: Long
    )

    internal data class ParsedLine(
        val startUs: Long,
        val endUs: Long,
        val text: String,
        val roleId: String?,
        val roleName: String?
    )

    private val lock = Any()

    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var mode: Int = MODE_FTS4_SPACED

    // ---------------------------------------------------------------- 打开/建表

    private fun open(context: Context): SQLiteDatabase {
        db?.let { return it }
        synchronized(lock) {
            db?.let { return it }
            val dir = File(context.filesDir, DB_DIR)
            if (!dir.exists()) dir.mkdirs()
            val d = SQLiteDatabase.openOrCreateDatabase(File(dir, DB_NAME), null)
            d.execSQL(
                "CREATE TABLE IF NOT EXISTS lines(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "record_id INTEGER NOT NULL, " +
                    "start_us INTEGER NOT NULL, " +
                    "end_us INTEGER NOT NULL, " +
                    "role_id TEXT, " +
                    "role_name TEXT, " +
                    "text TEXT NOT NULL)"
            )
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_lines_record ON lines(record_id)")
            d.execSQL(
                "CREATE TABLE IF NOT EXISTS files_meta(" +
                    "record_id INTEGER PRIMARY KEY, " +
                    "file_name TEXT NOT NULL, " +
                    "file_size INTEGER NOT NULL, " +
                    "file_mtime INTEGER NOT NULL, " +
                    "line_count INTEGER NOT NULL, " +
                    "indexed_at INTEGER NOT NULL)"
            )
            mode = detectOrCreateFtsMode(d)
            db = d
            XLog.i("index db open: mode=${if (mode == MODE_FTS5_TRIGRAM) "fts5-trigram" else "fts4-spaced"}")
            return d
        }
    }

    /**
     * 决定 FTS 模式：
     * - 表已存在 → 按建表 SQL 判断（历史库不能换模式，否则查询错乱）；
     * - 表不存在 → 探测 FTS5 trigram 是否可用，可用则建 FTS5，否则建 FTS4。
     */
    private fun detectOrCreateFtsMode(d: SQLiteDatabase): Int {
        val existing = d.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='lines_fts'", null
        )
        existing.use { c ->
            if (c.moveToFirst()) {
                val sql = c.getString(0) ?: ""
                return if (sql.contains("fts5") && sql.contains("trigram")) MODE_FTS5_TRIGRAM else MODE_FTS4_SPACED
            }
        }
        // 探测 FTS5 trigram
        val fts5Ok = try {
            d.execSQL("CREATE VIRTUAL TABLE temp.probe_fts5 USING fts5(x, tokenize='trigram')")
            d.execSQL("DROP TABLE IF EXISTS temp.probe_fts5")
            true
        } catch (t: Throwable) {
            XLog.d("fts5 trigram probe failed: ${t.message}")
            false
        }
        return if (fts5Ok) {
            d.execSQL("CREATE VIRTUAL TABLE lines_fts USING fts5(text, tokenize='trigram')")
            MODE_FTS5_TRIGRAM
        } else {
            d.execSQL("CREATE VIRTUAL TABLE lines_fts USING fts4(text_spaced TEXT, tokenize=unicode61)")
            MODE_FTS4_SPACED
        }
    }

    // ---------------------------------------------------------------- 索引写入

    /**
     * 全量索引/重建一个转写 txt（事务内：删旧行 + 插新行 + 更新元数据）。
     * 幂等，重复调用安全。@return 是否成功。
     */
    fun indexFile(context: Context, recordId: Long, file: File): Boolean {
        if (recordId <= 0L || !file.isFile) return false
        val parsed = parseFile(file)
        val d = open(context)
        d.beginTransaction()
        try {
            // 删旧行（先取旧 id，FTS 删除按 docid/rowid 逐条）
            val oldIds = ArrayList<Long>()
            d.rawQuery("SELECT id FROM lines WHERE record_id = ?", arrayOf(recordId.toString())).use { c ->
                while (c.moveToNext()) oldIds.add(c.getLong(0))
            }
            for (id in oldIds) {
                d.execSQL(ftsDeleteSql(), arrayOf<Any?>(id))
            }
            d.execSQL("DELETE FROM lines WHERE record_id = ?", arrayOf<Any?>(recordId))

            // 插新行
            for (pl in parsed) {
                val cv = android.content.ContentValues()
                cv.put("record_id", recordId)
                cv.put("start_us", pl.startUs)
                cv.put("end_us", pl.endUs)
                cv.put("role_id", pl.roleId)
                cv.put("role_name", pl.roleName)
                cv.put("text", pl.text)
                val rowId = d.insert("lines", null, cv)
                if (rowId > 0L) {
                    d.execSQL(ftsInsertSql(), arrayOf<Any?>(rowId, ftsStoredText(pl.text)))
                }
            }

            // 元数据
            val now = System.currentTimeMillis()
            d.execSQL(
                "INSERT OR REPLACE INTO files_meta(record_id, file_name, file_size, file_mtime, line_count, indexed_at) " +
                    "VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(recordId, file.name, file.length(), file.lastModified(), parsed.size, now)
            )
            d.setTransactionSuccessful()
            return true
        } finally {
            d.endTransaction()
        }
    }

    /** 删除一条录音的全部索引行（录音被删/对账发现文件缺失时）。 */
    fun deleteRecord(context: Context, recordId: Long) {
        val d = open(context)
        d.beginTransaction()
        try {
            val oldIds = ArrayList<Long>()
            d.rawQuery("SELECT id FROM lines WHERE record_id = ?", arrayOf(recordId.toString())).use { c ->
                while (c.moveToNext()) oldIds.add(c.getLong(0))
            }
            for (id in oldIds) d.execSQL(ftsDeleteSql(), arrayOf<Any?>(id))
            d.execSQL("DELETE FROM lines WHERE record_id = ?", arrayOf<Any?>(recordId))
            d.execSQL("DELETE FROM files_meta WHERE record_id = ?", arrayOf<Any?>(recordId))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** 全部索引元数据（对账用）。 */
    fun getFilesMeta(context: Context): Map<Long, FileMeta> {
        val d = open(context)
        val out = HashMap<Long, FileMeta>()
        d.rawQuery("SELECT record_id, file_name, file_size, file_mtime FROM files_meta", null).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = FileMeta(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 全文搜索转写正文。@param limit 原始命中行数上限（同一录音多行命中会占多个名额）。
     * FTS 查询失败（如纯符号关键字）自动回退 LIKE 全表扫。
     */
    fun search(context: Context, keyword: String, limit: Int): List<Hit> {
        val kw = keyword.filter { !it.isWhitespace() }.take(64)
        if (kw.isEmpty()) return emptyList()
        val d = open(context)
        return when (mode) {
            MODE_FTS5_TRIGRAM ->
                if (kw.length >= 3) searchFts(d, kw, limit) else searchLike(d, kw, limit)
            else -> // FTS4 逐字空格：任意长度短语都可用
                try {
                    searchFts(d, kw, limit)
                } catch (t: Throwable) {
                    XLog.d("fts query failed, fallback to like: ${t.message}")
                    searchLike(d, kw, limit)
                }
        }
    }

    private fun searchFts(d: SQLiteDatabase, kw: String, limit: Int): List<Hit> {
        val matchArg = if (mode == MODE_FTS5_TRIGRAM) {
            // trigram：短语查询即子串匹配
            "\"" + kw.filter { it != '"' } + "\""
        } else {
            // FTS4 逐字空格短语："你 好 世 界"
            "\"" + kw.filter { it != '"' }.toCharArray().joinToString(" ") + "\""
        }
        val out = ArrayList<Hit>()
        d.rawQuery(
            "SELECT l.record_id, l.start_us, l.end_us, l.role_name, l.text, COALESCE(fm.file_mtime, 0) " +
                "FROM lines_fts f " +
                "JOIN lines l ON l.id = f.${if (mode == MODE_FTS5_TRIGRAM) "rowid" else "docid"} " +
                "LEFT JOIN files_meta fm ON fm.record_id = l.record_id " +
                "WHERE lines_fts MATCH ? " +
                "ORDER BY l.record_id DESC, l.start_us LIMIT ?",
            arrayOf(matchArg, limit.toString())
        ).use { c ->
            fillHits(c, out)
        }
        return out
    }

    private fun searchLike(d: SQLiteDatabase, kw: String, limit: Int): List<Hit> {
        val escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val out = ArrayList<Hit>()
        d.rawQuery(
            "SELECT l.record_id, l.start_us, l.end_us, l.role_name, l.text, COALESCE(fm.file_mtime, 0) " +
                "FROM lines l LEFT JOIN files_meta fm ON fm.record_id = l.record_id " +
                "WHERE l.text LIKE ? ESCAPE '\\' " +
                "ORDER BY l.record_id DESC, l.start_us LIMIT ?",
            arrayOf("%$escaped%", limit.toString())
        ).use { c ->
            fillHits(c, out)
        }
        return out
    }

    private fun fillHits(c: android.database.Cursor, out: MutableList<Hit>) {
        while (c.moveToNext()) {
            out.add(
                Hit(
                    recordId = c.getLong(0),
                    startUs = c.getLong(1),
                    endUs = c.getLong(2),
                    roleName = if (c.isNull(3)) null else c.getString(3),
                    text = c.getString(4) ?: "",
                    fileMtime = c.getLong(5)
                )
            )
        }
    }

    // ---------------------------------------------------------------- 模式相关 SQL 片段

    private fun ftsDeleteSql(): String =
        if (mode == MODE_FTS5_TRIGRAM) "DELETE FROM lines_fts WHERE rowid = ?"
        else "DELETE FROM lines_fts WHERE docid = ?"

    private fun ftsInsertSql(): String =
        if (mode == MODE_FTS5_TRIGRAM) "INSERT INTO lines_fts(rowid, text) VALUES(?, ?)"
        else "INSERT INTO lines_fts(docid, text_spaced) VALUES(?, ?)"

    /** FTS 入库文本：FTS5 存原文；FTS4 存逐字空格化文本。 */
    private fun ftsStoredText(text: String): String =
        if (mode == MODE_FTS5_TRIGRAM) text else text.toCharArray().joinToString(" ") { it.toString() }

    // ---------------------------------------------------------------- txt 解析

    /**
     * 解析转写 txt。行格式（NewConvertResultUtil 口径）：
     * `开始us/结束us/文本[/roleId/角色名]/`，文本里的 '/' 已被 app 替换为空格。
     * 字段数 <3 或文本为空的行跳过。
     */
    private fun parseFile(file: File): List<ParsedLine> {
        val out = ArrayList<ParsedLine>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split('/')
            if (parts.size < 3) return@forEach
            val text = parts[2]
            if (text.isBlank()) return@forEach
            out.add(
                ParsedLine(
                    startUs = parts[0].toLongOrNull() ?: 0L,
                    endUs = parts[1].toLongOrNull() ?: 0L,
                    text = text,
                    roleId = if (parts.size > 5) parts[5] else null,
                    roleName = if (parts.size > 6) parts[6] else null
                )
            )
        }
        return out
    }
}
