package com.bilimusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.PlaybackUiState
import com.bilimusic.desktop.player.SleepTimerUiState

/** 秒 → m:ss */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 播放器面板：封面 / 标题 / 进度 / 控制 / 音量 / 播放模式。
 */
@Composable
fun PlayerPanel(
    state: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    sleepTimer: SleepTimerUiState,
    onStartSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 封面
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(96.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (cover != null) {
                            Image(
                                bitmap = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                            )
                        } else {
                            Text("♪", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.current?.title ?: "还没有在播放",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.current?.artist ?: "从左侧粘贴 BV 号或链接开始播放",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.qualityLabel.isNotBlank()) {
                            Chip(state.qualityLabel)
                        }
                        Chip(state.mode.displayName)
                        if (sleepTimer.running) {
                            Chip("定时 ${sleepTimer.remainingText}")
                        }
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 进度
            Slider(
                value = if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = { fraction ->
                    if (state.durationMs > 0) controller.seekTo((fraction * state.durationMs).toLong())
                },
                enabled = state.durationMs > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // 播放模式
                IconButton(onClick = controller::cycleMode) {
                    Icon(
                        imageVector = when (state.mode) {
                            PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Outlined.PlaylistPlay
                            PlayMode.REPEAT_ALL -> Icons.Outlined.Repeat
                            PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
                        },
                        contentDescription = "播放模式：${state.mode.displayName}",
                    )
                }

                IconButton(onClick = controller::previous, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(28.dp))
                }

                FilledIconButton(
                    onClick = controller::togglePlayPause,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                        modifier = Modifier.size(30.dp),
                    )
                }

                IconButton(onClick = controller::next, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(28.dp))
                }

                // 下载当前这首（离线缓存）
                IconButton(onClick = onDownload, enabled = state.current != null && !downloadBusy) {
                    if (downloadBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "下载到本地",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.width(24.dp))

                // 定时关闭：可选分钟数，或取消正在倒计时的定时
                var sleepMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sleepMenu = true }) {
                        Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sleepTimer.running) sleepTimer.remainingText else "定时")
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15, 30, 60, 90).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes 分钟后停止（本曲播完）") },
                                onClick = {
                                    onStartSleep(minutes)
                                    sleepMenu = false
                                },
                            )
                        }
                        if (sleepTimer.running) {
                            DropdownMenuItem(
                                text = { Text("取消定时") },
                                onClick = {
                                    onCancelSleep()
                                    sleepMenu = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text("音量", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                // 宽度必须留出右侧数字的位置：之前 140dp 把数字挤出了卡片右边缘（被裁掉）
                // 拖动时只更新本地值（视觉即时），松手才真正下发到 VLC：
                // 每一帧都调 player.audio().setVolume() 会让 JNI 调用积压，声音要过一会才变。
                var volumeDraft by remember { mutableStateOf(state.volume) }
                Slider(
                    value = volumeDraft.toFloat(),
                    onValueChange = { volumeDraft = it.toInt() },
                    onValueChangeFinished = { controller.setVolume(volumeDraft) },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(88.dp),
                )
                Spacer(Modifier.width(6.dp))
                // 固定宽度 + 不换行：避免 0→100 变化时宽度跳动
                Text(
                    text = "${volumeDraft}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(30.dp),
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 队列列表（当前曲高亮） */
@Composable
fun QueueList(state: PlaybackUiState, onPick: (Int) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = "播放队列（${state.queue.size}）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.queue.isEmpty()) {
                Text(
                    text = "队列是空的",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.queue.forEachIndexed { index, track ->
                val selected = index == state.index
                Surface(
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = MaterialTheme.shapes.small,
                    onClick = { onPick(index) },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = "${index + 1}. ${track.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
