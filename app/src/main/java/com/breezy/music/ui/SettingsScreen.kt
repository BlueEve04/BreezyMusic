package com.breezy.music.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.breezy.music.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalDensity
import com.breezy.music.model.MusicRepository
import com.breezy.music.model.Preferences
import com.breezy.music.model.ThemeMode
import com.breezy.music.model.ThemeState
import com.breezy.ui.button.BreezyButton
import com.breezy.ui.button.BreezyButtonStyle
import com.breezy.ui.button.BreezyButtonSize
import com.breezy.ui.list.BreezyListItem
import com.breezy.ui.text.BreezyText
import com.breezy.ui.text.BreezyTextLevel
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 预设主题色 ──
internal val presetColors = listOf(
    null to "系统莫奈",  // null + 此 label = 莫奈取色
    Color(0xFF4F46E5) to "靛蓝",
    Color(0xFFE91E63) to "玫红",
    Color(0xFF2196F3) to "蓝色",
    Color(0xFFFF9800) to "橙色",
    Color(0xFF4CAF50) to "绿色",
    Color(0xFF9C27B0) to "紫色",
    Color(0xFF00BCD4) to "青色",
    Color(0xFFFF5722) to "深橙",
    null to "自定义"  // null + 此 label = 自定义颜色
)

/**
 * 设置页面
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 弹窗状态 ──
    var showFolderDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLoadingDialog by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var showDoneDialog by remember { mutableStateOf(false) }
    var showPlaySettings by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }

    // ── 文件夹状态 ──
    var folderPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { folderPaths = MusicRepository.getFolders(context) }

    // 持久化文件夹路径
    LaunchedEffect(folderPaths) {
        if (folderPaths.isNotEmpty())
            Preferences.setFolderPaths(context, folderPaths.toSet())
    }

    // ── 系统文件夹选择器 ──
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化 SAF 权限，重启后仍然可访问
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("BreezyMusic", "takePersistableUriPermission failed", e)
            }
            val path = getPathFromUri(context, uri) ?: uri.toString()
            showLoadingDialog = true
            scope.launch {
                Log.i("BreezyMusic", "FolderPicker: uri=$uri, resolved path=$path")
                val count = withContext(Dispatchers.IO) {
                    MusicRepository.scanFolder(context, uri, path) { cur, total ->
                        scope.launch {
                            loadingMessage = if (total <= 0)
                                "正在遍历文件夹，已发现 $cur 个文件..."
                            else
                                "正在解析 $cur/$total 个音乐文件..."
                        }
                    }
                }
                loadingMessage = "解析完成，共添加 $count 首歌曲"
                showLoadingDialog = false
                showDoneDialog = true
                folderPaths = MusicRepository.getFolders(context)
            }
        }
    }

    // ── 暗色模式（使用全局 ThemeState） ──
    var showDarkModeMenu by remember { mutableStateOf(false) }

    // ── 主题色 ──
    var selectedColor by remember { mutableStateOf(ThemeState.accentColor) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    var customRed by remember { mutableStateOf(0.3f) }
    var customGreen by remember { mutableStateOf(0.3f) }
    var customBlue by remember { mutableStateOf(0.9f) }

    // ═══════════════════════════════════════
    // 子页面：播放设置
    // ═══════════════════════════════════════
    if (showPlaySettings) {
        BackHandler(enabled = com.breezy.music.model.Preferences.getBackBehavior(context) == "smart") {
            showPlaySettings = false
        }
        FolderPlaySettingsPage(onBack = { showPlaySettings = false })
        return
    }

    // ═══════════════════════════════════════
    // 主页面（横滑返回 + 列表项渐显）
    // ═══════════════════════════════════════
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BreezyTheme.colors.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 44.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
                }
                BreezyCustomText(
                    text = "设置",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface
                )
            }

            // ── 列表项（使用 BreezyUI 主题列表项） ──
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 0. 关于 Breezy Music
                var showAboutDialog by remember { mutableStateOf(false) }
                val versionInfo = remember {
                    try {
                        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        "${pkgInfo.versionName} (build ${pkgInfo.versionCode})" to "${pkgInfo.versionName} · build ${pkgInfo.versionCode}"
                    } catch (e: Exception) {
                        "未知版本" to "未知版本"
                    }
                }
                BreezyListItem(
                    title = "Breezy Music",
                    subtitle = "版本 ${versionInfo.first} · BlueEve",
                    leadingIcon = Icons.Default.Info,
                    showChevron = true,
                    onClick = { showAboutDialog = true }
                )

                if (showAboutDialog) {
                    Dialog(onDismissRequest = { showAboutDialog = false }) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = BreezyTheme.colors.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.avatar),
                                    contentDescription = "BlueEve",
                                    modifier = Modifier.size(72.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .padding(6.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.height(14.dp))
                                BreezyCustomText("BlueEve", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, color = BreezyTheme.colors.onSurface)
                                Spacer(Modifier.height(6.dp))
                                BreezyCustomText("版本 ${versionInfo.second}", fontSize = 13.sp,
                                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f))
                                Spacer(Modifier.height(8.dp))
                                val context = LocalContext.current
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                BreezyCustomText("www.blueeve.top", fontSize = 13.sp,
                                    color = BreezyTheme.colors.primary,
                                    modifier = Modifier.clickable {
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "www.blueeve.top"))
                                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                Spacer(Modifier.height(2.dp))
                                BreezyCustomText("github.com/BlueEve04", fontSize = 12.sp,
                                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.clickable {
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "https://github.com/BlueEve04"))
                                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                Spacer(Modifier.height(20.dp))
                                TextButton(onClick = { showAboutDialog = false }) {
                                    BreezyCustomText("关闭", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }

                // 1. 音乐文件夹管理
                BreezyListItem(
                    title = "音乐文件夹管理",
                    subtitle = if (folderPaths.isNotEmpty()) "${folderPaths.size} 个文件夹" else "未添加",
                    leadingIcon = Icons.Default.Folder,
                    showChevron = true,
                    onClick = { showFolderDialog = true }
                )

                // 1. 深浅色模式（内建下拉，排在第一位）
                BreezyListItem(
                    title = "深浅色模式",
                    subtitle = when (ThemeState.themeMode) {
                        ThemeMode.Dark -> "深色"
                        ThemeMode.Light -> "浅色"
                        ThemeMode.FollowSystem -> "跟随系统"
                        ThemeMode.Monet -> "跟随系统" // Monet 的深/浅跟随系统
                    },
                    leadingIcon = Icons.Default.DarkMode,
                    trailingText = when (ThemeState.themeMode) {
                        ThemeMode.Dark -> "深色"
                        ThemeMode.Light -> "浅色"
                        ThemeMode.FollowSystem -> "跟随系统"
                        ThemeMode.Monet -> "跟随系统"
                    },
                    trailingDropdown = true,
                    dropdownContent = {
                        listOf(ThemeMode.FollowSystem, ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(mode.label, color = BreezyTheme.colors.onSurface)
                                },
                                onClick = {
                                    ThemeState.themeMode = mode
                                    ThemeState.saveThemeMode(context)
                                },
                                leadingIcon = {
                                    if (ThemeState.themeMode == mode || (ThemeState.themeMode == ThemeMode.Monet && mode == ThemeMode.FollowSystem))
                                        Icon(Icons.Default.Check, null, tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                )

                // 2. 应用主题
                BreezyListItem(
                    title = "应用主题",
                    subtitle = if (ThemeState.themeMode == ThemeMode.Monet) "系统莫奈取色"
                    else presetColors.firstOrNull { it.first == selectedColor }?.second ?: "自定义",
                    leadingIcon = Icons.Default.Palette,
                    showChevron = true,
                    onClick = { showThemeDialog = true }
                )

                // 3. 播放设置
                BreezyListItem(
                    title = "播放设置",
                    subtitle = "文件夹点击策略与播放列表行为",
                    leadingIcon = Icons.Default.Settings,
                    showChevron = true,
                    onClick = { showPlaySettings = true }
                )

                // 返回键行为
                var backBehavior by remember { mutableStateOf(Preferences.getBackBehavior(context)) }
                BreezyListItem(
                    title = "返回键行为",
                    subtitle = if (backBehavior == "smart") "智能返回（多选→收起→上级）" else "直接退到后台",
                    leadingIcon = Icons.Default.ArrowBack,
                    trailingText = if (backBehavior == "smart") "智能返回" else "简单返回",
                    trailingDropdown = true,
                    dropdownContent = {
                        listOf("smart" to "智能返回", "simple" to "简单返回").forEach { (value, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(label, color = BreezyTheme.colors.onSurface)
                                },
                                onClick = {
                                    backBehavior = value
                                    Preferences.setBackBehavior(context, value)
                                },
                                leadingIcon = {
                                    if (backBehavior == value)
                                        Icon(Icons.Default.Check, null,
                                            tint = BreezyTheme.colors.onSurface,
                                            modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                )

                // 歌词：手动滑动后多久回正
                var lyricsResume by remember { mutableStateOf(Preferences.getLyricsResumeSeconds(context)) }
                BreezyListItem(
                    title = "歌词回正延迟",
                    subtitle = "手动滑动歌词后 ${lyricsResume} 秒自动回到当前句",
                    leadingIcon = Icons.Default.MusicNote,
                    trailingDropdown = true,
                    dropdownContent = {
                        listOf(3, 5, 8, 10, 15, 20).forEach { sec ->
                            DropdownMenuItem(
                                text = { Text("$sec 秒", color = BreezyTheme.colors.onSurface) },
                                onClick = {
                                    lyricsResume = sec
                                    Preferences.setLyricsResumeSeconds(context, sec)
                                },
                                leadingIcon = {
                                    if (lyricsResume == sec) Icon(Icons.Default.Check, null,
                                        tint = BreezyTheme.colors.onSurface,
                                        modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                )

                // 5. 封面缓存大小
                val cacheMB = Preferences.getCoverCacheSizeMB(context)
                BreezyListItem(
                    title = "封面缓存大小",
                    subtitle = if (cacheMB == -1) "无限制" else "$cacheMB MB",
                    leadingIcon = Icons.Default.Storage,
                    showChevron = true,
                    onClick = { showCacheDialog = true }
                )

                // 4. 封面裁切
                BreezyListItem(
                    title = "封面裁切",
                    subtitle = ThemeState.coverShape.label,
                    leadingIcon = Icons.Default.Collections,
                    trailingText = ThemeState.coverShape.label,
                    trailingDropdown = true,
                    dropdownContent = {
                        com.breezy.music.model.CoverShape.entries.forEach { shape ->
                            DropdownMenuItem(
                                text = {
                                    Text(shape.label, color = BreezyTheme.colors.onSurface)
                                },
                                onClick = {
                                    ThemeState.coverShape = shape
                                    ThemeState.saveCoverShape(context)
                                },
                                leadingIcon = {
                                    if (ThemeState.coverShape == shape)
                                        Icon(Icons.Default.Check, null, tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                )
            }
        }

        // ═══════════════════════════════════════
        // 文件夹管理弹窗
        // ═══════════════════════════════════════
        if (showFolderDialog) {
            FolderManageDialog(
                folderPaths = folderPaths,
                onDismiss = { showFolderDialog = false },
                onDelete = { path ->
                    scope.launch {
                        MusicRepository.removeFolder(context, path)
                        folderPaths = MusicRepository.getFolders(context)
                    }
                },
                onAddFolder = { folderPicker.launch(null) },
                onRescan = {
                    showLoadingDialog = true
                    scope.launch {
                        val totalSongs = withContext(Dispatchers.IO) {
                            MusicRepository.rescanAll(context) { msg ->
                                scope.launch { loadingMessage = msg }
                            }
                        }
                        loadingMessage = "解析完成，共 $totalSongs 首歌曲"
                        showLoadingDialog = false
                        showDoneDialog = true
                        folderPaths = MusicRepository.getFolders(context)
                    }
                }
            )
        }

        // ═══════════════════════════════════════
        // 主题色弹窗
        // ═══════════════════════════════════════
        if (showThemeDialog) {
            ThemeColorDialog(
                selectedColor = selectedColor,
                onColorSelected = {
                    selectedColor = it
                    ThemeState.accentColor = it
                    ThemeState.saveAccentColor(context)
                },
                onDismiss = { showThemeDialog = false },
                showCustomPicker = showCustomColorPicker,
                onCustomRequest = { showCustomColorPicker = true },
                customRed = customRed,
                customGreen = customGreen,
                customBlue = customBlue,
                onCustomRed = { customRed = it },
                onCustomGreen = { customGreen = it },
                onCustomBlue = { customBlue = it },
                onCustomConfirm = {
                    val color = Color(customRed, customGreen, customBlue)
                    selectedColor = color
                    ThemeState.accentColor = color
                    ThemeState.saveAccentColor(context)
                    showCustomColorPicker = false
                },
                onCustomCancel = { showCustomColorPicker = false }
            )
        }

        // ═══════════════════════════════════════
        // 加载中弹窗
        // ═══════════════════════════════════════
        if (showLoadingDialog) {
            LoadingDialog(message = loadingMessage)
        }

        // ═══════════════════════════════════════
        // 完成弹窗
        // ═══════════════════════════════════════
        if (showDoneDialog) {
            AlertDialog(
                onDismissRequest = { showDoneDialog = false },
                containerColor = BreezyTheme.colors.surface,
                title = {
                    Text("解析完成", color = BreezyTheme.colors.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                text = {
                    Text(loadingMessage, color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    BreezyButton(text = "确定", onClick = { showDoneDialog = false })
                }
            )
        }

        // ═══════════════════════════════════════
        // 封面缓存大小弹窗
        // ═══════════════════════════════════════
        if (showCacheDialog) {
            CacheSizeDialog(
                onDismiss = { showCacheDialog = false },
                context = context
            )
        }
    }
}

// ═══════════════════════════════════════════
// 带动画渐显的设置列表项
// ═══════════════════════════════════════════

@Composable
private fun AnimatedSettingsItem(
    index: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 80L)
        alpha.animateTo(1f, tween(300))
    }
    Box(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha.value }
            .then(modifier)
    ) {
        SettingsItem(icon, title, subtitle, modifier, trailing, onClick)
    }
}

// ═══════════════════════════════════════════
// 设置列表项（保留原有实现）
// ═══════════════════════════════════════════

@Composable
internal fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val tintedBg = androidx.compose.ui.graphics.lerp(
        BreezyTheme.colors.surface,
        BreezyTheme.colors.primary,
        0.12f
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = tintedBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = BreezyTheme.colors.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BreezyCustomText(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = BreezyTheme.colors.onSurface)
                if (subtitle.isNotEmpty()) {
                    BreezyCustomText(text = subtitle, fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            }
            trailing?.invoke()
            Icon(Icons.Default.ChevronRight, null, tint = BreezyTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        }
    }
}

// ═══════════════════════════════════════════
// 文件夹管理弹窗
// ═══════════════════════════════════════════

@Composable
internal fun FolderManageDialog(
    folderPaths: List<String>,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
    onAddFolder: () -> Unit,
    onRescan: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BreezyTheme.colors.surface,
        title = {
            Text("选择音乐文件夹", color = BreezyTheme.colors.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column {
                if (folderPaths.isEmpty()) {
                    Text(
                        "暂无音乐文件夹，请点击下方按钮添加",
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                } else {
                    // 列表（最大高度限制）
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp)
                    ) {
                        items(folderPaths) { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = path,
                                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.8f),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDelete(path) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, "删除", tint = BreezyTheme.colors.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BreezyButton(text = "取消", onClick = onDismiss, style = BreezyButtonStyle.Outlined, size = BreezyButtonSize.Small)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BreezyButton(text = "重新解析", onClick = onRescan, size = BreezyButtonSize.Small)
                    BreezyButton(text = "新增路径", onClick = onAddFolder, size = BreezyButtonSize.Small)
                }
            }
        }
    )
}

// ═══════════════════════════════════════════
// 主题色选择弹窗
// ═══════════════════════════════════════════

@Composable
internal fun ThemeColorDialog(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    showCustomPicker: Boolean,
    onCustomRequest: () -> Unit,
    customRed: Float,
    customGreen: Float,
    customBlue: Float,
    onCustomRed: (Float) -> Unit,
    onCustomGreen: (Float) -> Unit,
    onCustomBlue: (Float) -> Unit,
    onCustomConfirm: () -> Unit,
    onCustomCancel: () -> Unit
) {
    val ctx = LocalContext.current
    val isMonetActive = ThemeState.themeMode == ThemeMode.Monet
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BreezyTheme.colors.surface,
        title = {
            Text("选择主题色", color = BreezyTheme.colors.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column {
                if (showCustomPicker) {
                    // RGB 自定义
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ColorSlider("红色 R", customRed, onCustomRed, Color.Red)
                        ColorSlider("绿色 G", customGreen, onCustomGreen, Color.Green)
                        ColorSlider("蓝色 B", customBlue, onCustomBlue, Color.Blue)
                        // 预览
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(customRed, customGreen, customBlue))
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BreezyButton("取消", onClick = onCustomCancel, style = BreezyButtonStyle.Outlined, size = BreezyButtonSize.Small)
                            BreezyButton("确定", onClick = onCustomConfirm, size = BreezyButtonSize.Small)
                        }
                    }
                } else {
                    // 预设色块网格
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        presetColors.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                row.forEach { (color, label) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            if (color != null) {
                                                onColorSelected(color)
                                                // 选具体颜色时退出莫奈模式
                                                if (ThemeState.themeMode == ThemeMode.Monet) {
                                                    ThemeState.themeMode = ThemeMode.FollowSystem
                                                    ThemeState.saveThemeMode(ctx)
                                                }
                                                onDismiss()
                                            } else if (label == "系统莫奈") {
                                                // 切换到莫奈取色模式
                                                ThemeState.themeMode = ThemeMode.Monet
                                                ThemeState.saveThemeMode(ctx)
                                                onDismiss()
                                            } else {
                                                onCustomRequest()
                                            }
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(color ?: Color.Transparent)
                                                .then(
                                                    if (color == null)
                                                        Modifier.background(Color.Transparent, CircleShape)
                                                    else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (color == null && label == "系统莫奈") {
                                                if (isMonetActive) {
                                                    Icon(Icons.Default.Check, null, tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(24.dp))
                                                } else {
                                                    Icon(Icons.Default.AutoAwesome, null,
                                                        tint = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(28.dp))
                                                }
                                            } else if (color == null) {
                                                Text("+", color = BreezyTheme.colors.onSurface, fontSize = 20.sp)
                                            } else if (color == selectedColor && !isMonetActive) {
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(label, color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
fun ColorSlider(label: String, value: Float, onValueChange: (Float) -> Unit, trackColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = BreezyTheme.colors.primary,
                activeTrackColor = trackColor,
                inactiveTrackColor = trackColor.copy(alpha = 0.2f)
            ),
            valueRange = 0f..1f
        )
        Text(
            "${(value * 255).toInt()}",
            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.width(32.dp)
        )
    }
}

@Composable
private fun CacheSizeDialog(onDismiss: () -> Unit, context: android.content.Context) {
    val currentMB = remember { mutableIntStateOf(Preferences.getCoverCacheSizeMB(context)) }
    var sliderValue by remember {
        mutableStateOf(
            if (currentMB.intValue == -1) 1000f
            else currentMB.intValue.toFloat().coerceIn(100f, 1000f)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BreezyTheme.colors.surface,
        title = {
            Text("封面缓存大小", color = BreezyTheme.colors.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (sliderValue >= 1000f) "无限" else "${sliderValue.toInt()} MB",
                    color = BreezyTheme.colors.primary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 100f..1000f,
                    steps = 0,
                    colors = SliderDefaults.colors(
                        thumbColor = BreezyTheme.colors.primary,
                        activeTrackColor = BreezyTheme.colors.primary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100 MB", fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f))
                    Text("无限", fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f))
                }
            }
        },
        confirmButton = {
            BreezyButton(text = "确定", onClick = {
                val size = if (sliderValue >= 1000f) -1 else sliderValue.toInt()
                Preferences.setCoverCacheSizeMB(context, size)
                currentMB.intValue = size
                onDismiss()
            })
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f))
            }
        }
    )
}

// ═══════════════════════════════════════════
// 加载中弹窗
// ═══════════════════════════════════════════

@Composable
internal fun LoadingDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = BreezyTheme.colors.surface,
        dismissButton = {},
        confirmButton = {},
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp)
            ) {
                CircularProgressIndicator(color = BreezyTheme.colors.primary)
                Spacer(Modifier.height(20.dp))
                Text(message, color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    )
}

// ═══════════════════════════════════════════
// 工具：Uri → 路径
// ═══════════════════════════════════════════

internal fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    // SAF DocumentTree URI → 实际文件系统路径
    val docId = try {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.uri?.lastPathSegment
    } catch (e: Exception) { null }

    if (docId != null) {
        // 格式: "primary:relative/path" 或 "XXXX-XXXX:relative/path"
        val colonIdx = docId.indexOf(':')
        if (colonIdx > 0) {
            val volume = docId.substring(0, colonIdx)
            val relative = docId.substring(colonIdx + 1)
            return if (volume == "primary") {
                "/storage/emulated/0/$relative"
            } else {
                "/storage/$volume/$relative"
            }
        }
    }
    // 回退：尝试直接使用 uri.path
    return uri.path?.let { path ->
        // 从 /tree/primary:relative → /storage/emulated/0/relative
        val treePrefix = "/tree/"
        val idx = path.indexOf(treePrefix)
        if (idx >= 0) {
            val after = path.substring(idx + treePrefix.length)
            val colonIdx2 = after.indexOf(':')
            if (colonIdx2 > 0) {
                val vol = after.substring(0, colonIdx2)
                val rel = after.substring(colonIdx2 + 1)
                if (vol == "primary") "/storage/emulated/0/$rel"
                else "/storage/$vol/$rel"
            } else null
        } else null
    }
}
