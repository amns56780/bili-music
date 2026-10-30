package com.bilimusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bilimusic.app.domain.model.PlayMode
import com.bilimusic.desktop.player.PlaybackController
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Headset
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmDimens
import com.bilimusic.desktop.ui.theme.BmType
import com.bilimusic.desktop.player.PlaybackUiState
import com.bilimusic.desktop.player.SleepTimerUiState
import kotlin.math.roundToInt

/**
 * 底部通栏播放条（对齐网易云：左歌信息 / 中控制 + 进度 / 右音量与队列）。
 *
 * 交互要点（交接文档坑 7）：进度条和音量条都只在拖动时更新本地 draft，
 * 松手才真正 seek / 下发 VLC，避免每帧跨语言调用积压导致「声音慢半拍」。
 */
@Composable
fun PlayerBar(
    state: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    sleepTimer: SleepTimerUiState,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
    onStartSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit,
    /** 点封面进入沉浸听歌模式 */
    onCoverClick: () -> Unit = {},
    /** 打开输出设备选择 */
    onOpenOutputDevice: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BmDimens.playerHeight)
            .background(BmColors.bgPlayer)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ---------------- 左：当前曲目 ----------------
        Row(modifier = Modifier.width(330.dp), verticalAlignment = Alignment.CenterVertically) {
            val current = state.current
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .clickable(enabled = current != null) { onCoverClick() }
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                BmCover(
                    cover = cover,
                    seed = current?.title ?: "♪",
                    size = BmDimens.coverPlayer,
                    radius = 9.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.title ?: "还没有在播放",
                    style = BmType.titleM,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = current?.artist ?: "选一个歌单，或粘贴 BV 号",
                    style = BmType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            BmIconButton(
                icon = Icons.Outlined.Download,
                contentDescription = "下载当前曲目",
                onClick = onDownload,
                enabled = current != null && !downloadBusy,
            )
        }

        // ---------------- 中：控制 + 进度 ----------------
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BmIconButton(
                    icon = modeIcon(state.mode),
                    contentDescription = "播放模式：" + state.mode.displayName,
                    onClick = controller::cycleMode,
                    iconSize = 17.dp,
                )
                BmIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    onClick = controller::previous,
                    iconSize = 20.dp,
                    tint = BmColors.text,
                )
                // 播放 / 暂停：品牌红实心圆
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(BmColors.brand)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { controller.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                BmIconButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    onClick = controller::next,
                    iconSize = 20.dp,
                    tint = BmColors.text,
                )
                // 占位保持 5 个控件等宽，视觉上跟网易云一致
                Spacer(Modifier.size(32.dp))
            }

            Spacer(Modifier.height(2.dp))

            // 进度条
            var dragging by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf(0f) }
            val ratio = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(if (dragging) (draft * state.durationMs).toLong() else state.positionMs),
                    style = BmType.number,
                    modifier = Modifier.width(38.dp),
                )
                BmSlider(
                    value = if (dragging) draft else ratio,
                    onChange = {
                        dragging = true
                        draft = it
                    },
                    onFinished = {
                        // 读 controller 的**当前**时长：拖动回调可能来自旧组合，
                        // 直接用捕获的 state 会拿到 durationMs=0 的初始快照
                        val duration = controller.state.value.durationMs
                        if (duration > 0) controller.seekTo((draft * duration).toLong())
                        dragging = false
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.durationMs > 0) formatTime(state.durationMs) else "--:--",
                    style = BmType.number,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End,
                )
            }
        }

        // ---------------- 右：音量 / 定时 / 队列 ----------------
        var wheelVolume by remember { mutableStateOf(state.volume) }
        LaunchedEffect(state.volume) { wheelVolume = state.volume }
        Row(
            modifier = Modifier
                // 容纳：音量组 + 设备按钮 + 定时 + 队列
                .width(350.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // 音量组：滚轮只绑在这一小块上（之前绑在整行，把右边按钮的点击全吃掉了）
            Row(
                modifier = Modifier
                    .volumeWheel { step ->
                        val v = (controller.state.value.volume + step).coerceIn(0, 100)
                        controller.setVolume(v, persist = true)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            var volumeDragging by remember { mutableStateOf(false) }
            var volumeDraft by remember { mutableStateOf(state.volume) }
            var lastVolumeSentAt by remember { mutableStateOf(0L) }
            LaunchedEffect(state.volume, volumeDragging) {
                if (!volumeDragging) volumeDraft = state.volume
            }
            Icon(
                imageVector = Icons.Outlined.VolumeUp,
                contentDescription = "音量",
                tint = BmColors.text2,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            BmSlider(
                value = volumeDraft / 100f,
                onChange = {
                    volumeDragging = true
                    volumeDraft = (it * 100).roundToInt()
                    // 拖动过程中节流下发（每 50ms 一次）：VLC 的音量命令是异步的，
                    // 只在松手时下发会有明显延迟感。这里实时下发但不写盘，松手再持久化。
                    val now = System.currentTimeMillis()
                    if (now - lastVolumeSentAt >= 50) {
                        lastVolumeSentAt = now
                        controller.setVolume(volumeDraft, persist = false)
                    }
                },
                onFinished = {
                    controller.setVolume(volumeDraft, persist = true)
                    volumeDragging = false
                },
                modifier = Modifier.width(110.dp),
                hoverBarColor = BmColors.text2,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (volumeDraft == 0) "静音" else volumeDraft.toString() + "%",
                style = BmType.number,
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.End,
            )
            } // ← 音量组结束（滚轮只作用于这一块）
            Spacer(Modifier.width(8.dp))

            // 输出设备
            BmIconButton(
                icon = Icons.Outlined.Headset,
                contentDescription = "输出设备",
                onClick = onOpenOutputDevice,
            )
            Spacer(Modifier.width(6.dp))

            // 定时关闭
            Box {
                var sleepMenu by remember { mutableStateOf(false) }
                BmIconButton(
                    icon = Icons.Outlined.Bedtime,
                    contentDescription = "定时关闭",
                    onClick = { sleepMenu = true },
                    tint = if (sleepTimer.running) {
                        BmColors.brand
                    } else {
                        BmColors.text2
                    },
                )
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(minutes.toString() + " 分钟后停止（本曲播完）") },
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

            // 队列弹层
            Box {
                var showQueue by remember { mutableStateOf(false) }
                BmIconButton(
                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = "播放队列",
                    onClick = { showQueue = !showQueue },
                )
                if (showQueue) {
                    val density = LocalDensity.current
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = with(density) { IntOffset(0, -(400.dp.roundToPx())) },
                        onDismissRequest = { showQueue = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        QueuePanel(
                            state = state,
                            onPick = { index ->
                                controller.playAt(index)
                                showQueue = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 队列弹层：当前曲高亮，点一首直接切过去 */
@Composable
private fun QueuePanel(state: PlaybackUiState, onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .height(390.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF202024)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播放队列",
                style = BmType.titleM,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = state.queue.size.toString() + " 首",
                style = BmType.label,
            )
        }
        if (state.queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("队列是空的", style = BmType.bodySm)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(state.queue.size) { index ->
                    val track = state.queue[index]
                    val current = index == state.index
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    current -> BmColors.brandSoft
                                    hovered -> BmColors.hover
                                    else -> Color.Transparent
                                },
                            )
                            .hoverable(interaction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onPick(index) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = BmType.number,
                            modifier = Modifier.width(24.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = BmType.body,
                                color = if (current) {
                                    BmColors.brand
                                } else {
                                    BmColors.text
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist,
                                style = BmType.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun modeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Outlined.PlaylistPlay
    PlayMode.REPEAT_ALL -> Icons.Outlined.Repeat
    PlayMode.REPEAT_ONE -> Icons.Outlined.RepeatOne
    PlayMode.SHUFFLE -> Icons.Outlined.Shuffle
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}


/** 输出设备列表项 */
@Composable
private fun OutputDeviceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BmType.body,
            color = if (selected) BmColors.brand else BmColors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(BmColors.brand))
        }
    }
}

