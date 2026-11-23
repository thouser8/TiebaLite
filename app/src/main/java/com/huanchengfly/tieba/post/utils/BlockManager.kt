package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.MessageListBean
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.api.models.protos.plainText
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Block.Companion.getKeywords
import com.huanchengfly.tieba.post.models.database.Block.Companion.ruleKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.regex.Pattern

object BlockManager {
    private val blockList: MutableList<Block> = mutableListOf()

    val blackList: List<Block>
        get() = blockList.filter { it.category == Block.CATEGORY_BLACK_LIST }

    val whiteList: List<Block>
        get() = blockList.filter { it.category == Block.CATEGORY_WHITE_LIST }

    suspend fun addBlock(block: Block): Block {
        val id = DatabaseUtil.insertBlock(block)
        val savedBlock = block.copy(id = id)
        blockList.add(savedBlock)
        return savedBlock
    }

    fun addBlockAsync(
        block: Block,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            val id = DatabaseUtil.insertBlock(block)
            val savedBlock = block.copy(id = id)
            blockList.add(savedBlock)
            callback?.invoke(true)
        }
    }

    suspend fun removeBlock(id: Long) {
        DatabaseUtil.deleteBlockById(id)
        blockList.removeAll { it.id == id }
    }

    /** 当前全部规则（备份导出用，按库内容为准） */
    suspend fun getAllBlocks(): List<Block> = DatabaseUtil.getAllBlocks()

    /**
     * 用 [blocks] 覆盖全部规则（备份还原用），并同步内存缓存。
     * 返回写库后的实际数据（含新的自增 id）。
     */
    suspend fun replaceAllBlocks(blocks: List<Block>): List<Block> {
        val saved = DatabaseUtil.replaceAllBlocks(blocks)
        blockList.clear()
        blockList.addAll(saved)
        return saved
    }

    /**
     * 把 [blocks] 合并进现有规则（Oolong 规则导入用），重复的规则跳过。
     * 返回实际新增的条数与跳过的重复条数。
     */
    suspend fun mergeBlocks(blocks: List<Block>): MergeSummary {
        val existing = DatabaseUtil.getAllBlocks()
        val keys = existing.mapTo(HashSet()) { it.ruleKey() }
        val added = blocks.filter { keys.add(it.ruleKey()) }
        if (added.isNotEmpty()) {
            DatabaseUtil.insertBlocks(added)
        }
        reload()
        return MergeSummary(
            keywordCount = added.count { it.type == Block.TYPE_KEYWORD },
            userCount = added.count { it.type == Block.TYPE_USER },
            skippedCount = blocks.size - added.size,
        )
    }

    /** 从数据库重新载入缓存（多端写入后同步用） */
    suspend fun reload() {
        val all = DatabaseUtil.getAllBlocks()
        blockList.clear()
        blockList.addAll(all)
    }

    suspend fun init() {
        blockList.addAll(DatabaseUtil.getAllBlocks())
    }

    fun shouldBlock(content: String): Boolean {
        val isWhite = whiteList.any { block ->
            block.type == Block.TYPE_KEYWORD && block.getKeywords().any { keyword ->
                if (block.isRegex) {
                    try {
                        Pattern.compile(keyword).matcher(content).find()
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    content.contains(keyword)
                }
            }
        }
        if (isWhite)
            return false
        val isBlack = blackList.any { block ->
            block.type == Block.TYPE_KEYWORD && block.getKeywords().any { keyword ->
                if (block.isRegex) {
                    try {
                        Pattern.compile(keyword).matcher(content).find()
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    content.contains(keyword)
                }
            }
        }
        return isBlack
    }

    fun shouldBlock(userId: Long = 0L, userName: String? = null): Boolean {
        val isWhite = whiteList.any { block ->
            !block.isRegex &&
                    block.type == Block.TYPE_USER &&
                    (block.uid == userId.toString() || block.username == userName)
        }
        if (isWhite) return false

        val isBlack = blackList.any { block ->
            !block.isRegex &&
                    block.type == Block.TYPE_USER &&
                    (block.uid == userId.toString() || block.username == userName)
        }
        return isBlack
    }

    fun ThreadInfo.shouldBlock(): Boolean =
        shouldBlock(title) || shouldBlock(abstractText) || shouldBlock(
            authorId.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author.nameShow })

    fun Post.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author.nameShow })

    fun SubPostList.shouldBlock(): Boolean =
        shouldBlock(content.plainText) || shouldBlock(
            author_id.takeIf { it != 0L } ?: (author?.id ?: -1),
            author?.name?.ifEmpty { author.nameShow })

    fun MessageListBean.MessageInfoBean.shouldBlock(): Boolean =
        shouldBlock(content.orEmpty()) || shouldBlock(
            this.replyer?.id?.toLongOrNull() ?: -1,
            this.replyer?.name?.ifEmpty { this.replyer.nameShow }
        )
}

/** 合并导入的统计 */
data class MergeSummary(
    val keywordCount: Int,
    val userCount: Int,
    /** 与现有规则重复、被跳过的条数 */
    val skippedCount: Int,
) {
    val total: Int get() = keywordCount + userCount
}
