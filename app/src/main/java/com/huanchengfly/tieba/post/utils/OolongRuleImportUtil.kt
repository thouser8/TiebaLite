package com.huanchengfly.tieba.post.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.ruleKey
import com.huanchengfly.tieba.post.toJson
import java.util.regex.Pattern

/**
 * 读取 HuanCheng65/Oolong 导出的屏蔽规则（`oolong-block-rules.json`）。
 *
 * 文件结构（Oolong 备份 v2）：
 * ```
 * { "version": 2,
 *   "rules": [
 *     { "condition": { "type": "text", "pattern": { "type": "words", "words": ["a","b"] } },
 *       "action": "Default", "enabled": true, "scope": "All" },
 *     { "condition": { "type": "user", "uid": 123, "name": "abc", "displayName": "昵称" } }
 *   ] }
 * ```
 * v1 备份没有 `condition`，改用扁平的 `type`（keyword / user / forum / level）+ `words` / `regex` / `uid` 等字段，
 * 这里一并兼容。
 *
 * 本版数据库只有「关键词（普通或正则）」和「用户（uid 或昵称）」两类规则，因此：
 * - `text` + `words` 单词 → 普通关键词；多词（且关系）→ 拆成 `(?=.*a)(?=.*b)` 正则，保持「同时出现」的语义
 * - `text` + `regex` → 正则关键词，并补 `(?i)`（Oolong 的正则默认忽略大小写）
 * - `user` → uid 优先，无 uid 时退回昵称
 * - `action: Allow` → 白名单，其余 → 黑名单
 * - `any`（或）→ 每个分支各导入一条，语义等价；`all`（且）只有单个可表达条件时才导入
 * - 吧规则、等级、IP、内容类型、取反等本版无法表达的规则会被跳过，并计入 [OolongImportResult.notes]
 */
object OolongRuleImportUtil {

    /** 规则文件里至少要有这些字段之一才认为是 Oolong 备份 */
    fun looksLikeOolong(text: String): Boolean {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        return trimmed.contains("\"condition\"") || trimmed.contains("\"rules\"")
    }

