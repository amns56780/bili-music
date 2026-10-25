package com.bilimusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilimusic.app.data.remote.dto.FavFolderDto
import com.bilimusic.desktop.auth.AuthUiState
import com.bilimusic.desktop.data.ImportProgress
import com.bilimusic.desktop.data.LocalAudioFile
import com.bilimusic.desktop.data.LocalMusicUiState
import com.bilimusic.desktop.data.StoredPlaylist
import com.bilimusic.desktop.data.StoredSong
import com.bilimusic.desktop.player.PlaybackController
import com.bilimusic.desktop.player.PlaybackUiState
import com.bilimusic.desktop.player.SleepTimerUiState
import com.bilimusic.desktop.ui.theme.BmColors
import com.bilimusic.desktop.ui.theme.BmDimens
import com.bilimusic.desktop.ui.theme.BmType

/**
 * 主界面骨架（对齐网易云 PC 版歌单页）：
 *
 *   ┌──────────┬──────────────────────────┐
 *   │ 歌单栏    │ 歌单详情（header + 曲目表） │
 *   ├──────────┴──────────────────────────┤
 *   │ 底部通栏播放条                        │
 *   └─────────────────────────────────────┘
 *
 * 顶栏的账号 / 链接输入都收进了侧栏，主区只服务「当前歌单」这一件事。
 */
@Composable
fun HomeScreen(
    auth: AuthUiState,
    playback: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    playlists: List<StoredPlaylist>,
    songCounts: Map<Long, Int>,
    selectedPlaylist: StoredPlaylist?,
    songs: List<StoredSong>,
    importProgress: ImportProgress?,
    favFolders: List<FavFolderDto>,
    showLocalMusic: Boolean,
    localMusic: LocalMusicUiState,
    localScanDirs: List<String>,
    onOpenInput: (String) -> Unit,
    onLogout: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onLoadFavFolders: () -> Unit,
    onImportFolder: (Long, String) -> Unit,
    onImportToView: () -> Unit,
    onDismissFavPicker: () -> Unit,
    onConsumeProgressError: () -> Unit,
    onPlayPlaylist: (Int) -> Unit,
    onOpenLocalMusic: () -> Unit,
    onScanLocal: () -> Unit,
    onPlayLocal: (List<LocalAudioFile>) -> Unit,
    onImportLocal: (List<LocalAudioFile>) -> Unit,
    onDismissLocalMusic: () -> Unit,
    onAddLocalDir: () -> Unit,
    onRemoveLocalDir: (String) -> Unit,
    onResetLocalDirs: () -> Unit,
    sleepTimer: SleepTimerUiState,
    onStartSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
    extraMessage: String? = null,
    onCopyTo: (Long, Set<Long>) -> Unit,
    onMoveTo: (Long, Set<Long>) -> Unit,
    onRemoveSongs: (Set<Long>) -> Unit,
    onImportSeason: (String) -> Unit,
    onImportSpace: (String) -> Unit,
    onImportBvids: (String) -> Unit,
    onImportShareLink: (String) -> Unit,
    onRequestLogin: () -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onSetCoverUrl: (Long, String?) -> Unit,
    onSetCoverFile: (Long) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(selectedPlaylist?.id, playlists.size) {
        println("[BmDebug] selectedPlaylist=" + selectedPlaylist?.id + " playlists=" + playlists.size + " songs=" + songs.size)
    }
    Column(modifier = Modifier.fillMaxSize().background(BmColors.bg)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Sidebar(
                auth = auth,
                playlists = playlists,
                songCounts = songCounts,
                selectedId = selectedPlaylist?.id,
                progress = importProgress,
                favFolders = favFolders,
                onSelect = onSelectPlaylist,
                onCreate = onCreatePlaylist,
                onRequestLogin = onRequestLogin,
                onLogout = onLogout,
                onOpenLocalMusic = onOpenLocalMusic,
                onLoadFavFolders = onLoadFavFolders,
                onImportFolder = onImportFolder,
                onImportToView = onImportToView,
                onImportSeason = onImportSeason,
                onImportSpace = onImportSpace,
                onImportBvids = onImportBvids,
                onImportShareLink = onImportShareLink,
                onDismissFavPicker = onDismissFavPicker,
                onConsumeProgressError = onConsumeProgressError,
                onOpenInput = onOpenInput,
            )

            // 侧栏与主区的分界线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(BmColors.divider),
            )

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                PlaylistPage(
                    modifier = Modifier.weight(1f),
                    playlist = selectedPlaylist,
                    songs = songs,
                    playlists = playlists,
                    isCurrent = { song ->
                        val current = playback.current
                        current != null && current.bvid == song.bvid && current.cid == song.cid
                    },
                    onPlayAll = { onPlayPlaylist(0) },
                    onPlayAt = onPlayPlaylist,
                    onCopyTo = onCopyTo,
                    onMoveTo = onMoveTo,
                    onRemoveSongs = onRemoveSongs,
                    onRename = onRenamePlaylist,
                    onDelete = onDeletePlaylist,
                    onSetCoverUrl = onSetCoverUrl,
                    onSetCoverFile = onSetCoverFile,
                )

                // 操作反馈条（原来是顶部一条提示，现在贴着播放条显示，不挡内容）
                val message = playback.message ?: extraMessage
                if (message != null) {
                    MessageBar(
                        text = message,
                        onDismiss = controller::consumeMessage,
                    )
                }
            }
        }

        BmDivider()

        PlayerBar(
            state = playback,
            cover = cover,
            controller = controller,
            sleepTimer = sleepTimer,
            downloadBusy = downloadBusy,
            onDownload = onDownload,
            onStartSleep = onStartSleep,
            onCancelSleep = onCancelSleep,
        )
    }

    if (showLocalMusic) {
        LocalMusicDialog(
            state = localMusic,
            custom = localMusic.custom,
            scanDirs = localScanDirs,
            onScan = onScanLocal,
            onPlayAll = onPlayLocal,
            onImport = onImportLocal,
            onAddDir = onAddLocalDir,
            onRemoveDir = onRemoveLocalDir,
            onResetDirs = onResetLocalDirs,
            onDismiss = onDismissLocalMusic,
        )
    }
}

/** 底部一条细长的操作反馈（导入结果、错误提示等） */
@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(BmColors.brandSoft)
            .padding(horizontal = BmDimens.contentPaddingX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = BmType.bodySm,
            color = BmColors.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "知道了",
            style = BmType.label,
            color = BmColors.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
