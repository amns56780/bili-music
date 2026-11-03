package com.bilimusic.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNameConflict
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmType

/**
 * 「歌单重名」对话框：覆盖 / 自动重命名 / 取消。
 *
 * 新建歌单、重命名歌单、导入歌单三处共用；文案差异只通过 [overwriteHint] 传，
 * 样式跟 Sidebar.kt 里的 [BmDialog] 保持一致（同一个深色弹窗 + 文字按钮）。
 *
 * 语义由共享层 [com.bilimusic.app.domain.model.PlaylistNaming.resolveConflict] 决定：
 * - 覆盖：删除已有同名歌单（连同曲目），再用原名新建 / 写入
 * - 自动重命名：用 [PlaylistNameConflict.suggestedTitle] 建新歌单，不动旧歌单
 * - 取消：本次操作不写任何数据（点遮罩 / Esc 也算取消）
 */
@Composable
fun DuplicatePlaylistNameDialog(
    conflict: PlaylistNameConflict,
    onChoice: (DuplicateNameChoice) -> Unit,
    /** 覆盖行为的补充说明（新建 / 重命名 / 导入的后果不一样） */
    overwriteHint: String = "覆盖会删除它并重新创建「" + conflict.desiredTitle + "」",
) {
    AlertDialog(
        onDismissRequest = { onChoice(DuplicateNameChoice.CANCEL) },
        containerColor = BmColors.dialog,
        titleContentColor = BmColors.text,
        textContentColor = BmColors.text2,
        title = { Text("歌单重名", style = BmType.titleL) },
        text = {
            Column {
                Text(
                    text = "已存在同名歌单「" + conflict.existingTitle + "」（" +
                        conflict.existingSongCount + " 首）。",
                    style = BmType.bodySm,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = overwriteHint + "；也可以自动重命名为「" +
                        conflict.suggestedTitle + "」，旧歌单不受影响。",
                    style = BmType.bodySm,
                )
            }
        },
        // AlertDialog 只有 confirm/dismiss 两个槽位，这里三个按钮都放 confirmButton 里，
        // 顺序按产品要求：覆盖 → 自动重命名 → 取消。
        confirmButton = {
            Row {
                TextButton(onClick = { onChoice(DuplicateNameChoice.OVERWRITE) }) {
                    Text(
                        text = "覆盖",
                        style = BmType.body,
                        // 品牌红 = 这套深色主题里的警示色，和「删除歌单」等危险操作一致
                        color = BmColors.brand,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(
                    onClick = { onChoice(DuplicateNameChoice.AUTO_RENAME) },
                    modifier = Modifier.widthIn(max = 260.dp),
                ) {
                    Text(
                        text = "自动重命名「" + conflict.suggestedTitle + "」",
                        style = BmType.body,
                        color = BmColors.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onChoice(DuplicateNameChoice.CANCEL) }) {
                    Text("取消", style = BmType.body, color = BmColors.text2)
                }
            }
        },
    )
}
