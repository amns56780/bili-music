package com.bilimusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
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

/**
 * 登录后的主界面：
 * 顶部账号栏 + 「粘贴 BV/链接直接播放」+ 左侧歌单栏 + 歌单详情 + 播放器面板 + 播放队列。
 */
@Composable
fun HomeScreen(
    auth: AuthUiState,
    playback: PlaybackUiState,
    cover: ImageBitmap?,
    controller: PlaybackController,
    playlists: List<StoredPlaylist>,
    selectedPlaylistId: Long?,
    selectedPlaylistTitle: String,
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
    sleepTimer: SleepTimerUiState,
    onStartSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
    extraMessage: String? = null,
    onCopyTo: (Long, Set<Long>) -> Unit,
    onMoveTo: (Long, Set<Long>) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
        // 顶部账号栏
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("bilimusic", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(16.dp))
            Text(
                text = auth.account?.let { "${it.name} · ${it.uidText}" } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onLogout) {
                Icon(Icons.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("退出登录")
            }
        }

        Spacer(Modifier.height(12.dp))

        // 直接播放入口
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("粘贴 BV 号 / av 号 / 视频链接 / b23.tv 短链") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onOpenInput(input) }, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("播放")
            }
        }

        (playback.message ?: extraMessage)?.let { message ->
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = controller::consumeMessage) { Text("知道了") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧：歌单栏
            Box(modifier = Modifier.width(280.dp)) {
                PlaylistSidebar(
                    playlists = playlists,
                    selectedId = selectedPlaylistId,
                    progress = importProgress,
                    favFolders = favFolders,
                    onSelect = onSelectPlaylist,
                    onCreate = onCreatePlaylist,
                    onLoadFavFolders = onLoadFavFolders,
                    onImportFolder = onImportFolder,
                    onImportToView = onImportToView,
                    onOpenLocalMusic = onOpenLocalMusic,
                    onDismissFavPicker = onDismissFavPicker,
                    onConsumeProgressError = onConsumeProgressError,
                )
            }

            Spacer(Modifier.width(14.dp))

            // 中间：歌单详情 + 播放器
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaylistDetail(
                    playlistTitle = selectedPlaylistTitle,
                    songs = songs,
                    currentIndex = playback.index.takeIf { playback.queue.isNotEmpty() } ?: -1,
                    playlists = playlists,
                    onPlayAll = { onPlayPlaylist(0) },
                    onPlayAt = onPlayPlaylist,
                    onCopyTo = onCopyTo,
                    onMoveTo = onMoveTo,
                )
                PlayerPanel(
                    state = playback,
                    cover = cover,
                    controller = controller,
                    sleepTimer = sleepTimer,
                    onStartSleep = onStartSleep,
                    onCancelSleep = onCancelSleep,
                    downloadBusy = downloadBusy,
                    onDownload = onDownload,
                )
            }

            Spacer(Modifier.width(14.dp))

            // 右侧：播放队列
            Box(modifier = Modifier.width(300.dp)) {
                QueueList(state = playback, onPick = controller::playAt)
            }
        }

        if (showLocalMusic) {
            LocalMusicDialog(
                state = localMusic,
                scanDirs = localScanDirs,
                onScan = onScanLocal,
                onPlayAll = onPlayLocal,
                onImport = onImportLocal,
                onDismiss = onDismissLocalMusic,
            )
        }
    }
}