    /**
     * 解析 Oolong 规则文本，返回待写库的规则（不落库）。
     *
     * @throws IllegalStateException 文件结构不是 Oolong 备份
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): OolongImportResult {
        val root = GsonUtil.getGson().fromJson(text, JsonElement::class.java)
            ?: throw IllegalStateException("不是有效的 JSON 文件")

        val version: Int?
        val rules: JsonArray
        when {
            root.isJsonArray -> {
                version = null
                rules = root.asJsonArray
            }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                version = obj.get("version")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asInt }.getOrNull() }
                rules = obj.array("rules")
                    ?: throw IllegalStateException("不是 Oolong 规则文件：缺少 rules")
            }
            else -> throw IllegalStateException("不是 Oolong 规则文件")
        }
        if (rules.size() == 0) throw IllegalStateException("规则文件里没有规则")

        val blocks = ArrayList<Block>()
        val skippedReasons = LinkedHashMap<String, Int>()
        var skipped = 0

        fun skip(reason: String) {
            skipped++
            skippedReasons[reason] = (skippedReasons[reason] ?: 0) + 1
        }

        for (element in rules) {
            if (!element.isJsonObject) {
                skip("无法识别的规则")
                continue
            }
            val entry = element.asJsonObject
            if (!entry.bool("enabled", default = true)) {
                skip("已停用的规则")
                continue
            }
            val expiresAt = entry.long("expiresAt", default = 0L)
            if (expiresAt > 0L && expiresAt <= now) {
                skip("已过期的规则")
                continue
            }

            val condition = entry.obj("condition") ?: entry.legacyCondition()
            if (condition == null) {
                skip("规则缺少条件")
                continue
            }

            val notes = LinkedHashSet<String>()
            val converted = convert(condition, notes)
            notes.forEach { note -> skippedReasons.note(note) }

            if (converted is Converted.Unsupported) {
                skip(converted.reason)
                continue
            }
            val ok = converted as Converted.Ok

            val category = if (entry.str("action").equals("Allow", ignoreCase = true)) {
                Block.CATEGORY_WHITE_LIST
            } else {
                Block.CATEGORY_BLACK_LIST
            }
            ok.keywords.forEach { keyword ->
                blocks.add(
                    Block(
                        category = category,
                        type = Block.TYPE_KEYWORD,
                        keywords = listOf(keyword.text).toJson(),
                        isRegex = keyword.isRegex,
                    )
                )
            }
            ok.users.forEach { user ->
                blocks.add(
                    Block(
                        category = category,
                        type = Block.TYPE_USER,
                        username = user.name.ifEmpty { null },
                        uid = user.uid.takeIf { it > 0L }?.toString(),
                    )
                )
            }
            if (ok.keywords.isEmpty() && ok.users.isEmpty()) {
                skip("规则没有可导入的内容")
            }
        }

        val unique = blocks.distinctBy { it.ruleKey() }
        if (unique.isEmpty()) {
            throw IllegalStateException(
                skippedReasons.maxByOrNull { it.value }?.key ?: "没有可导入的规则"
            )
        }

        return OolongImportResult(
            blocks = unique,
            keywordCount = unique.count { it.type == Block.TYPE_KEYWORD },
            userCount = unique.count { it.type == Block.TYPE_USER },
            totalRules = rules.size(),
            skippedRules = skipped,
            notes = skippedReasons.map { (reason, count) ->
                if (count > 0) "「$reason」$count 条" else reason
            },
            version = version,
        )
    }

    // ── 条件转换 ────────────────────────────────────────────────

    private sealed interface Converted {
        data class Ok(val keywords: List<Keyword>, val users: List<User>) : Converted
        data class Unsupported(val reason: String) : Converted
    }

    private data class Keyword(val text: String, val isRegex: Boolean)

    private data class User(val uid: Long, val name: String)

    private fun convert(condition: JsonObject, notes: MutableSet<String>): Converted =
        when (val type = condition.str("type")) {
            "text" -> convertText(condition, notes)
            "user" -> convertUser(condition)
            "all" -> convertAll(condition, notes)
            "any" -> convertAny(condition, notes)
            "not" -> Converted.Unsupported("本版不支持取反条件")
            "forum" -> Converted.Unsupported("本版没有吧屏蔽")
            "forum_name" -> Converted.Unsupported("本版不支持按吧名匹配")
            "author_name" -> Converted.Unsupported("本版不支持按作者昵称屏蔽")
            "level" -> Converted.Unsupported("本版不支持按等级屏蔽")
            "thread_author" -> Converted.Unsupported("本版不支持「只看楼主」条件")
            "ip" -> Converted.Unsupported("本版不支持按 IP 属地屏蔽")
            "has" -> Converted.Unsupported("本版不支持按内容类型屏蔽")
            "only" -> Converted.Unsupported("本版不支持按纯内容类型屏蔽")
            "shorter" -> Converted.Unsupported("本版不支持按字数屏蔽")
            else -> Converted.Unsupported("未知的条件类型「$type」")
        }

    private fun convertText(condition: JsonObject, notes: MutableSet<String>): Converted {
        val pattern = condition.obj("pattern")
            ?: return Converted.Unsupported("文本条件缺少匹配内容")
        when (condition.str("field")) {
            "Title", "Body" -> notes.add("仅标题 / 仅正文的限定已放宽为全文匹配")
        }
        return when (pattern.str("type")) {
            "words" -> {
                val words = pattern.array("words")?.stringValues()?.distinct().orEmpty()
                when {
                    words.isEmpty() -> Converted.Unsupported("关键词为空")
                    words.size == 1 -> Converted.Ok(listOf(Keyword(words.single(), false)), emptyList())
                    else -> {
                        notes.add("多关键词「同时出现」已转为正则匹配")
                        Converted.Ok(listOf(Keyword(andRegex(words), true)), emptyList())
                    }
                }
            }
            "regex" -> {
                val raw = pattern.str("pattern")
                if (raw.isEmpty()) {
                    Converted.Unsupported("正则为空")
                } else {
                    // Oolong 的正则默认忽略大小写，本版按 Java 默认大小写敏感，补上 (?i)
                    val regex = if (raw.startsWith("(?i)")) raw else "(?i)$raw"
                    Converted.Ok(listOf(Keyword(regex, true)), emptyList())
                }
            }
            else -> Converted.Unsupported("未知的文本匹配类型")
        }
    }

    private fun convertUser(condition: JsonObject): Converted {
        val uid = condition.long("uid")
        val name = condition.str("name")
        val displayName = condition.str("displayName")
        if (uid <= 0L && name.isEmpty() && displayName.isEmpty()) {
            return Converted.Unsupported("用户规则没有可用 uid 或昵称")
        }
        // 有 uid 时只按 uid 匹配，避免展示名重名误伤
        val matchName = if (uid > 0L) name else name.ifEmpty { displayName }
        return Converted.Ok(emptyList(), listOf(User(uid, matchName)))
    }

    private fun convertAll(condition: JsonObject, notes: MutableSet<String>): Converted {
        val children = condition.array("conditions")
            ?: return Converted.Unsupported("「且」条件为空")
        val ok = ArrayList<Converted.Ok>()
        var ignoredForum = false
        val failed = ArrayList<String>()
        for (child in children) {
            val converted = if (child.isJsonObject) {
                convert(child.asJsonObject, notes)
            } else {
                Converted.Unsupported("无法识别的条件")
            }
            when (converted) {
                is Converted.Ok -> ok.add(converted)
                is Converted.Unsupported -> {
                    // 吧限定丢掉只会放宽到全吧，其他条件丢掉会改变语义，因此单独处理
                    if (child.isJsonObject && child.asJsonObject.str("type") == "forum") {
                        ignoredForum = true
                    } else {
                        failed.add(converted.reason)
                    }
                }
            }
        }
        return when {
            ok.isEmpty() -> Converted.Unsupported(failed.firstOrNull() ?: "没有可导入的条件")
            ok.size > 1 -> Converted.Unsupported("多条件「且」关系无法表达")
            failed.isNotEmpty() -> Converted.Unsupported(failed.first())
            else -> {
                if (ignoredForum) notes.add("吧限定条件已忽略（本版无吧屏蔽）")
                ok.single()
            }
        }
    }

    private fun convertAny(condition: JsonObject, notes: MutableSet<String>): Converted {
        val children = condition.array("conditions")
            ?: return Converted.Unsupported("「或」条件为空")
        val keywords = ArrayList<Keyword>()
        val users = ArrayList<User>()
        val failed = ArrayList<String>()
        for (child in children) {
            val converted = if (child.isJsonObject) {
                convert(child.asJsonObject, notes)
            } else {
                Converted.Unsupported("无法识别的条件")
            }
            when (converted) {
                is Converted.Ok -> {
                    keywords.addAll(converted.keywords)
                    users.addAll(converted.users)
                }
                is Converted.Unsupported -> failed.add(converted.reason)
            }
        }
        if (keywords.isEmpty() && users.isEmpty()) {
            return Converted.Unsupported(failed.firstOrNull() ?: "没有可导入的条件")
        }
        if (failed.isNotEmpty()) notes.add("部分「或」分支无法导入，已跳过")
        return Converted.Ok(keywords, users)
    }

    /** 多关键词的「且」关系 → 一条正则（`find()` 在起点即可判定） */
    private fun andRegex(words: List<String>): String = buildString {
        append("(?s)")
        words.forEach { word -> append("(?=.*").append(Pattern.quote(word)).append(")") }
    }

