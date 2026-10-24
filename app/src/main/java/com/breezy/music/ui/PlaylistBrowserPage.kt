package com.breezy.music.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.documentfile.provider.DocumentFile
import com.breezy.music.model.*
import com.breezy.music.util.M3u8Parser
import com.breezy.music.util.SongSortUtil
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.absoluteValue

private const val TAG = "PlaylistBrowser"

// 离开页面再返回时保留状态
private val savedSelectedPlaylist = mutableStateOf<M3u8Parser.PlaylistInfo?>(null)

/** 将歌单歌曲顺序写回 M3U8 文件（保留原有行格式） */
private suspend fun writePlaylistOrder(context: android.content.Context, playlistUri: String, songs: List<SongEntity>) {
    try {
        val uri = Uri.parse(playlistUri)
        android.util.Log.d(TAG, "writePlaylistOrder: ${songs.size}首")

        // 读取现有内容，建立 path→原始行 的映射
        val existingLines = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        existingLines.add(line)
                    }
                }
            }
        }

        // 构建反向索引：原始行 → 在现有列表中的索引
        // 同时构建：歌曲 path 后缀 → 原始行
        val pathToLine = mutableMapOf<String, String>()
        for (line in existingLines) {
            val key = line.substringAfterLast('/')
            if (key.isNotEmpty()) pathToLine[key] = line
        }

        // 按新顺序写出，已有歌曲用原始行格式，新歌用文件名
        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
        val writeUri = doc?.uri ?: uri
        context.contentResolver.openOutputStream(writeUri, "w")?.use { out ->
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("#EXTM3U\n")
                for (song in songs) {
                    val fileName = song.path.substringAfterLast('/')
                    val line = pathToLine[fileName] ?: fileName
                    writer.write("$line\n")
                }
            }
        }
        android.util.Log.d(TAG, "writePlaylistOrder: 写入成功")
    } catch (e: Exception) {
        android.util.Log.e(TAG, "writePlaylistOrder: 失败", e)
    }
}

/**
 * 在指定文件夹下新建 .m3u8 歌单文件，并注册到缓存
 */
private fun createPlaylistFile(context: android.content.Context, folderTreeUri: String, name: String) {
    try {
        val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderTreeUri))
            ?: throw Exception("无法访问文件夹")
        val fileName = "$name.m3u8"
        // 删除已存在的同名文件
        treeDoc.listFiles().find { it.name == fileName }?.delete()
        // 创建新文件
        val newFile = treeDoc.createFile("audio/x-mpegurl", name)  // 不带扩展名，DocumentFile 自动加
            ?: throw Exception("创建文件失败")
        // 写入 M3U8 头
        context.contentResolver.openOutputStream(newFile.uri, "wt")?.use { out ->
            out.write("#EXTM3U\n".toByteArray())
        } ?: throw Exception("无法写入文件")
        // 注册到缓存
        val uris = Preferences.getM3u8Uris(context).toMutableSet()
        uris.add(newFile.uri.toString())
        Preferences.setM3u8Uris(context, uris)
        Log.i(TAG, "createPlaylistFile: $fileName 创建成功 → ${newFile.uri}")
    } catch (e: Exception) {
        Log.e(TAG, "createPlaylistFile: 失败", e)
        throw e
    }
}

