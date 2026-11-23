package com.huanchengfly.tieba.post.models.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huanchengfly.tieba.post.fromJson

@Entity(tableName = "block")
data class Block @JvmOverloads constructor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: Int = 0,
    val type: Int = 0,
    val keywords: String? = null,
    val username: String? = null,
    val uid: String? = null,
    @ColumnInfo(name = "isregex") val isRegex: Boolean = false,
) {
    companion object {
        const val CATEGORY_BLACK_LIST = 10
        const val CATEGORY_WHITE_LIST = 11
        const val TYPE_KEYWORD = 0
        const val TYPE_USER = 1
        fun Block.getKeywords(): List<String> = keywords?.fromJson() ?: emptyList()

        /** 判断两条规则是否等价的键（备份导入去重用），忽略自增 id */
        fun Block.ruleKey(): String = when (type) {
            TYPE_USER -> "$category|u|${uid.orEmpty()}|${username.orEmpty()}"
            else -> "$category|k|$isRegex|${getKeywords().joinToString("\u0001")}"
        }
    }
}
