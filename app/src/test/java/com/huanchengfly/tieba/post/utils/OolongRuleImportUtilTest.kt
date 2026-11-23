package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.regex.Pattern

/**
 * Oolong 规则导入的回归测试：覆盖关键词（单/多词）、正则、用户、白名单、吧规则、
 * 停用规则、取反条件、v1 扁平备份与去重。
 */
class OolongRuleImportUtilTest {

    private val sample = """
        {
          "version": 2,
          "rules": [
            { "condition": { "type": "text", "pattern": { "type": "words", "words": ["广告"] } } },
            { "condition": { "type": "text", "pattern": { "type": "words", "words": ["代练", "私聊"] } } },
            { "condition": { "type": "text", "pattern": { "type": "regex", "pattern": "^a+b${'$'}" } } },
            { "condition": { "type": "user", "uid": 42, "name": "u42", "displayName": "昵称" } },
            { "condition": { "type": "user", "uid": 0, "name": "", "displayName": "只有昵称" } },
            { "action": "Allow",
              "condition": { "type": "text", "pattern": { "type": "words", "words": ["白名单词"] } } },
            { "condition": { "type": "forum", "name": "某吧" } },
            { "enabled": false,
              "condition": { "type": "text", "pattern": { "type": "words", "words": ["停用词"] } } },
            { "condition": { "type": "all", "conditions": [
                { "type": "forum", "name": "某吧" },
                { "type": "text", "pattern": { "type": "words", "words": ["吧内词"] } } ] } },
            { "condition": { "type": "not",
                "condition": { "type": "text", "pattern": { "type": "words", "words": ["取反词"] } } } },
            { "type": "keyword", "words": ["老版本"] },
            { "condition": { "type": "text", "pattern": { "type": "words", "words": ["蝌蚪"] } } },
            { "condition": { "type": "text", "pattern": { "type": "words", "words": ["蝌蚪"] } } }
          ]
        }
    """.trimIndent()

    @Test
    fun parseMapsSupportedRules() {
        val result = OolongRuleImportUtil.parse(sample)

        // 广告、代练+私聊、正则、白名单词、吧内词、老版本、蝌蚪（重复 1 条被合并）
        assertEquals(7, result.keywordCount)
        assertEquals(2, result.userCount)
        assertEquals(9, result.total)
        assertEquals(13, result.totalRules)
        // 吧规则、停用规则、取反规则
        assertEquals(3, result.skippedRules)
        assertEquals(2, result.version)
        assertTrue(result.notes.any { it.contains("本版没有吧屏蔽") })
    }

    @Test
    fun multiWordRuleBecomesAndRegex() {
        val andRule = OolongRuleImportUtil.parse(sample).blocks.first { block ->
            block.type == Block.TYPE_KEYWORD && block.isRegex &&
                    block.getKeywords().any { it.contains("(?=.*") }
        }
        val pattern = Pattern.compile(andRule.getKeywords().single())

        assertTrue(pattern.matcher("今天代练 报价，私聊我").find())
        assertFalse(pattern.matcher("今天只有代练").find())
        assertFalse(pattern.matcher("只提私聊").find())
    }

    @Test
    fun allowRuleGoesToWhitelist() {
        val white = OolongRuleImportUtil.parse(sample).blocks.single {
            it.category == Block.CATEGORY_WHITE_LIST
        }
        assertEquals("白名单词", white.getKeywords().single())
        assertFalse(white.isRegex)
    }

    @Test
    fun userRulesPreferUid() {
        val blocks = OolongRuleImportUtil.parse(sample).blocks
        val byUid = blocks.single { it.type == Block.TYPE_USER && it.uid == "42" }
        assertEquals("u42", byUid.username)

        val byName = blocks.single { it.type == Block.TYPE_USER && it.uid == null }
        assertEquals("只有昵称", byName.username)
    }

    @Test
    fun regexRuleIsCaseInsensitive() {
        val regexRule = OolongRuleImportUtil.parse(sample).blocks.single {
            it.type == Block.TYPE_KEYWORD && it.getKeywords().single().contains("^a+b")
        }
        assertTrue(regexRule.getKeywords().single().startsWith("(?i)"))
        assertTrue(Pattern.compile(regexRule.getKeywords().single()).matcher("AAAB").find())
    }

    @Test
    fun backupEntryDetectsOolongJson() {
        val file = BlockRuleBackupUtil.readBackup(
            ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8))
        )
        assertTrue(file is BackupFile.Oolong)
        assertEquals(9, (file as BackupFile.Oolong).result.total)
    }

    @Test
    fun emptyRulesAreRejected() {
        val error = runCatching { OolongRuleImportUtil.parse("""{"version":2,"rules":[]}""") }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull() is IllegalStateException)
    }
}
