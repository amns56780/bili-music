package com.bilimusic.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bilimusic.app.domain.model.DuplicateNameChoice
import com.bilimusic.app.domain.model.PlaylistNameConflict

/** 二次确认对话框（删除等破坏性操作必须走它） */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/**
 * 文本输入对话框：用于新建歌单 / 重命名。
 * 输入为空时确认按钮禁用并给出提示，不会出现「点了没反应」。
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "确定",
    supportingText: String? = null,
    maxLength: Int = 60,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val isBlank = trimmed.isEmpty()
    val isTooLong = trimmed.length > maxLength

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= maxLength) value = it },
                label = { Text(label) },
                singleLine = true,
                isError = isTooLong,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                supportingText = {
                    when {
                        isBlank -> Text("不能为空")
                        isTooLong -> Text("最多 $maxLength 个字符")
                        supportingText != null -> Text(supportingText)
                        else -> Text("${trimmed.length} / $maxLength")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = !isBlank && !isTooLong,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 歌单重名对话框：覆盖 / 自动重命名（建议名）/ 取消。
 *
 * 新建、重命名、导入三条链路共用同一个组件，语义由 shared 的 PlaylistNaming 统一：
 * - 覆盖：删除同名旧歌单（连同其曲目），再用原名创建
 * - 自动重命名：保留旧歌单，新歌单用 suggestedTitle
 * - 取消：不写任何数据
 *
 * 对话框必须显示旧歌单的曲目数，避免用户误删不认识的歌单。
 */
@Composable
fun DuplicateNameDialog(
    conflict: PlaylistNameConflict,
    onChoose: (DuplicateNameChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌单重名") },
        text = {
            Column {
                Text(
                    text = "已存在同名歌单「${conflict.existingTitle}」，共 ${conflict.existingSongCount} 首曲目。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "覆盖：删除原歌单「${conflict.existingTitle}」及其 " +
                        "${conflict.existingSongCount} 首曲目，再用「${conflict.desiredTitle}」创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "自动重命名：保留原歌单，新歌单叫「${conflict.suggestedTitle}」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoose(DuplicateNameChoice.OVERWRITE) }) {
                    Text("覆盖", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { onChoose(DuplicateNameChoice.AUTO_RENAME) }) {
                    Text("自动重命名")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 纯信息对话框（例如展示一段长文本说明） */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    confirmLabel: String = "知道了",
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmLabel) }
        },
    )
}
