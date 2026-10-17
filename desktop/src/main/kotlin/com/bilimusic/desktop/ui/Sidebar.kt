package com.bilimusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimusic.app.data.remote.dto.FavFolderDto
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.data.ImportProgress
import com.bilimusic.desktop.data.StoredPlaylist
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmDimens
import com.bilimusic.desktop.ui.theme.BmType

private val dialogBg = BmColors.dialog

/**
 * 左侧歌单栏：账号区 + 「我的歌单」列表 + 底部入口。
 *
 * 对齐网易云：窄栏、分组标题、封面缩略图、选中项带红色指示条。
 * 所有网络歌单的导入入口都收进「导入歌单」菜单，不占主界面。
 */
@Composable
fun Sidebar(
    auth: AuthUiState,
    playlists: List<StoredPlaylist>,
    songCounts: Map<Long, Int>,
    selectedId: Long?,
    progress: ImportProgress?,
    favFolders: List<FavFolderDto>,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRequestLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenLocalMusic: () -> Unit,
    onLoadFavFolders: () -> Unit,
    onImportFolder: (Long, String) -> Unit,
    onImportToView: () -> Unit,
    onImportSeason: (String) -> Unit,
    onImportSpace: (String) -> Unit,
    onDismissFavPicker: () -> Unit,
    onConsumeProgressError: () -> Unit,
    onOpenInput: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var showFavPicker by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showSeason by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(BmDimens.sidebarWidth)
            .fillMaxHeight()
            .background(BmColors.bgSide),
    ) {
        AccountHeader(
            auth = auth,
            onRequestLogin = onRequestLogin,
            onLogout = onLogout,
        )

        // 粘贴链接 / BV 号播放入口（原来在顶栏那一条，现在收进侧栏，保持主界面干净）
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BmColors.hover)
                .clickable { showInput = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = BmColors.text3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text("粘贴链接 / BV 号播放", style = BmType.label, color = BmColors.text3)
        }

        // 分组标题
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的歌单", style = BmType.bodySm, color = BmColors.text3)
            Spacer(Modifier.weight(1f))
            BmIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "新建歌单",
                onClick = { showCreate = true },
                iconSize = 16.dp,
                tint = BmColors.text2,
            )
        }

        // 歌单列表
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    subtitle = sourceLabel(playlist.sourceType) + " · " + (songCounts[playlist.id] ?: 0) + " 首",
                    selected = playlist.id == selectedId,
                    onClick = { onSelect(playlist.id) },
                )
            }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        text = "还没有歌单。点右上角 ＋ 新建，或从底部导入 B 站歌单。",
                        style = BmType.label,
                        color = BmColors.text3,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // 导入进度 / 错误
        progress?.let { p ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (p.running) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = BmColors.brand,
                        trackColor = BmColors.trackBar,
                    )
                }
                if (p.text.isNotBlank()) {
                    Text(
                        text = p.text,
                        style = BmType.label,
                        color = BmColors.text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                p.error?.let { err ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = err,
                            style = BmType.label,
                            color = BmColors.brand,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = onConsumeProgressError) {
                            Text("知道了", style = BmType.label, color = BmColors.text2)
                        }
                    }
                }
            }
        }

        BmDivider()

        // 底部入口
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            SidebarAction(Icons.Outlined.FolderOpen, "本地音乐", onOpenLocalMusic)
            SidebarAction(Icons.Outlined.CloudDownload, "导入歌单", { showImportMenu = !showImportMenu })
            if (showImportMenu) {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    ImportMenuItem("从 B 站收藏夹导入") {
                        showImportMenu = false
                        showFavPicker = true
                        onLoadFavFolders()
                    }
                    ImportMenuItem("导入「稍后再看」") {
                        showImportMenu = false
                        onImportToView()
                    }
                    ImportMenuItem("导入 UP主投稿 / 合集") {
                        showImportMenu = false
                        showSeason = true
                    }
                }
            }
        }
    }

    // ---------------- 弹窗 ----------------

    if (showCreate) {
        BmDialog(
            title = "新建歌单",
            onDismiss = { showCreate = false },
            confirmText = "创建",
            onConfirm = { text ->
                if (text.isNotBlank()) onCreate(text.trim())
                showCreate = false
            },
        )
    }

    if (showInput) {
        BmDialog(
            title = "播放链接 / BV 号",
            hint = "支持 BV 号、av 号、视频链接、b23.tv 短链",
            onDismiss = { showInput = false },
            confirmText = "播放",
            onConfirm = { text ->
                if (text.isNotBlank()) onOpenInput(text.trim())
                showInput = false
            },
        )
    }

    if (showFavPicker) {
        AlertDialog(
            onDismissRequest = { showFavPicker = false; onDismissFavPicker() },
            containerColor = dialogBg,
            titleContentColor = BmColors.text,
            textContentColor = BmColors.text2,
            title = { Text("选择要导入的收藏夹", style = BmType.titleL) },
            text = {
                if (favFolders.isEmpty()) {
                    Text("没有读取到收藏夹（需要先登录，或在收藏夹里至少有一个）", style = BmType.bodySm)
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(favFolders, key = { it.id }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onImportFolder(folder.id, folder.title.ifBlank { "收藏夹" })
                                        showFavPicker = false
                                        onDismissFavPicker()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.title.ifBlank { "未命名收藏夹" },
                                        style = BmType.body,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(folder.mediaCount.toString() + " 首", style = BmType.label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFavPicker = false; onDismissFavPicker() }) {
                    Text("取消", style = BmType.body, color = BmColors.text2)
                }
            },
        )
    }

    if (showSeason) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSeason = false },
            containerColor = dialogBg,
            titleContentColor = BmColors.text,
            textContentColor = BmColors.text2,
            title = { Text("导入 UP主投稿 / 合集", style = BmType.titleL) },
            text = {
                Column {
                    Text(
                        text = "· UP主主页：space.bilibili.com/1234567（或直接填 UID）\n" +
                            "· 合集：.../channel/collectiondetail?sid=12345（或直接填合集 ID）",
                        style = BmType.bodySm,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("主页链接 / UID / 合集链接 / 合集 ID", style = BmType.bodySm) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = { onImportSpace(input); input = ""; showSeason = false },
                        enabled = input.isNotBlank(),
                    ) { Text("导入 UP主投稿", style = BmType.body, color = BmColors.brand) }
                    TextButton(
                        onClick = { onImportSeason(input); input = ""; showSeason = false },
                        enabled = input.isNotBlank(),
                    ) { Text("导入合集", style = BmType.body, color = BmColors.brand) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSeason = false }) {
                    Text("取消", style = BmType.body, color = BmColors.text2)
                }
            },
        )
    }
}