    // ── v1 备份兼容 ─────────────────────────────────────────────

    /** 老版本备份没有 `condition`，把扁平字段折成等价的条件对象 */
    private fun JsonObject.legacyCondition(): JsonObject? {
        val match = when (str("type")) {
            "keyword" -> {
                val words = array("words")?.stringValues().orEmpty()
                if (words.isEmpty()) return null
                val titleOnly = bool("titleOnly", default = false)
                if (bool("regex", default = false)) {
                    textCondition(regex = words.first(), titleOnly = titleOnly)
                } else {
                    textCondition(words = words, titleOnly = titleOnly)
                }
            }
            "user" -> JsonObject().apply {
                addProperty("type", "user")
                addProperty("uid", long("uid"))
                addProperty("name", str("name"))
                addProperty("displayName", str("displayName"))
            }
            "forum" -> JsonObject().apply {
                addProperty("type", "forum")
                addProperty("name", str("name"))
            }
            else -> return null
        }
        val inForum = str("inForum")
        if (inForum.isEmpty()) return match
        return JsonObject().apply {
            addProperty("type", "all")
            add("conditions", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "forum")
                    addProperty("name", inForum)
                })
                add(match)
            })
        }
    }

    private fun textCondition(
        words: List<String> = emptyList(),
        regex: String? = null,
        titleOnly: Boolean = false,
    ): JsonObject = JsonObject().apply {
        addProperty("type", "text")
        if (titleOnly) addProperty("field", "Title")
        add("pattern", JsonObject().apply {
            if (regex != null) {
                addProperty("type", "regex")
                addProperty("pattern", regex)
            } else {
                addProperty("type", "words")
                add("words", JsonArray().apply { words.forEach { add(it) } })
            }
        })
    }

    // ── JSON 小工具 ─────────────────────────────────────────────

    private fun JsonObject.str(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            ?.trim().orEmpty()

    private fun JsonObject.bool(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asBoolean }.getOrDefault(default) } ?: default

    private fun JsonObject.long(name: String, default: Long = 0L): Long =
        get(name)?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrDefault(default) } ?: default

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.stringValues(): List<String> {
        val result = ArrayList<String>(size())
        for (element in this) {
            if (!element.isJsonPrimitive) continue
            val value = runCatching { element.asString }.getOrNull()?.trim().orEmpty()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }

    /** 附加在「被跳过原因」里的提示（不计入跳过条数，仅说明语义变化） */
    private fun MutableMap<String, Int>.note(note: String) {
        if (!containsKey(note)) put(note, 0)
    }
}

/** Oolong 规则导入结果 */
data class OolongImportResult(
    val blocks: List<Block>,
    val keywordCount: Int,
    val userCount: Int,
    val totalRules: Int,
    val skippedRules: Int,
    /** 跳过原因 / 语义变化提示，已带条数，可直接展示 */
    val notes: List<String>,
    val version: Int?,
) {
    val total: Int get() = keywordCount + userCount
}
