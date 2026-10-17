package com.bilimusic.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilimusic.desktop.data.StoredPlaylist
import com.bilimusic.desktop.data.StoredSong
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmDimens
import com.bilimusic.desktop.ui.theme.BmType
import java.time.Instant
import java.time.ZoneId

/**
 * 歌单详情页：大封面 header + 歌曲表格。
 *
 * 版式对齐网易云歌单页：顶部 hero 区（封面 / 标题 / 元信息 / 操作按钮），
 * 下面是带表头的歌曲列表，当前播放行用品牌红高亮 + 跳动音柱。
 */
@Composable
fun PlaylistPage(
    playlist: StoredPlaylist?,
    songs: List<StoredSong>,
    playlists: List<StoredPlaylist>,
    isCurrent: (StoredSong) -> Boolean,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onCopyTo: (Long, Set<Long>) -> Unit,
    onMoveTo: (Long, Set<Long>) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSetCoverUrl: (Long, String?) -> Unit,
    onSetCoverFile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlist == null) {
        EmptyState(modifier = modifier)
        return
    }
    val pl = playlist

    var selectionMode by remember(pl.id) { mutableStateOf(false) }
    var selected by remember(pl.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var moreMenu by remember { mutableStateOf(false) }
    var copyMenu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }

    val targets = playlists.filter { it.id != pl.id }
    val source = sourceName(pl.sourceType)
    val hasVideoCover = songs.any { !it.coverUrl.isNullOrBlank() }
    val hasCustomCover = !pl.coverUrl.isNullOrBlank() || !pl.coverPath.isNullOrBlank()

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- Hero ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = BmDimens.contentPaddingX, end = BmDimens.contentPaddingX, top = 30.dp, bottom = 22.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 点封面可以直接换封面（跟网易云一致）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(BmDimens.radiusCard))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = hasVideoCover) { showCoverPicker = true },
            ) {
                PlaylistCover(playlist = pl, size = BmDimens.coverBig, radius = BmDimens.radiusCard)
            }
            Spacer(Modifier.width(BmDimens.gapLg))
            Column(modifier = Modifier.height(BmDimens.coverBig)) {
                Text(
                    text = "歌单 · " + source,
                    style = BmType.label,
                    color = BmColors.text3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(BmDimens.radiusTag))
                        .border(1.dp, BmColors.divider, RoundedCornerShape(BmDimens.radiusTag))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = pl.title,
                    style = BmType.titleXl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(13.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape)
                            .background(BmColors.gradientFor(pl.title).first),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = pl.title.take(1), color = Color.White, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildMeta(songs.size, pl.createdAt, source),
                        style = BmType.bodySm,
                        color = BmColors.text2,
                    )
                }
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(source)
                    Tag(if (songs.any { it.localPath.isNullOrBlank() }) "B站音源" else "本地文件")
                }
                Spacer(Modifier.weight(1f))
                // ---------------- 操作 ----------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BmPrimaryButton(
                        text = "播放全部",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onPlayAll,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    BmGhostButton(
                        text = if (selectionMode) "退出多选" else "多选",
                        onClick = {
                            selectionMode = !selectionMode
                            selected = emptySet()
                        },
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Box {
                        BmRoundButton(
                            icon = Icons.Outlined.MoreHoriz,
                            contentDescription = "更多操作",
                            onClick = { moreMenu = !moreMenu },
                        )
                        BmPopupMenu(expanded = moreMenu, onDismiss = { moreMenu = false }) {
                            BmMenuItem("重命名歌单") {
                                moreMenu = false
                                showRename = true
                            }
                            BmMenuItem("选中全部曲目", enabled = songs.isNotEmpty()) {
                                moreMenu = false
                                selectionMode = true
                                selected = songs.map { it.id }.toSet()
                            }
                            BmMenuDivider()
                            BmMenuItem("从歌单视频选封面", enabled = hasVideoCover) {
                                moreMenu = false
                                showCoverPicker = true
                            }
                            BmMenuItem("从本地图片选封面") {
                                moreMenu = false
                                onSetCoverFile(pl.id)
                            }
                            if (hasCustomCover) {
                                BmMenuItem("恢复默认封面") {
                                    moreMenu = false
                                    onSetCoverUrl(pl.id, null)
                                }
                            }
                            BmMenuDivider()
                            BmMenuItem("删除歌单", danger = true) {
                                moreMenu = false
                                showDelete = true
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 多选操作条 ----------------
        if (selectionMode && songs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = BmDimens.contentPaddingX)
                    .height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 " + selected.size + " 首", style = BmType.bodySm, color = BmColors.text2)
                Spacer(Modifier.width(12.dp))
                SmallAction("全选") { selected = songs.map { it.id }.toSet() }
                SmallAction("反选") { selected = songs.map { it.id }.toSet() - selected }
                Spacer(Modifier.weight(1f))
                Box {
                    SmallAction("复制到", enabled = selected.isNotEmpty() && targets.isNotEmpty()) {
                        copyMenu = true
                    }
                    BmPopupMenu(
                        expanded = copyMenu,
                        onDismiss = { copyMenu = false },
                        alignEnd = true,
                        width = 200.dp,
                    ) {
                        if (targets.isEmpty()) {
                            BmMenuItem(text = "需要至少两个歌单", enabled = false, onClick = {})
                        }
                        targets.forEach { target ->
                            BmMenuItem(text = target.title) {
                                copyMenu = false
                                onCopyTo(target.id, selected)
                                selectionMode = false
                                selected = emptySet()
                            }
                        }
                    }
                }
                Box {
                    SmallAction("移动到", enabled = selected.isNotEmpty() && targets.isNotEmpty()) {
                        moveMenu = true
                    }
                    BmPopupMenu(
                        expanded = moveMenu,
                        onDismiss = { moveMenu = false },
                        alignEnd = true,
                        width = 200.dp,
                    ) {
                        if (targets.isEmpty()) {
                            BmMenuItem(text = "需要至少两个歌单", enabled = false, onClick = {})
                        }
                        targets.forEach { target ->
                            BmMenuItem(text = target.title) {
                                moveMenu = false
                                onMoveTo(target.id, selected)
                                selectionMode = false
                                selected = emptySet()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---------------- 表头 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BmDimens.contentPaddingX)
                .height(BmDimens.tableHeaderHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", style = BmType.number, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            Text("音乐标题", style = BmType.number, modifier = Modifier.weight(1f).padding(start = 24.dp))
            Text("UP主", style = BmType.number, modifier = Modifier.width(210.dp))
            Text("时长", style = BmType.number, modifier = Modifier.width(66.dp), textAlign = TextAlign.End)
        }
        BmDivider(modifier = Modifier.padding(horizontal = BmDimens.contentPaddingX))

        // ---------------- 曲目列表 ----------------
        if (songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("这个歌单还没有曲目", style = BmType.titleM)
                Spacer(Modifier.height(8.dp))
                Text(
                    "可以从左侧「导入歌单」拉取 B 站收藏夹 / 稍后再看 / UP主投稿 / 合集",
                    style = BmType.bodySm,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = BmDimens.contentPaddingX - 12.dp)) {
                items(songs.size, key = { songs[it].id }) { index ->
                    val song = songs[index]
                    SongRow(
                        index = index,
                        song = song,
                        current = isCurrent(song),
                        selected = selected.contains(song.id),
                        onClick = {
                            if (selectionMode) {
                                selected = if (selected.contains(song.id)) selected - song.id else selected + song.id
                            } else {
                                onPlayAt(index)
                            }
                        },
                    )
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    if (showRename) {
        BmDialog(
            title = "重命名歌单",
            initial = pl.title,
            confirmText = "保存",
            onDismiss = { showRename = false },
            onConfirm = { text ->
                if (text.isNotBlank()) onRename(pl.id, text.trim())
                showRename = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = BmColors.dialog,
            titleContentColor = BmColors.text,
            textContentColor = BmColors.text2,
            title = { Text("删除歌单", style = BmType.titleL) },
            text = {
                Text(
                    text = "确定删除「" + pl.title + "」吗？\n" +
                        "歌单里的 " + songs.size + " 条曲目记录会一起删除，" +
                        "但已经下载到本地的音频文件不受影响。",
                    style = BmType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete(pl.id) }) {
                    Text("删除", style = BmType.body, color = BmColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text("取消", style = BmType.body, color = BmColors.text2)
                }
            },
        )
    }

    if (showCoverPicker) {
        val candidates = songs.filter { !it.coverUrl.isNullOrBlank() }.distinctBy { it.bvid }
        AlertDialog(
            onDismissRequest = { showCoverPicker = false },
            containerColor = BmColors.dialog,
            titleContentColor = BmColors.text,
            textContentColor = BmColors.text2,
            title = { Text("从歌单视频里选封面", style = BmType.titleL) },
            text = {
                if (candidates.isEmpty()) {
                    Text(
                        "这个歌单里的视频没有封面信息（可能是本地音乐），可以用「从本地图片选封面」。",
                        style = BmType.bodySm,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.width(560.dp).height(420.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        gridItems(candidates, key = { it.id }) { song ->
                            CoverCandidateCell(song = song) {
                                onSetCoverUrl(pl.id, song.coverUrl)
                                showCoverPicker = false
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoverPicker = false }) {
                    Text("取消", style = BmType.body, color = BmColors.text2)
                }
            },
        )
    }
}

/** 封面候选项：视频封面缩略图 + 标题 */
@Composable
private fun CoverCandidateCell(song: StoredSong, onClick: () -> Unit) {
    val client = LocalImageClient.current
    val bitmap = rememberRemoteImage(url = song.coverUrl, client = client ?: fallbackClient)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(130.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BmColors.hover)
            .border(if (hovered) 2.dp else 0.dp, BmColors.brand, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = BmType.label)
            }
        }
        Text(
            text = song.title,
            style = BmType.label,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: StoredSong,
    current: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BmDimens.rowHeight)
            .clip(RoundedCornerShape(BmDimens.radiusRow))
            .background(
                when {
                    selected -> BmColors.active
                    current -> BmColors.brandSoft
                    hovered -> BmColors.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
            if (current) {
                EqBars()
            } else {
                Text(text = (index + 1).toString(), style = BmType.number, color = BmColors.text3)
            }
        }
        Text(
            text = song.title,
            style = BmType.body,
            color = if (current) BmColors.brand else BmColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 24.dp),
        )
        Text(
            text = song.artist.ifBlank { "未知UP主" },
            style = BmType.body,
            color = if (current) BmColors.brand.copy(alpha = 0.85f) else BmColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(210.dp),
        )
        Text(
            text = formatDuration(song.durationMs),
            style = BmType.number,
            color = BmColors.text3,
            maxLines = 1,
            modifier = Modifier.width(66.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** 正在播放时，序号位置变成跳动的三根音柱 */
@Composable
private fun EqBars() {
    val transition = rememberInfiniteTransition(label = "eq")
    val h1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "h1",
    )
    val h2 by transition.animateFloat(
        initialValue = 13f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "h2",
    )
    val h3 by transition.animateFloat(
        initialValue = 7f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse),
        label = "h3",
    )
    Row(
        modifier = Modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(h1, h2, h3).forEach { h ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h.dp)
                    .background(BmColors.brand, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("bilimusic", style = BmType.titleXl.copy(fontSize = 34.sp))
        Spacer(Modifier.height(10.dp))
        Text("B 站音源的音乐播放器", style = BmType.body, color = BmColors.text2)
        Spacer(Modifier.height(28.dp))
        BmDivider(modifier = Modifier.width(120.dp))
        Spacer(Modifier.height(28.dp))
        Text("从左侧选择一个歌单开始", style = BmType.titleM)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "也可以用左侧「粘贴链接 / BV 号播放」直接放一首",
            style = BmType.bodySm,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "或「导入歌单」从 B 站收藏夹 / 稍后再看 / UP主投稿 / 合集拉取",
            style = BmType.bodySm,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text = text,
        style = BmType.label,
        color = BmColors.text2,
        modifier = Modifier
            .clip(RoundedCornerShape(BmDimens.radiusTag))
            .background(BmColors.tagBg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = text,
        style = BmType.bodySm,
        color = when {
            !enabled -> BmColors.text3.copy(alpha = 0.5f)
            hovered -> BmColors.text
            else -> BmColors.text2
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered && enabled) BmColors.hover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}

private fun buildMeta(count: Int, createdAt: Long, source: String): String {
    val date = runCatching {
        Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
            .let { z -> (z.monthValue.toString()) + "-" + z.dayOfMonth.toString().padStart(2, '0') }
    }.getOrNull() ?: ""
    return count.toString() + " 首 · " + date + " 创建 · " + source
}

private fun sourceName(sourceType: String): String = when (sourceType) {
    "FAV" -> "收藏夹"
    "TOVIEW", "TO_VIEW" -> "稍后再看"
    "UPLOAD", "SPACE" -> "UP主投稿"
    "SEASON" -> "合集"
    "LOCAL" -> "本地"
    else -> "手动新建"
}