@Composable
private fun AccountHeader(
    auth: AuthUiState,
    onRequestLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val account = auth.account
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 18.dp, bottom = 12.dp)
            .hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BmCover(cover = null, seed = account?.name ?: "B", size = 36.dp, radius = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account?.name ?: "未登录",
                style = BmType.titleM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = account?.uidText ?: "本地播放可用",
                style = BmType.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (account == null) {
            Text(
                text = "登录",
                style = BmType.bodySm,
                color = BmColors.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onRequestLogin)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        } else if (hovered) {
            BmIconButton(
                icon = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = "退出登录",
                onClick = onLogout,
                iconSize = 15.dp,
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: StoredPlaylist,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .height(54.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        selected -> BmColors.active
                        hovered -> BmColors.hover
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onClick)
                .padding(start = 11.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(playlist = playlist, size = BmDimens.coverSide, radius = BmDimens.radiusCoverSm)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = if (selected) BmType.titleM else BmType.body,
                    color = BmColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = BmType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .width(3.dp)
                    .height(24.dp)
                    .background(BmColors.brand, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SidebarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) BmColors.hover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = BmColors.text2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = BmType.body, color = BmColors.text2)
    }
}

@Composable
private fun ImportMenuItem(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) BmColors.hover else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = BmType.label, color = BmColors.text2)
    }
}

/** 统一的单输入框弹窗（新建歌单 / 粘贴链接共用） */
@Composable
fun BmDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: (String) -> Unit,
    hint: String = "",
    initial: String = "",
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogBg,
        titleContentColor = BmColors.text,
        textContentColor = BmColors.text2,
        title = { Text(title, style = BmType.titleL) },
        text = {
            Column {
                if (hint.isNotBlank()) {
                    Text(hint, style = BmType.bodySm)
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(title, style = BmType.bodySm) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmText, style = BmType.body, color = BmColors.brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", style = BmType.body, color = BmColors.text2) }
        },
    )
}

private fun sourceLabel(sourceType: String): String = when (sourceType) {
    "FAV" -> "收藏夹"
    "TOVIEW", "TO_VIEW" -> "稍后再看"
    "UPLOAD", "SPACE" -> "UP主投稿"
    "SEASON" -> "合集"
    "LOCAL" -> "本地"
    else -> "手动新建"
}
