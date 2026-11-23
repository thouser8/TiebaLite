package com.huanchengfly.tieba.post.ui.page.settings.block

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.ui.page.settings.components.settingsListContentPadding
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.ui.page.destinations.BlockListPageDestination
import com.huanchengfly.tieba.post.ui.page.settings.components.AppearanceCard
import com.huanchengfly.tieba.post.ui.page.settings.components.CardDivider
import com.huanchengfly.tieba.post.ui.page.settings.components.SectionLabel
import com.huanchengfly.tieba.post.ui.page.settings.components.SettingRow
import com.huanchengfly.tieba.post.ui.page.settings.components.SwitchSettingRow
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.BlockManager
import com.huanchengfly.tieba.post.utils.BlockRuleBackupUtil
import com.huanchengfly.tieba.post.utils.RestoreResult
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 屏蔽设置：卡片式分组（屏蔽列表入口 + 备份还原 + 内容过滤开关） */
@Destination
@Composable
fun BlockSettingsPage(
    navigator: DestinationsNavigator
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<RestoreResult?>(null) }
    val restoreDialogState = rememberDialogState()

    // 导出：让用户选择一个目标文件，直接写入 .tbrules（zip）内容
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val blocks = BlockManager.getAllBlocks()
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入所选文件")
                    stream.use { BlockRuleBackupUtil.backup(blocks, it) }
                }
            }
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "已备份 ${it.keywordCount} 条关键词、${it.userCount} 条用户规则" },
                    onFailure = { "备份失败：${it.message ?: "未知错误"}" }
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 导入：先解析成内存规则，确认后再覆盖，避免误触直接清空现有规则
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                    stream.use { BlockRuleBackupUtil.restore(it) }
                }
            }
            parsed.onSuccess {
                pendingRestore = it
                restoreDialogState.show()
            }.onFailure {
                Toast.makeText(
                    context,
                    "读取备份失败：${it.message ?: "文件格式不正确"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    ConfirmDialog(
        dialogState = restoreDialogState,
        title = { Text(text = stringResource(id = R.string.title_block_restore)) },
        onConfirm = {
            val result = pendingRestore ?: return@ConfirmDialog
            scope.launch {
                val done = runCatching {
                    withContext(Dispatchers.IO) { BlockManager.replaceAllBlocks(result.blocks) }
                }
                Toast.makeText(
                    context,
                    done.fold(
                        onSuccess = { saved ->
                            "已还原 ${saved.count { it.type == Block.TYPE_KEYWORD }} 条关键词、" +
                                    "${saved.count { it.type == Block.TYPE_USER }} 条用户规则"
                        },
                        onFailure = { "还原失败：${it.message ?: "未知错误"}" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        },
        onDismiss = { pendingRestore = null },
        onCancel = { pendingRestore = null },
    ) {
        pendingRestore?.let { result ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "将用备份中的 ${result.keywordCount} 条关键词、${result.userCount} 条用户规则" +
                            "覆盖当前全部屏蔽规则，此操作不可撤销。"
                )
                if (result.hasForumRules) {
                    Text(text = "备份中含吧规则，本版不支持吧屏蔽，该部分已忽略。")
                }
            }
        }
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(
                        text = stringResource(id = R.string.title_block_settings),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.h6
                    )
                },
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                }
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = settingsListContentPadding()
        ) {
            item {
                SectionLabel(
                    text = stringResource(id = R.string.title_block_settings),
                    icon = Icons.Outlined.Block
                )
            }
            item {
                AppearanceCard {
                    SettingRow(
                        icon = Icons.Outlined.Block,
                        title = stringResource(id = R.string.title_block_list),
                        onClick = { navigator.navigate(BlockListPageDestination) }
                    )
                    CardDivider()
                    SwitchSettingRow(
                        icon = Icons.Outlined.HideSource,
                        title = stringResource(id = R.string.settings_hide_blocked_content),
                        key = "hideBlockedContent",
                        defaultValue = false,
                    )
                    CardDivider()
                    SwitchSettingRow(
                        icon = Icons.Outlined.VideocamOff,
                        title = stringResource(id = R.string.settings_block_video),
                        summary = stringResource(id = R.string.settings_block_video_summary),
                        key = "blockVideo",
                        defaultValue = false,
                    )
                    CardDivider()
                    SwitchSettingRow(
                        icon = Icons.Outlined.Block,
                        title = stringResource(id = R.string.settings_show_followed_only),
                        summary = stringResource(id = R.string.settings_show_followed_only_summary),
                        key = "showFollowedOnly",
                        defaultValue = false,
                    )
                }
            }
            item {
                SectionLabel(
                    text = stringResource(id = R.string.title_block_backup_section),
                    icon = Icons.Outlined.Security
                )
            }
            item {
                AppearanceCard {
                    SettingRow(
                        icon = Icons.Outlined.Save,
                        title = stringResource(id = R.string.title_block_backup),
                        summary = stringResource(id = R.string.summary_block_backup),
                        onClick = {
                            exportLauncher.launch(BlockRuleBackupUtil.getBackupFileName())
                        }
                    )
                    CardDivider()
                    SettingRow(
                        icon = Icons.Outlined.Restore,
                        title = stringResource(id = R.string.title_block_restore),
                        summary = stringResource(id = R.string.summary_block_restore),
                        onClick = { importLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }
        }
    }
}
