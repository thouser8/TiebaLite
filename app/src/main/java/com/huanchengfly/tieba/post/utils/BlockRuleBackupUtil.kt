package com.huanchengfly.tieba.post.utils

import com.google.gson.JsonObject
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.toJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 屏蔽规则备份 / 还原。
 *
 * 文件格式沿用 0ranko0P/TiebaLite 的 `.tbrules`（zip 容器），便于两边备份互相导入：
 * ```
 * <name>.tbrules
 *   ├── META-INF/metadata     JSON：version / timestamp / appVersion / 各类型规则数
 *   ├── keywords.csv          keyword,isRegex,whitelisted
 *   ├── users.csv             uid,name,whitelisted
 *   └── forum.txt             吧规则（本版无此功能，导出不写、导入忽略）
 * ```
 * CSV 布尔值用 0/1，行尾 LF。
 *
 * 与本版数据库的映射：本版 `block` 表是「一行可含多个关键词」的老结构，
 * 因此导出时把一行拆成每关键词一行（与新版一致），导入时每个关键词生成一行。
 */
object BlockRuleBackupUtil {

    const val ENTRY_METADATA = "META-INF/metadata"
    const val ENTRY_KEYWORDS = "keywords.csv"
    const val ENTRY_USERS = "users.csv"
    const val ENTRY_FORUM = "forum.txt"

    /** 备份文件扩展名 */
    const val EXTENSION = "tbrules"

    /** 备份格式版本，与 0ranko0P 的 version 1 对齐 */
    private const val FORMAT_VERSION = 1

    /** 默认备份文件名：TiebaLite_yy-MM-dd_HH-mm-ss.tbrules */
    fun getBackupFileName(date: Date = Date()): String =
        "TiebaLite_${SimpleDateFormat("yy-MM-dd_HH-mm-ss", Locale.ENGLISH).format(date)}.$EXTENSION"

    /**
     * 导出屏蔽规则到 [output]（zip 流），返回导出的规则条数。
     *
     * @throws IllegalStateException 没有任何可备份的规则
     */
    fun backup(blocks: List<Block>, output: OutputStream): BackupSummary {
        val keywordRows = ArrayList<KeywordRow>()
        blocks.filter { it.type == Block.TYPE_KEYWORD }.forEach { block ->
            val whitelisted = block.category == Block.CATEGORY_WHITE_LIST
            block.getKeywords().forEach { raw ->
                val keyword = raw.trim()
                if (keyword.isNotEmpty()) {
                    keywordRows.add(KeywordRow(keyword, block.isRegex, whitelisted))
                }
            }
        }
        val userRows = blocks.filter { it.type == Block.TYPE_USER }.mapNotNull { block ->
            val uid = block.uid?.trim().orEmpty()
            val name = block.username?.trim().orEmpty()
            if (uid.isEmpty() && name.isEmpty()) null
            else UserRow(uid, name, block.category == Block.CATEGORY_WHITE_LIST)
        }
        if (keywordRows.isEmpty() && userRows.isEmpty()) {
            throw IllegalStateException("没有可备份的屏蔽规则")
        }

        ZipOutputStream(output).use { zip ->
            zip.writeEntry(ENTRY_METADATA, buildMetadata(keywordRows.size, userRows.size))
            if (keywordRows.isNotEmpty()) {
                zip.writeEntry(ENTRY_KEYWORDS, buildKeywordCsv(keywordRows))
            }
            if (userRows.isNotEmpty()) {
                zip.writeEntry(ENTRY_USERS, buildUserCsv(userRows))
            }
            zip.finish()
        }
        return BackupSummary(keywordCount = keywordRows.size, userCount = userRows.size)
    }

