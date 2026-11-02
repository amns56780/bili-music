package com.bilimusic.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bilimusic.app.BuildConfig
import com.bilimusic.app.data.local.entity.DownloadRecordEntity
import com.bilimusic.app.data.local.entity.DownloadState
import com.bilimusic.app.data.repository.LoginState
import com.bilimusic.app.domain.model.AudioQualityOption
import com.bilimusic.app.ui.components.ConfirmDialog
import com.bilimusic.app.ui.components.InfoDialog

/**
 * FR-7 页面 9：设置页。
 *
 * 当前可用：账号信息与退出登录（FR-1）、关于与已知限制、免责声明、版本信息。
 * 音质选择（Phase 4/10）、缓存管理（Phase 9）、关闭电池优化引导（Phase 5）
 * 会随对应功能一起加进来，不放点了没反应的占位项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val loginState by viewModel.loginState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showKnownLimits by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AccountCard(
                    loginState = loginState,
                    onOpenLogin = onOpenLogin,
                    onLogout = { showLogoutConfirm = true },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        BatteryOptimizationRow()
                        NotificationPermissionRow()
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow(
                            icon = Icons.AutoMirrored.Outlined.VolumeUp,
                            title = "与其他 App 同时播放",
                            subtitle = if (uiState.keepPlayingWithOtherApps) {
                                "已开启：抖音/微信等前台软件出声时，本 App 继续播放（不申请音频焦点）"
                            } else {
                                "已关闭：其他 App 开始放声音时，本 App 会自动暂停"
                            },
                            checked = uiState.keepPlayingWithOtherApps,
                            onCheckedChange = viewModel::setKeepPlayingWithOtherApps,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            icon = Icons.Outlined.MusicNote,
                            title = "音质选择",
                            subtitle = "${uiState.audioQuality.displayName}（切换后当前歌曲不中断，从下一首开始生效）",
                            onClick = viewModel::openQualityDialog,
                        )
                        SettingsRow(
                            icon = Icons.Outlined.SdStorage,
                            title = "缓存管理",
                            subtitle = "已用 ${SettingsViewModel.formatBytes(uiState.cacheUsedBytes)} / " +
                                "上限 ${SettingsViewModel.formatBytes(uiState.cacheLimitBytes)}，" +
                                "已缓存 ${downloads.size} 首",
                            onClick = viewModel::openCacheDialog,
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            icon = Icons.Outlined.Info,
                            title = "关于 bilimusic",
                            subtitle = "B 站音源本地播放器，只取音频流",
                            onClick = { showAbout = true },
                        )
                        SettingsRow(
                            icon = Icons.Outlined.WarningAmber,
                            title = "已知限制",
                            subtitle = "音频焦点、国产 ROM、音量面板的取舍",
                            onClick = { showKnownLimits = true },
                        )
                        SettingsRow(
                            icon = Icons.Outlined.Description,
                            title = "免责声明",
                            subtitle = "非官方接口 · 仅供个人学习研究",
                            onClick = { showDisclaimer = true },
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("版本信息") },
                        supportingContent = {
                            Text("${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）")
                        },
                    )
                }
            }

            item {
                Text(
                    text = "音质选择、缓存管理、关闭电池优化引导，会随着对应功能一起开放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录",
            message = "退出后会清空本机保存的 Cookie 与账号信息，需要重新扫码登录。已导入的歌单会保留。",
            confirmLabel = "退出登录",
            onConfirm = {
                showLogoutConfirm = false
                viewModel.logout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    if (showAbout) {
        InfoDialog(
            title = "关于 bilimusic",
            message = "bilimusic 是一个本地自用的安卓音乐播放器：\n" +
                "登录 B 站账号 → 把收藏夹 / 稍后再看 / 合集导入成歌单 → 只取音频流播放。\n\n" +
                "• 不存储、不转码、不分发任何音频内容\n" +
                "• 使用 B 站非官方接口，接口变更可能影响功能\n" +
                "• 侧载安装，不上架应用商店",
            onDismiss = { showAbout = false },
        )
    }

    if (showKnownLimits) {
        InfoDialog(
            title = "已知限制",
            message = "本 App 默认不申请音频焦点（AudioFocus），以实现「和别的 App 同时出声互不打断」；" +
                "这个行为可以在设置里用「与其他 App 同时播放」开关关掉，" +
                "关掉后就是标准音乐 App 的行为：别的 App 出声时本 App 自动暂停。\n\n" +
                "默认模式（同时播放）下的已知限制：\n\n" +
                "1. 来电、系统闹钟、语音助手占用音频通道属于系统/硬件层抢占，无法完全避免被静音。\n\n" +
                "2. 部分国产 ROM（MIUI / ColorOS / HarmonyOS 等）有自己的后台播放管控，" +
                "可能强制暂停或压低后台音量。可以在系统设置里为本 App 关闭电池优化。\n\n" +
                "3. 不申请音频焦点的副作用：个别机型上系统音量面板可能不把本 App 识别为" +
                "「活跃媒体会话」。这属于预期取舍，不是 Bug。\n\n" +
                "4. 拔耳机不会自动暂停（同样是刻意设置）。\n\n" +
                "5. B 站取流地址只有 120 分钟有效期，过期会自动重新取流；接口为非官方接口，可能随时变更。",
            onDismiss = { showKnownLimits = false },
        )
    }

    if (showDisclaimer) {
        InfoDialog(
            title = "免责声明",
            message = "1. 本项目使用的 B 站接口均为非官方公开接口，可能随时变更或失效；" +
                "接口变动导致的功能失效不属于 Bug。\n\n" +
                "2. 本项目仅供个人学习研究使用，禁止分发、禁止商业使用、禁止用于任何盈利场景。\n\n" +
                "3. 使用非官方接口存在账号被风控的风险，风险由使用者自负。\n\n" +
                "4. 通过本 App 播放的所有音频内容版权归原 UP 主与哔哩哔哩所有；" +
                "本 App 不存储、不转码、不分发任何内容，仅做个人播放用途。",
            onDismiss = { showDisclaimer = false },
        )
    }

    if (uiState.qualityDialogOpen) {
        QualityPickerDialog(
            current = uiState.audioQuality,
            onPick = viewModel::setAudioQuality,
            onDismiss = viewModel::closeQualityDialog,
        )
    }

    if (uiState.cacheDialogOpen) {
        CacheManagerDialog(
            downloads = downloads,
            usedBytes = uiState.cacheUsedBytes,
            limitBytes = uiState.cacheLimitBytes,
            onDelete = viewModel::deleteDownload,
            onClearAll = viewModel::clearAllCache,
            onOpenLimit = {
                viewModel.closeCacheDialog()
                viewModel.openLimitDialog()
            },
            onDismiss = viewModel::closeCacheDialog,
        )
    }

    if (uiState.limitDialogOpen) {
        CacheLimitDialog(
            current = uiState.cacheLimitBytes,
            onPick = viewModel::setCacheLimit,
            onDismiss = viewModel::closeLimitDialog,
        )
    }
}

/** FR-10 音质档位选择 */
@Composable
private fun QualityPickerDialog(
    current: AudioQualityOption,
    onPick: (AudioQualityOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音质选择") },
        text = {
            Column {
                Text(
                    text = "切换后当前歌曲不中断，从下一首开始生效。接口没有对应档位时会自动降级到最接近的一档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AudioQualityOption.entries.forEach { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) },
                        headlineContent = { Text(option.displayName) },
                        leadingContent = {
                            RadioButton(selected = current == option, onClick = { onPick(option) })
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** FR-9 缓存管理：占用总览 + 逐条删除 + 清空 + 上限设置 */
@Composable
private fun CacheManagerDialog(
    downloads: List<DownloadRecordEntity>,
    usedBytes: Long,
    limitBytes: Long,
    onDelete: (String, String) -> Unit,
    onClearAll: () -> Unit,
    onOpenLimit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缓存管理") },
        text = {
            Column {
                Text(
                    text = "已用 ${SettingsViewModel.formatBytes(usedBytes)} / " +
                        "上限 ${SettingsViewModel.formatBytes(limitBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "缓存 key 用 bvid-cid-音质，超上限按最近最少使用自动淘汰。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (downloads.isEmpty()) {
                    Text(
                        text = "还没有缓存任何曲目。在歌单里点曲目右侧「下载」即可离线播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(downloads, key = { it.cacheKey }) { record ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = record.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "${downloadStateLabel(record)} · " +
                                            SettingsViewModel.formatBytes(record.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { onDelete(record.cacheKey, record.title) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除缓存")
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenLimit) { Text("设置上限") }
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清空缓存") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (confirmClear) {
        ConfirmDialog(
            title = "清空缓存",
            message = "确定删除全部离线缓存吗？已下载的曲目需要重新下载才能离线播放。",
            confirmLabel = "清空",
            onConfirm = {
                confirmClear = false
                onClearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun CacheLimitDialog(
    current: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        1L * 1024 * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        4L * 1024 * 1024 * 1024,
        8L * 1024 * 1024 * 1024,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("缓存上限") },
        text = {
            Column {
                Text(
                    text = "超过上限时按最近最少使用自动淘汰旧缓存（重启 App 后完全生效）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEach { bytes ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(bytes) },
                        headlineContent = { Text(SettingsViewModel.formatBytes(bytes)) },
                        leadingContent = {
                            RadioButton(selected = current == bytes, onClick = { onPick(bytes) })
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun downloadStateLabel(record: DownloadRecordEntity): String =
    when (DownloadState.fromNameOrNull(record.state)) {
        DownloadState.COMPLETED -> "已下载"
        DownloadState.DOWNLOADING -> "下载中 ${(record.progress * 100).toInt()}%"
        DownloadState.QUEUED -> "排队中"
        DownloadState.FAILED -> "下载失败"
        else -> "未知"
    }

@Composable
private fun AccountCard(
    loginState: LoginState,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        when (loginState) {
            is LoginState.LoggedIn -> {
                val account = loginState.account
                ListItem(
                    headlineContent = { Text(account.name) },
                    supportingContent = {
                        Text(
                            text = buildString {
                                append("UID: ${account.mid}")
                                if (account.isVip) append(" · 大会员")
                            },
                        )
                    },
                    leadingContent = {
                        if (account.faceUrl.isBlank()) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = "头像",
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            AsyncImage(
                                model = account.faceUrl,
                                contentDescription = "头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape),
                            )
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = onLogout) { Text("退出登录") }
                    },
                )
            }

            LoginState.LoggedOut -> ListItem(
                headlineContent = { Text("未登录") },
                supportingContent = { Text("登录后才能导入收藏夹、稍后再看、合集并播放") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    TextButton(onClick = onOpenLogin) { Text("去登录") }
                },
            )

            LoginState.Unknown -> ListItem(
                headlineContent = { Text("正在校验登录状态…") },
                leadingContent = {
                    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

/** 带开关的设置行：整行可点，点哪都切换 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/**
 * FR-6 已知限制 2 的应对：部分国产 ROM 会管控后台播放，引导用户关掉电池优化（不强制）。
 * 回到设置页时用 ON_RESUME 重新读一次状态。
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ListItem(
        headlineContent = { Text("后台播放保护") },
        supportingContent = {
            Text(
                text = if (ignoring) {
                    "已关闭电池优化，后台播放更稳"
                } else {
                    "部分国产 ROM 会限制后台播放，建议关闭电池优化"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BatterySaver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            if (ignoring) {
                Text(
                    text = "已开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = { openBatteryOptimizationSettings(context) }) { Text("去设置") }
            }
        },
    )
}

/** FR-8：通知权限被拒绝时给一个可点击的「去授权」入口，不让通知静默消失 */
@Composable
private fun NotificationPermissionRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    ListItem(
        headlineContent = { Text("通知权限未授权") },
        supportingContent = {
            Text(
                text = "播放控制通知、锁屏控制需要通知权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            TextButton(onClick = { openNotificationSettings(context) }) { Text("去授权") }
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(direct) }
        .onFailure { runCatching { context.startActivity(fallback) } }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