/**
 * 歌单浏览页面 —— 发现并展示所有 .m3u8 文件
 * 点击歌单进入详情，支持长按拖拽排序
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistBrowserPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current

    // 详情导航（文件级持久化）
    var selectedPlaylist by savedSelectedPlaylist

    var playlists by remember { mutableStateOf<List<M3u8Parser.PlaylistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 多选
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 新建歌单
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showNewPlaylistNameInput by remember { mutableStateOf(false) }
    var newPlaylistFolderUri by remember { mutableStateOf("") }
    var newPlaylistName by remember { mutableStateOf("") }

    BackHandler(enabled = multiSelectMode
        && Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedNames = emptySet()
    }

    // 加载歌单：优先读解析缓存，缓存未命中才解析 M3U8 文件
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = MusicDatabase.getInstance(context).musicDao()
            val allSongs = dao.getAllSongs()
            val cachedUris = Preferences.getM3u8Uris(context)
            Log.i(TAG, "加载歌单: ${cachedUris.size} URI, ${allSongs.size} 首歌曲")

            if (cachedUris.isEmpty()) {
                playlists = emptyList()
                loading = false
                return@withContext
            }

            // 歌曲路径 → SongEntity 快速查找
            val songMap = mutableMapOf<String, SongEntity>()
            for (song in allSongs) songMap[song.path] = song

            val found = mutableListOf<M3u8Parser.PlaylistInfo>()
            for (uriStr in cachedUris) {
                val uri = Uri.parse(uriStr)
                // URL-decode + 仅取文件名部分
                val rawName = uri.lastPathSegment ?: uriStr.substringAfterLast('/')
                val name = rawName.removeSuffix(".m3u8").removeSuffix(".m3u")
                    .ifEmpty { "未知歌单" }

                // 优先读缓存
                val cachedPaths = Preferences.getPlaylistSongsCache(context, uriStr)
                if (cachedPaths.isNotEmpty()) {
                    val cachedSongs = cachedPaths.mapNotNull { songMap[it] }
                    Log.d(TAG, "  [缓存命中]「$name」→ ${cachedSongs.size} 首")
                    if (cachedSongs.isNotEmpty()) {
                        found.add(M3u8Parser.PlaylistInfo(name, uriStr, cachedSongs))
                    }
                } else {
                    // 缓存未命中，解析文件
                    val pl = M3u8Parser.parse(context, Uri.parse(uriStr), allSongs, songMap)
                    Log.i(TAG, "  [解析]「${pl.name}」→ ${pl.songs.size} 首")
                    if (pl.songs.isNotEmpty()) {
                        found.add(pl)
                        // 存入缓存
                        Preferences.setPlaylistSongsCache(context, uriStr,
                            pl.songs.map { it.path })
                    }
                }
            }

            found.sortWith(compareBy(SongSortUtil.stringComparator) { it.name })
            playlists = found
            Log.i(TAG, "完成: ${found.size} 个有效歌单")
        }
        loading = false
    }

    // 过滤
    val filteredPlaylists = if (displayQuery.isNotEmpty())
        playlists.filter { it.name.contains(displayQuery, ignoreCase = true) }
    else playlists

    // 桶索引
    val bucketIndex = remember(filteredPlaylists) {
        SongSortUtil.buildBucketIndex(filteredPlaylists.map { it.name })
    }

    // 歌单详情页
    if (selectedPlaylist != null) {
        PlaylistDetailView(
            playlist = selectedPlaylist!!,
            onBack = { selectedPlaylist = null },
            onSongsChanged = { newSongs ->
                selectedPlaylist = selectedPlaylist?.copy(songs = newSongs)
                // 同步更新 playlists 列表
                playlists = playlists.map { p ->
                    if (p.uri == selectedPlaylist?.uri) p.copy(songs = newSongs) else p
                }
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏 ──
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 4.dp, top = 48.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (multiSelectMode) { multiSelectMode = false; selectedNames = emptySet() }
                    else onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
                }

                if (isSearching) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            searchJob.value?.cancel()
                            searchJob.value = scope.launch { delay(300); displayQuery = q }
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = BreezyTheme.colors.onSurface),
                        cursorBrush = SolidColor(BreezyTheme.colors.primary),
                        modifier = Modifier.weight(1f).height(40.dp)
                            .focusRequester(focusRequester)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BreezyTheme.colors.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty())
                                    BreezyCustomText("搜索歌单...", fontSize = 14.sp,
                                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(horizontal = 12.dp))
                                inner()
                            }
                        }
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    IconButton(onClick = { searchQuery = ""; displayQuery = ""; isSearching = false }) {
                        Icon(Icons.Default.Close, "关闭", tint = BreezyTheme.colors.onSurface)
                    }
                } else {
                    BreezyCustomText("歌单", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f))
                    if (!multiSelectMode) {
                        BreezyCustomText("${filteredPlaylists.size} 个", fontSize = 13.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 8.dp))
                        IconButton(onClick = { showNewPlaylistDialog = true }) {
                            Icon(Icons.Default.Add, "新建歌单",
                                tint = BreezyTheme.colors.primary, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { isSearching = true; searchQuery = ""; displayQuery = "" }) {
                            Icon(Icons.Default.Search, "搜索", tint = BreezyTheme.colors.onSurface)
                        }
                    }
                }
            }

            // ── 列表 ──
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("扫描歌单中...", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else if (filteredPlaylists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("未发现歌单文件\n请在音乐文件夹中放入 .m3u8 文件后重新扫描",
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val trackH = maxHeight
                    val total = filteredPlaylists.size
                    val listState = rememberLazyListState()

                    val scrollRatio by remember(total) {
                        derivedStateOf {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            if (visible.isEmpty() || total <= visible.size) 0f
                            else {
                                val firstIdx = listState.firstVisibleItemIndex
                                val offset = listState.firstVisibleItemScrollOffset
                                val firstH = visible.first().size
                                val frac = if (firstH > 0) offset.toFloat() / firstH else 0f
                                ((firstIdx + frac) / (total - visible.size)).coerceIn(0f, 1f)
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredPlaylists, key = { it.uri }) { pl ->
                            PlaylistListItem(
                                playlist = pl,
                                isSelected = pl.name in selectedNames,
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) {
                                        selectedNames = if (pl.name in selectedNames)
                                            selectedNames - pl.name
                                        else selectedNames + pl.name
                                    } else selectedPlaylist = pl
                                },
                                onLongPress = {
                                    if (!multiSelectMode) {
                                        multiSelectMode = true
                                        selectedNames = setOf(pl.name)
                                    }
                                }
                            )
                        }
                    }

                    if (displayQuery.isEmpty() && bucketIndex.isNotEmpty()) {
                        QuickScrollBar(
                            modifier = Modifier.matchParentSize(),
                            bucketLabels = SongSortUtil.allBucketLabels,
                            bucketIndex = bucketIndex,
                            totalItems = total,
                            scrollRatio = scrollRatio,
                            trackHeightDp = trackH,
                            onScrollToIndex = { idx ->
                                scope.launch { listState.scrollToItem(idx.coerceIn(0, total - 1)) }
                            },
                            firstCharAt = { idx ->
                                filteredPlaylists.getOrNull(idx)?.name?.take(1) ?: ""
                            },
                            pinyinAt = { idx ->
                                SongSortUtil.getPinyin(filteredPlaylists.getOrNull(idx)?.name ?: "")
                            }
                        )
                    }
                }
            }
        }

        // 多选工具栏
        setDock(
            if (multiSelectMode) {
                {
                    MultiSelectDock(
                        onCancel = { multiSelectMode = false; selectedNames = emptySet() },
                        actions = {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val toAdd = mutableListOf<SongEntity>()
                                    selectedNames.forEach { name ->
                                        val pl = playlists.find { it.name == name }
                                        if (pl != null) toAdd.addAll(pl.songs)
                                    }
                                    withContext(Dispatchers.Main) {
                                        PlaybackState.appendSongs(toAdd)
                                        multiSelectMode = false; selectedNames = emptySet()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Add, "添加到播放列表",
                                    tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                            }
                        }
                    )
                }
            } else null
        )

        // ── 新建歌单：选择文件夹 ──
        if (showNewPlaylistDialog) {
            val folders = Preferences.getFolderPaths(context).toList()
            AlertDialog(
                onDismissRequest = { showNewPlaylistDialog = false },
                containerColor = BreezyTheme.colors.surface,
                title = { Text("选择歌单存放目录", color = BreezyTheme.colors.onSurface) },
                text = {
                    if (folders.isEmpty()) {
                        Text("请先在设置中添加音乐文件夹\n（歌单不能跨存储库）",
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f))
                    } else {
                        Column {
                            Text("歌单将建立在所选文件夹的根目录下\n不同存储库的歌单不可混用",
                                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            folders.forEach { folderUri ->
                                val displayName = try {
                                    val doc = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                                    doc?.name ?: Uri.parse(folderUri).lastPathSegment ?: folderUri
                                } catch (_: Exception) {
                                    Uri.parse(folderUri).lastPathSegment ?: folderUri
                                }
                                TextButton(
                                    onClick = {
                                        newPlaylistFolderUri = folderUri
                                        showNewPlaylistDialog = false
                                        showNewPlaylistNameInput = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(displayName, color = BreezyTheme.colors.onSurface)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showNewPlaylistDialog = false }) {
                        Text("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                }
            )
        }

        // ── 新建歌单：命名 ──
        if (showNewPlaylistNameInput) {
            var nameInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewPlaylistNameInput = false },
                containerColor = BreezyTheme.colors.surface,
                title = { Text("新建歌单", color = BreezyTheme.colors.onSurface) },
                text = {
                    Column {
                        Text("请输入歌单名称（不含扩展名）",
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            label = { Text("歌单名称") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = BreezyTheme.colors.onSurface,
                                unfocusedTextColor = BreezyTheme.colors.onSurface,
                                focusedBorderColor = BreezyTheme.colors.primary,
                                unfocusedBorderColor = BreezyTheme.colors.onSurface.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = nameInput.trim()
                            if (name.isNotEmpty()) {
                                newPlaylistName = name
                                showNewPlaylistNameInput = false
                                // 创建歌单文件
                                scope.launch(Dispatchers.IO) {
                                    createPlaylistFile(context, newPlaylistFolderUri, name)
                                    withContext(Dispatchers.Main) {
                                        // 重新加载歌单列表
                                        loading = true
                                    }
                                    // 触发重新加载
                                    val dao = MusicDatabase.getInstance(context).musicDao()
                                    val allSongs = dao.getAllSongs()
                                    val songMap = mutableMapOf<String, SongEntity>()
                                    for (s in allSongs) songMap[s.path] = s
                                    // 重新扫描 M3U8 URI
                                    val uris = Preferences.getM3u8Uris(context).toMutableSet()
                                    val newPlaylists = mutableListOf<M3u8Parser.PlaylistInfo>()
                                    for (uriStr in uris) {
                                        val uri = Uri.parse(uriStr)
                                        val rawName = uri.lastPathSegment ?: uriStr.substringAfterLast('/')
                                        val plName = rawName.removeSuffix(".m3u8").removeSuffix(".m3u").ifEmpty { "未知歌单" }
                                        val cachedPaths = Preferences.getPlaylistSongsCache(context, uriStr)
                                        val cachedSongs = cachedPaths.mapNotNull { songMap[it] }
                                        if (cachedSongs.isNotEmpty()) {
                                            newPlaylists.add(M3u8Parser.PlaylistInfo(plName, uriStr, cachedSongs))
                                        } else {
                                            val pl = M3u8Parser.parse(context, uri, allSongs, songMap)
                                            if (pl.songs.isNotEmpty()) {
                                                newPlaylists.add(pl)
                                                Preferences.setPlaylistSongsCache(context, uriStr, pl.songs.map { it.path })
                                            }
                                        }
                                    }
                                    newPlaylists.sortWith(compareBy(SongSortUtil.stringComparator) { it.name })
                                    withContext(Dispatchers.Main) {
                                        playlists = newPlaylists
                                        loading = false
                                        Toast.makeText(context, "歌单「$name」已创建", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = nameInput.isNotBlank()
                    ) {
                        Text("创建", color = BreezyTheme.colors.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewPlaylistNameInput = false }) {
                        Text("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                }
            )
        }
    }
}

// ═══════════════════════════════════════
// 歌单列表项
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistListItem(
    playlist: M3u8Parser.PlaylistInfo,
    isSelected: Boolean,
    multiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) BreezyTheme.colors.onSurface.copy(alpha = 0.1f)
                else BreezyTheme.colors.onSurface.copy(alpha = 0.04f)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前三首封面叠放
        val context = LocalContext.current
        val topSongs = remember(playlist.uri) { playlist.songs.take(3) }
        Box(modifier = Modifier.size(48.dp)) {
            topSongs.forEachIndexed { i, song ->
                var cover by remember(song.path) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(song.path) {
                    withContext(Dispatchers.IO) {
                        cover = song.path.takeIf { it.isNotEmpty() }?.let { AlbumArtCache.get(context, it) }
                    }
                }
                Box(
                    modifier = Modifier
                        .offset(x = (i * 10).dp, y = (i * 6).dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BreezyTheme.colors.onSurface.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        Image(bitmap = cover!!, contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.MusicNote, null,
                            tint = BreezyTheme.colors.onSurface.copy(alpha = 0.25f),
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            BreezyCustomText(playlist.name, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = BreezyTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BreezyCustomText("${playlist.songs.size} 首", fontSize = 12.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
        }

        if (isSelected) {
            Icon(Icons.Default.CheckCircle, "已选",
                tint = BreezyTheme.colors.primary, modifier = Modifier.size(22.dp))
        } else if (!multiSelectMode) {
            Icon(Icons.Default.ChevronRight, "打开",
                tint = BreezyTheme.colors.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
        }
    }
}

// ═══════════════════════════════════════
// 歌单详情 —— 歌曲列表（支持长按拖拽排序）
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailView(
    playlist: M3u8Parser.PlaylistInfo,
    onBack: () -> Unit,
    onSongsChanged: (List<SongEntity>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current
    var songs by remember(playlist.uri) { mutableStateOf(playlist.songs) }
    val lazyListState = rememberLazyListState()
    val density = LocalDensity.current

    // 多选
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 拖拽状态
    var draggedIdx by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(60f) }
    var settling by remember { mutableStateOf(false) }
    var pendingReorder by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val settleAnim = remember { Animatable(0f) }
    var reorderVersion by remember { mutableIntStateOf(0) }

    // 拖拽边缘平滑自动滚动
    //   追赶模式：item 超出视口 → 一次性 scrollBy 追回来
    //   边缘模式：item 在边缘区域内 → 逐帧微调
    LaunchedEffect(draggedIdx) {
        if (draggedIdx == null) return@LaunchedEffect
        val totalItems = songs.size
        while (draggedIdx != null) {
            val layout = lazyListState.layoutInfo
            val vh = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
            if (vh <= 0f) { delay(16); continue }
            val draggedItem = layout.visibleItemsInfo.find { it.index == draggedIdx }
            val edgeSize = (itemHeightPx * 3f).coerceAtMost(vh * 0.40f)
            val firstIdx = layout.visibleItemsInfo.first().index
            val lastIdx = layout.visibleItemsInfo.last().index

            if (draggedItem != null) {
                val itemCenter = draggedItem.offset + draggedItem.size / 2f + dragOffsetY - layout.viewportStartOffset

                if (itemCenter < 0f && firstIdx > 0) {
                    // 追赶模式：item 超出顶部 → 缓慢追上，但不超过第一项
                    lazyListState.scroll { scrollBy(itemCenter.coerceAtLeast(-itemHeightPx * 0.3f)) }
                } else if (itemCenter > vh && lastIdx < totalItems - 1) {
                    lazyListState.scroll { scrollBy(itemCenter - vh) }
                } else if (itemCenter < edgeSize && firstIdx > 0) {
                    val factor = (1f - itemCenter / edgeSize).coerceIn(0.1f, 1f)
                    lazyListState.scroll { scrollBy(-itemHeightPx * factor * 0.15f) }
                } else if (itemCenter > vh - edgeSize && lastIdx < totalItems - 1) {
                    val factor = (1f - (vh - itemCenter) / edgeSize).coerceIn(0.1f, 1f)
                    lazyListState.scroll { scrollBy(itemHeightPx * factor * 0.15f) }
                }
            } else {
                // 拖拽项不可见：根据索引估计方向滚动
                if (draggedIdx!! <= firstIdx && firstIdx > 0)
                    lazyListState.scroll { scrollBy(-itemHeightPx * 0.25f) }
                else if (draggedIdx!! >= lastIdx && lastIdx < totalItems - 1)
                    lazyListState.scroll { scrollBy(itemHeightPx * 0.25f) }
            }
            delay(16)
        }
    }

    BackHandler(enabled = multiSelectMode
        && Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedPaths = emptySet()
    }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }

    val filteredSongs = if (displayQuery.isNotEmpty())
        songs.filter { it.title.contains(displayQuery, ignoreCase = true) }
    else songs

    val bucketIndex = remember(filteredSongs) { SongSortUtil.buildBucketIndex(filteredSongs) }

    // 拖拽实时目标索引（所有 item 共享，确保避让即时生效）
    val dragTargetIdx by remember {
        derivedStateOf {
            if (draggedIdx != null) {
                (draggedIdx!! + dragOffsetY / itemHeightPx.coerceAtLeast(1f)).roundToInt()
                    .coerceIn(0, filteredSongs.lastIndex)
            } else null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BreezyTheme.colors.background)) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 4.dp, top = 48.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (multiSelectMode) { multiSelectMode = false; selectedPaths = emptySet() }
                else onBack()
            }) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            if (isSearching) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { q -> searchQuery = q; displayQuery = q },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = BreezyTheme.colors.onSurface),
                    cursorBrush = SolidColor(BreezyTheme.colors.primary),
                    modifier = Modifier.weight(1f).height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BreezyTheme.colors.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty())
                                BreezyCustomText("搜索...", fontSize = 14.sp,
                                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(horizontal = 12.dp))
                            inner()
                        }
                    }
                )
                IconButton(onClick = { searchQuery = ""; displayQuery = ""; isSearching = false }) {
                    Icon(Icons.Default.Close, "关闭", tint = BreezyTheme.colors.onSurface)
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    BreezyCustomText(playlist.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BreezyCustomText("${filteredSongs.size} 首 · 长按拖拽排序", fontSize = 12.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
                if (!multiSelectMode) {
                    IconButton(onClick = { isSearching = true; searchQuery = ""; displayQuery = "" }) {
                        Icon(Icons.Default.Search, "搜索", tint = BreezyTheme.colors.onSurface)
                    }
                    IconButton(onClick = {
                        // "播放全部"始终立即开始播放
                        PlaybackState.playList(filteredSongs)
                    }) {
                        Icon(Icons.Default.PlayArrow, "播放全部",
                            tint = BreezyTheme.colors.primary, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        if (filteredSongs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("歌单为空", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val trackH = maxHeight
                val total = filteredSongs.size

                val scrollRatio by remember(total) {
                    derivedStateOf {
                        val visible = lazyListState.layoutInfo.visibleItemsInfo
                        if (visible.isEmpty() || total <= visible.size) 0f
                        else {
                            val firstIdx = lazyListState.firstVisibleItemIndex
                            val offset = lazyListState.firstVisibleItemScrollOffset
                            val firstH = visible.first().size
                            val frac = if (firstH > 0) offset.toFloat() / firstH else 0f
                            ((firstIdx + frac) / (total - visible.size)).coerceIn(0f, 1f)
                        }
                    }
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filteredSongs, key = { _, s -> s.path }) { index, song ->
                        val isDragging = draggedIdx == index

                        // 视觉偏移：被拖项跟手（无动画），中间项拖拽中平滑避让，松手立刻归零
                        val visualOffsetY = if (isDragging) {
                            if (settling) settleAnim.value else dragOffsetY
                        } else if (draggedIdx != null && dragTargetIdx != null) {
                            // 拖拽进行中：平滑避让
                            val ti = dragTargetIdx!!
                            val target = when {
                                draggedIdx!! < ti && index in (draggedIdx!! + 1)..ti -> -itemHeightPx
                                draggedIdx!! > ti && index in ti..(draggedIdx!! - 1) -> +itemHeightPx
                                else -> 0f
                            }
                            animateFloatAsState(targetValue = target, animationSpec = tween(150)).value
                        } else {
                            // 无拖拽：直接归零，不播放回弹动画
                            0f
                        }

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = visualOffsetY
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .onSizeChanged { itemHeightPx = it.height.toFloat() }
                                    .then(
                                        // 拖拽在非多选或多选≤1项时启用
                                        if (!multiSelectMode || selectedPaths.size <= 1)
                                            Modifier.pointerInput(multiSelectMode, selectedPaths.size, reorderVersion) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val downPos = down.position
                                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                                val touchSlop = viewConfiguration.touchSlop

                                                var isTap = false
                                                var isLongPress = false

                                                val timedOut = withTimeoutOrNull(longPressTimeout) {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (!change.pressed) {
                                                            // Finger up → potential tap
                                                            if ((change.position - downPos).getDistance() < touchSlop)
                                                                isTap = true
                                                            break
                                                        }
                                                        // Movement beyond slop → scrolling, cancel
                                                        if ((change.position - downPos).getDistance() > touchSlop)
                                                            break
                                                    }
                                                }

                                                if (isTap) {
                                                    // ★ 消费事件，防止 inner SongItem combinedClickable 重复触发
                                                    down.consume()
                                                    if (multiSelectMode) {
                                                        val wasSelected = song.path in selectedPaths
                                                        selectedPaths = if (wasSelected) selectedPaths - song.path
                                                        else selectedPaths + song.path
                                                    } else {
                                                        PlaybackState.play(song)
                                                    }
                                                    return@awaitEachGesture
                                                }
                                                if (timedOut != null) {
                                                    // 在长按超时前抬起或滑动 → 不做任何事
                                                    return@awaitEachGesture
                                                }
                                                // ── 长按已触发 ──
                                                down.consume()
                                                // 仅首次进入多选时设置选中项
                                                if (!multiSelectMode) {
                                                    multiSelectMode = true
                                                    selectedPaths = setOf(song.path)
                                                }
                                                val idx = songs.indexOfFirst { it.path == song.path }
                                                if (idx >= 0) { draggedIdx = idx; dragOffsetY = 0f }
                                                // 拖拽阶段
                                                drag(down.id) { change ->
                                                    change.consume()
                                                    dragOffsetY += change.position.y - change.previousPosition.y
                                                }
                                                // 拖拽结束
                                                val from = draggedIdx
                                                if (from != null) {
                                                    if (dragOffsetY.absoluteValue < 40f) {
                                                        // 几乎没有移动：非多选→进入多选，已多选→不操作
                                                        if (!multiSelectMode) {
                                                            multiSelectMode = true
                                                            selectedPaths = setOf(song.path)
                                                        }
                                                    } else {
                                                        val steps = (dragOffsetY / itemHeightPx.coerceAtLeast(1f)).roundToInt()
                                                        val to = (from + steps).coerceIn(0, songs.lastIndex)
                                                        if (to != from) {
                                                            pendingReorder = Pair(from, to)  // 延迟commit
                                                        }
                                                    }
                                                }
                                                // 平滑归位到空槽（目标偏移 = (to-from)*itemHeight，直接滑入避让空位）
                                                if (dragOffsetY != 0f) {
                                                    settling = true
                                                    val finalOffset = dragOffsetY
                                                    val targetOffset = if (pendingReorder != null) {
                                                        (pendingReorder!!.second - pendingReorder!!.first).toFloat() * itemHeightPx
                                                    } else 0f
                                                    scope.launch {
                                                        settleAnim.snapTo(finalOffset)
                                                        settleAnim.animateTo(targetOffset, tween(150))
                                                        pendingReorder?.let { (f, t) ->
                                                            pendingReorder = null
                                                            songs = songs.toMutableList().apply { add(t, removeAt(f)) }
                                                            val newSongs = songs
                                                            onSongsChanged(newSongs)
                                                            reorderVersion++
                                                            // 异步写入文件
                                                            launch(Dispatchers.IO) {
                                                                writePlaylistOrder(context, playlist.uri, newSongs)
                                                                Preferences.setPlaylistSongsCache(context, playlist.uri,
                                                                    newSongs.map { it.path })
                                                            }
                                                        }
                                                        settling = false
                                                        draggedIdx = null; dragOffsetY = 0f
                                                    }
                                                } else {
                                                    draggedIdx = null; dragOffsetY = 0f
                                                }
                                            }
                                        } else Modifier
                                    )
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isDragging) BreezyTheme.colors.primary.copy(alpha = 0.2f)
                                        else if (song.path in selectedPaths) BreezyTheme.colors.primary.copy(alpha = 0.18f)
                                        else Color.Transparent
                                    )
                                ) {
                                    SongItem(
                                        song = song,
                                        isSelected = song.path in selectedPaths,
                                        multiSelectMode = multiSelectMode,
                                        onTap = {
                                            // 仅当外层手势禁用时（多选≥2项），内层处理点击
                                            if (multiSelectMode && selectedPaths.size > 1) {
                                                val wasSelected = song.path in selectedPaths
                                                selectedPaths = if (wasSelected) selectedPaths - song.path
                                                else selectedPaths + song.path
                                            }
                                            // 其他情况：外层 awaitEachGesture 已处理（tap 播放 / tap 选中）
                                        }
                                        // onLongPress 默认 null，长按全部由父层 awaitEachGesture 处理
                                    )
                                    if (!multiSelectMode) {
                                        Icon(
                                            Icons.Default.DragHandle, "拖拽排序",
                                            tint = BreezyTheme.colors.onSurface.copy(alpha = 0.25f),
                                            modifier = Modifier.align(Alignment.CenterVertically)
                                                .padding(end = 4.dp).size(22.dp)
                                        )
                                    }
                                }
                            }
                            } // outer Box (zIndex)
                        } // itemsIndexed
                    } // LazyColumn

                if (displayQuery.isEmpty() && bucketIndex.isNotEmpty()) {
                    QuickScrollBar(
                        modifier = Modifier.matchParentSize(),
                        bucketLabels = SongSortUtil.allBucketLabels,
                        bucketIndex = bucketIndex,
                        totalItems = total,
                        scrollRatio = scrollRatio,
                        trackHeightDp = trackH,
                        onScrollToIndex = { idx ->
                            scope.launch { lazyListState.scrollToItem(idx.coerceIn(0, filteredSongs.lastIndex)) }
                        },
                        firstCharAt = { idx -> filteredSongs.getOrNull(idx)?.title?.take(1) ?: "" },
                        pinyinAt = { idx -> SongSortUtil.getPinyin(filteredSongs.getOrNull(idx)?.title ?: "") }
                    )
                }
            }
        }

        // 多选工具栏
        setDock(
            if (multiSelectMode) {
                {
                    MultiSelectDock(
                        onCancel = { multiSelectMode = false; selectedPaths = emptySet() },
                        actions = {
                            // 添加到播放队列
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val toAdd = selectedPaths.mapNotNull { sp -> songs.find { it.path == sp } }
                                    withContext(Dispatchers.Main) {
                                        PlaybackState.appendSongs(toAdd)
                                        multiSelectMode = false; selectedPaths = emptySet()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Add, "添加到播放列表",
                                    tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                            }
                            // 从歌单中删除
                            IconButton(onClick = {
                                showDeleteConfirm = true
                            }) {
                                Icon(Icons.Default.Delete, "从歌单删除",
                                    tint = BreezyTheme.colors.error, modifier = Modifier.size(22.dp))
                            }
                        }
                    )
                }
            } else null
        )

        // 删除确认弹窗
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = BreezyTheme.colors.surface,
                title = { Text("确认删除", color = BreezyTheme.colors.onSurface) },
                text = { Text("确定要从歌单中删除选中的 ${selectedPaths.size} 首歌曲吗？\n此操作不可撤销。",
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f)) },
                confirmButton = {
                    TextButton(onClick = {
                        val toRemove = selectedPaths
                        songs = songs.filter { it.path !in toRemove }
                        val newSongs = songs
                        onSongsChanged(newSongs)
                        scope.launch(Dispatchers.IO) {
                            writePlaylistOrder(context, playlist.uri, newSongs)
                            Preferences.setPlaylistSongsCache(context, playlist.uri,
                                newSongs.map { it.path })
                        }
                        multiSelectMode = false; selectedPaths = emptySet()
                        showDeleteConfirm = false
                    }) {
                        Text("删除", color = BreezyTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                }
            )
        }
    } // end PlaylistDetailView
}