    /**
     * 解析备份文件，返回待写库的规则（不落库，由调用方决定是否覆盖）。
     *
     * @throws IllegalStateException 文件里没有任何关键词/用户规则
     */
    fun restore(input: InputStream): RestoreResult {
        val blocks = mutableListOf<Block>()
        var hasForumRules = false
        var version: Int? = null

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY_METADATA -> version = runCatching {
                        GsonUtil.getGson().fromJson(zip.readEntryText(), JsonObject::class.java)
                            ?.get("version")?.asInt
                    }.getOrNull()
                    ENTRY_KEYWORDS -> blocks += parseKeywordRows(zip.readEntryText())
                    ENTRY_USERS -> blocks += parseUserRows(zip.readEntryText())
                    ENTRY_FORUM -> hasForumRules = zip.readEntryText().isNotBlank()
                }
                zip.closeEntry()
            }
        }

        val unique = blocks.distinctBy { block ->
            when (block.type) {
                Block.TYPE_USER -> "${block.category}|u|${block.uid.orEmpty()}|${block.username.orEmpty()}"
                else -> "${block.category}|k|${block.isRegex}|${block.getKeywords().joinToString("\u0001")}"
            }
        }
        if (unique.isEmpty()) {
            throw IllegalStateException("备份中没有可还原的屏蔽规则")
        }
        return RestoreResult(
            blocks = unique,
            keywordCount = unique.count { it.type == Block.TYPE_KEYWORD },
            userCount = unique.count { it.type == Block.TYPE_USER },
            hasForumRules = hasForumRules,
            version = version,
        )
    }

    /**
     * 读取任意受支持的规则文件：zip 容器（`.tbrules`）或 Oolong 导出的规则 JSON。
     * 按文件头（`PK` / `{` / `[`）自动判断，调用方无需让用户区分来源。
     *
     * @throws IllegalStateException 文件损坏或不是受支持的格式
     */
    fun readBackup(input: InputStream): BackupFile {
        val bytes = input.readAtMost(MAX_BACKUP_BYTES)
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return BackupFile.TbRules(restore(ByteArrayInputStream(bytes)))
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.isBlank()) throw IllegalStateException("文件为空")
        if (!OolongRuleImportUtil.looksLikeOolong(text)) {
            throw IllegalStateException("无法识别的文件格式，仅支持 .tbrules 备份或 Oolong 规则 JSON")
        }
        return BackupFile.Oolong(OolongRuleImportUtil.import(text))
    }

    /** 限制读入体积，避免误选大文件时把内存吃满 */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IllegalStateException("文件过大，不像是规则备份")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

    // ── 写 ──────────────────────────────────────────────────────

    private fun buildMetadata(keywordCount: Int, userCount: Int): String {
        val obj = JsonObject()
        obj.addProperty("version", FORMAT_VERSION)
        obj.addProperty("timestamp", System.currentTimeMillis())
        obj.addProperty("appVersion", BuildConfig.VERSION_NAME)
        obj.addProperty("keywordRuleCount", keywordCount)
        obj.addProperty("userRuleCount", userCount)
        // 本版没有吧规则，保持字段存在以便新版读取时不会缺项
        obj.addProperty("forumRuleCount", 0)
        return GsonUtil.getGson().toJson(obj)
    }

    private fun buildKeywordCsv(rows: List<KeywordRow>): String = buildString {
        append("keyword,isRegex,whitelisted\n")
        rows.forEach { row ->
            append(csvEscape(row.keyword))
            append(if (row.isRegex) ",1," else ",0,")
            append(if (row.whitelisted) "1\n" else "0\n")
        }
    }

    private fun buildUserCsv(rows: List<UserRow>): String = buildString {
        append("uid,name,whitelisted\n")
        rows.forEach { row ->
            append(csvEscape(row.uid))
            append(',')
            append(csvEscape(row.name))
            append(if (row.whitelisted) ",1\n" else ",0\n")
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readEntryText(): String =
        readBytes().toString(Charsets.UTF_8)

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍 */
    private fun csvEscape(value: String): String {
        val needQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needQuote) "\"$escaped\"" else escaped
    }

    // ── 读 ──────────────────────────────────────────────────────

    private fun parseKeywordRows(text: String): List<Block> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyList()
        val skipHeader = rows.first().firstOrNull()?.trim()?.equals("keyword", ignoreCase = true) == true
        return rows.drop(if (skipHeader) 1 else 0).mapNotNull { row ->
            val keyword = row.getOrNull(0)?.trim().orEmpty()
            if (keyword.isEmpty()) return@mapNotNull null
            Block(
                category = if (row.getOrNull(2).toBooleanBit()) Block.CATEGORY_WHITE_LIST
                else Block.CATEGORY_BLACK_LIST,
                type = Block.TYPE_KEYWORD,
                keywords = listOf(keyword).toJson(),
                isRegex = row.getOrNull(1).toBooleanBit(),
            )
        }
    }

    private fun parseUserRows(text: String): List<Block> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyList()
        val skipHeader = rows.first().firstOrNull()?.trim()?.equals("uid", ignoreCase = true) == true
        return rows.drop(if (skipHeader) 1 else 0).mapNotNull { row ->
            val uid = row.getOrNull(0)?.trim().orEmpty().ifEmpty { null }
            val name = row.getOrNull(1)?.trim().orEmpty().ifEmpty { null }
            if (uid == null && name == null) return@mapNotNull null
            Block(
                category = if (row.getOrNull(2).toBooleanBit()) Block.CATEGORY_WHITE_LIST
                else Block.CATEGORY_BLACK_LIST,
                type = Block.TYPE_USER,
                username = name,
                uid = uid,
            )
        }
    }

    private fun String?.toBooleanBit(): Boolean = when (this?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        else -> false
    }

    /**
     * 极简 CSV 解析：支持双引号包裹字段、`""` 转义引号、CRLF/LF 行尾。
     * 不引入额外依赖，够覆盖本格式。
     */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var rowHasContent = false

        fun flushField() {
            row.add(field.toString())
            field.setLength(0)
            rowHasContent = true
        }

        fun flushRow() {
            flushField()
            if (rowHasContent) rows.add(row)
            row = mutableListOf()
            rowHasContent = false
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> flushField()
                c == '\r' -> Unit
                c == '\n' -> flushRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) flushRow()
        // 丢掉全空行（Excel 另存常见的尾部空行）
        return rows.filter { cells -> cells.any { it.isNotBlank() } }
    }

    private data class KeywordRow(val keyword: String, val isRegex: Boolean, val whitelisted: Boolean)

    private data class UserRow(val uid: String, val name: String, val whitelisted: Boolean)
}

/** 备份结果统计 */
data class BackupSummary(val keywordCount: Int, val userCount: Int) {
    val total: Int get() = keywordCount + userCount
}

/** 还原解析结果（尚未写库） */
data class RestoreResult(
    val blocks: List<Block>,
    val keywordCount: Int,
    val userCount: Int,
    /** 备份里含吧规则（本版无吧屏蔽，无法还原） */
    val hasForumRules: Boolean,
    val version: Int?,
) {
    val total: Int get() = keywordCount + userCount
}

/** 自动识别后的备份内容 */
sealed interface BackupFile {
    /** `.tbrules`（zip 容器） */
    data class TbRules(val result: RestoreResult) : BackupFile

    /** Oolong 导出的规则 JSON */
    data class Oolong(val result: OolongImportResult) : BackupFile
}
