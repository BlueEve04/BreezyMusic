package com.breezy.music.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.breezy.music.model.*
import com.breezy.music.util.SongSortUtil
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 引用 AllSongsPage 中的 SongItem（internal）
import com.breezy.music.ui.SongItem

// 离开页面再返回时保留状态
private val savedSelectedArtist = mutableStateOf<ArtistInfo?>(null)

@Composable
fun ArtistsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current

    var artists by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 网格大小（与专辑共享偏好）
    var gridSize by remember { mutableStateOf(Preferences.getArtistGridSize(context)) }
    var showSizeDialog by remember { mutableStateOf(false) }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 详情导航（文件级持久化）
    var selectedArtist by savedSelectedArtist

    // 多选
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedArtists by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 更换封面
    var showCoverDialog by remember { mutableStateOf(false) }
    var coverTargetArtist by remember { mutableStateOf<String?>(null) }
    var coverOptions by remember { mutableStateOf<List<String>>(emptyList()) }  // 该歌手所有专辑封面path
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val artist = coverTargetArtist ?: return@rememberLauncherForActivityResult
            Preferences.setArtistCover(context, artist, it.toString())
            // 刷新封面：重新加载歌手列表
            scope.launch(Dispatchers.IO) {
                val dao = MusicDatabase.getInstance(context).musicDao()
                val all = dao.getAllSongs()
                artists = all.groupBy { a -> a.artist.ifBlank { "未知歌手" } }
                    .map { (name, songs) ->
                        val albums = songs.map { a -> a.album }.distinct()
                        val randomAlbum = albums.randomOrNull()
                        val cp = songs.firstOrNull { a -> a.album == randomAlbum }?.path ?: ""
                        ArtistInfo(name, songs.size, albums.size, cp)
                    }
                    .sortedWith(compareBy(SongSortUtil.stringComparator) { it.name })
            }
        }
    }

    BackHandler(enabled = multiSelectMode
        && Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedArtists = emptySet()
    }

    // 加载歌手
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = MusicDatabase.getInstance(context).musicDao()
            val all = dao.getAllSongs()
            artists = all.groupBy { it.artist.ifBlank { "未知歌手" } }
                .map { (artist, songs) ->
                    val albums = songs.map { it.album }.distinct()
                    val randomAlbum = albums.randomOrNull()
                    val coverPath = if (randomAlbum != null)
                        songs.firstOrNull { it.album == randomAlbum }?.path ?: ""
                    else ""
                    ArtistInfo(
                        name = artist,
                        songCount = songs.size,
                        albumCount = albums.size,
                        coverPath = coverPath
                    )
                }
                .sortedWith(compareBy(SongSortUtil.stringComparator) { it.name })
        }
        loading = false
    }

    // 过滤
    val filteredArtists = if (displayQuery.isNotEmpty())
        artists.filter { it.name.contains(displayQuery, ignoreCase = true) }
    else artists

    // 桶索引
    val bucketIndex = remember(filteredArtists) {
        SongSortUtil.buildBucketIndex(filteredArtists.map { it.name })
    }

    // 歌手详情页
    if (selectedArtist != null) {
        ArtistDetailView(
            artist = selectedArtist!!,
            onBack = { selectedArtist = null }
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
                    if (multiSelectMode) { multiSelectMode = false; selectedArtists = emptySet() }
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
                                    BreezyCustomText("搜索歌手...", fontSize = 14.sp,
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
                    BreezyCustomText("歌手", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f))
                    if (!multiSelectMode) {
                        BreezyCustomText("${filteredArtists.size} 位", fontSize = 13.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp))
                        IconButton(onClick = { showSizeDialog = true }) {
                            Icon(Icons.Default.GridView, "布局",
                                tint = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { isSearching = true; searchQuery = ""; displayQuery = "" }) {
                            Icon(Icons.Default.Search, "搜索", tint = BreezyTheme.colors.onSurface)
                        }
                    }
                }
            }

            // ── 网格 ──
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("加载中...", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else if (filteredArtists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("暂无歌手", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val trackH = maxHeight
                    val cellSize = when (gridSize) {
                        "small" -> 90.dp; "large" -> 160.dp; "xlarge" -> 200.dp
                        else -> 120.dp
                    }
                    val spacing = 10.dp
                    val padding = 12.dp
                    val raw = ((maxWidth - padding * 2 + spacing) / (cellSize + spacing)).toInt()
                    val gridCols = if (raw < 2) 2 else raw
                    val gridState = rememberLazyGridState()
                    val total = filteredArtists.size
                    val colCount: Int = gridCols

                    val scrollRatio by remember(total, colCount) {
                        derivedStateOf {
                            if (total <= colCount) 0f
                            else {
                                val totalRows = (total + colCount - 1) / colCount
                                val currentRow = gridState.firstVisibleItemIndex / colCount
                                if (totalRows <= 1) 0f
                                else (currentRow.toFloat() / (totalRows - 1).toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    }

                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(gridCols),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(padding),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        items(filteredArtists, key = { it.name }) { artist ->
                            ArtistGridItem(
                                artist = artist,
                                size = cellSize,
                                isSelected = artist.name in selectedArtists,
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) {
                                        selectedArtists = if (artist.name in selectedArtists)
                                            selectedArtists - artist.name
                                        else selectedArtists + artist.name
                                    } else {
                                        selectedArtist = artist
                                    }
                                },
                                onLongPress = {
                                    if (!multiSelectMode) {
                                        multiSelectMode = true
                                        selectedArtists = setOf(artist.name)
                                    }
                                }
                            )
                        }
                    }

                    // 快速滑动条
                    if (displayQuery.isEmpty() && bucketIndex.isNotEmpty()) {
                        QuickScrollBar(
                            modifier = Modifier.matchParentSize(),
                            bucketLabels = SongSortUtil.allBucketLabels,
                            bucketIndex = bucketIndex,
                            totalItems = total,
                            scrollRatio = scrollRatio,
                            trackHeightDp = trackH,
                            onScrollToIndex = { idx ->
                                scope.launch { gridState.scrollToItem(idx.coerceIn(0, total - 1)) }
                            },
                            firstCharAt = { idx ->
                                filteredArtists.getOrNull(idx)?.name?.take(1) ?: ""
                            },
                            pinyinAt = { idx ->
                                SongSortUtil.getPinyin(filteredArtists.getOrNull(idx)?.name ?: "")
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
                        onCancel = { multiSelectMode = false; selectedArtists = emptySet() },
                        actions = {
                            // 添加到播放列表
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val dao = MusicDatabase.getInstance(context).musicDao()
                                    val toAdd = mutableListOf<SongEntity>()
                                    selectedArtists.forEach { artistName ->
                                        toAdd.addAll(dao.getSongsByArtist(artistName))
                                    }
                                    withContext(Dispatchers.Main) {
                                        PlaybackState.appendSongs(toAdd)
                                        multiSelectMode = false; selectedArtists = emptySet()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Add, "添加到播放列表",
                                    tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                            }
                            // 更换封面（仅选中1个歌手时显示）
                            if (selectedArtists.size == 1) {
                                IconButton(onClick = {
                                    val artistName = selectedArtists.first()
                                    coverTargetArtist = artistName
                                    // 收集该歌手所有专辑封面
                                    scope.launch(Dispatchers.IO) {
                                        val dao = MusicDatabase.getInstance(context).musicDao()
                                        val artistSongs = dao.getSongsByArtist(artistName)
                                        coverOptions = artistSongs
                                            .groupBy { it.album }
                                            .mapNotNull { (_, ss) -> ss.firstOrNull()?.path }
                                    }
                                    showCoverDialog = true
                                }) {
                                    Icon(Icons.Default.SwapHoriz, "更换封面",
                                        tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    )
                }
            } else null
        )

        // 网格大小弹窗
        if (showSizeDialog) {
            GridSizeDialog(
                current = gridSize,
                onSelect = { size ->
                    gridSize = size
                    Preferences.setArtistGridSize(context, size)
                    showSizeDialog = false
                },
                onDismiss = { showSizeDialog = false }
            )
        }

        // 更换封面弹窗
        if (showCoverDialog && coverTargetArtist != null) {
            CoverChangeDialog(
                artistName = coverTargetArtist!!,
                coverOptions = coverOptions,
                onPickLocal = { imagePicker.launch("image/*") },
                onPickAlbum = { path ->
                    Preferences.setArtistCover(context, coverTargetArtist!!, path)
                    showCoverDialog = false
                    // 刷新艺术家列表
                    scope.launch(Dispatchers.IO) {
                        val dao = MusicDatabase.getInstance(context).musicDao()
                        val all = dao.getAllSongs()
                        artists = all.groupBy { it.artist.ifBlank { "未知歌手" } }
                            .map { (name, songs) ->
                                val albums = songs.map { it.album }.distinct()
                                val randomAlbum = albums.randomOrNull()
                                val cp = songs.firstOrNull { it.album == randomAlbum }?.path ?: ""
                                ArtistInfo(name, songs.size, albums.size, cp)
                            }
                            .sortedWith(compareBy(SongSortUtil.stringComparator) { it.name })
                    }
                },
                onDismiss = { showCoverDialog = false }
            )
        }
    }
}

// ═══════════════════════════════════════
// 歌手网格项（正圆）
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistGridItem(
    artist: ArtistInfo,
    size: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    multiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    // 封面来源：自定义 > 随机专辑封面
    val customCover = Preferences.getArtistCover(context, artist.name)
    val effectivePath = customCover?.takeIf { it.isNotEmpty() } ?: artist.coverPath
    val isCustom = customCover?.isNotEmpty() == true
    var coverBitmap by remember(effectivePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(effectivePath) {
        if (effectivePath.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                coverBitmap = try {
                    if (isCustom) {
                        // 自定义封面：直接从 content:// URI 加载
                        context.contentResolver.openInputStream(Uri.parse(effectivePath))?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    } else {
                        AlbumArtCache.get(context, effectivePath)
                    }
                } catch (_: Exception) { null }
            }
        }
    }

    Column(
        modifier = Modifier.width(size)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 正圆头像 + 勾选标记（勾选在圆形外部，避免被 clip 剪裁）
        Box(modifier = Modifier.size(size)) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        if (isSelected) BreezyTheme.colors.primary.copy(alpha = 0.35f)
                        else BreezyTheme.colors.primary.copy(alpha = 0.12f)
                    )
                    .then(
                        if (isSelected) Modifier.border(3.dp, BreezyTheme.colors.primary, CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap!!,
                        contentDescription = artist.name,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val firstChar = artist.name.firstOrNull()?.uppercase() ?: "?"
                    val charSize = when {
                        size >= 160.dp -> 56.sp
                        size >= 120.dp -> 44.sp
                        else -> 32.sp
                    }
                    BreezyCustomText(
                        text = firstChar,
                        fontSize = charSize,
                        fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.primary
                    )
                }
            }
            // 勾选标记在圆形外部（顶层，不被 clip 影响）
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle, "已选",
                    tint = BreezyTheme.colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 歌手名
        BreezyCustomText(
            text = artist.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BreezyTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(size)
        )

        // 歌曲数
        BreezyCustomText(
            text = "${artist.songCount}首 · ${artist.albumCount}专辑",
            fontSize = 11.sp,
            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(size)
        )
    }
}

// ═══════════════════════════════════════
// 更换封面弹窗
// ═══════════════════════════════════════

@Composable
private fun CoverChangeDialog(
    artistName: String,
    coverOptions: List<String>,
    onPickLocal: () -> Unit,
    onPickAlbum: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BreezyTheme.colors.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                BreezyCustomText("更换封面 - $artistName", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = BreezyTheme.colors.onSurface)
                Spacer(Modifier.height(16.dp))

                // 选项1：从本地选择
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPickLocal(); onDismiss() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Image, null, tint = BreezyTheme.colors.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    BreezyCustomText("从本地选择照片", fontSize = 14.sp, color = BreezyTheme.colors.onSurface)
                }

                Spacer(Modifier.height(8.dp))

                // 选项2：从专辑封面选择
                if (coverOptions.isNotEmpty()) {
                    BreezyCustomText("或选择专辑封面：", fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp))

                    // 可滚动的缩略图网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 180.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(coverOptions) { path ->
                            var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(path) {
                                withContext(Dispatchers.IO) {
                                    thumb = AlbumArtCache.get(context, path)
                                }
                            }
                            Box(
                                modifier = Modifier.aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BreezyTheme.colors.onSurface.copy(alpha = 0.08f))
                                    .clickable { onPickAlbum(path) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(bitmap = thumb!!, contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    BreezyCustomText("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// 歌手详情（子页面）
// ═══════════════════════════════════════

@Composable
private fun ArtistDetailView(
    artist: ArtistInfo,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()

    // 多选
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler(enabled = multiSelectMode
        && Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedPaths = emptySet()
    }

    LaunchedEffect(artist.name) {
        withContext(Dispatchers.IO) {
            songs = MusicDatabase.getInstance(context).musicDao()
                .getSongsByArtist(artist.name)
                .sortedWith(SongSortUtil.byTitle)
        }
        loading = false
    }

    val bucketIndex = remember(songs) { SongSortUtil.buildBucketIndex(songs) }

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
            Column(modifier = Modifier.weight(1f)) {
                BreezyCustomText(artist.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BreezyCustomText("${artist.songCount}首 · ${artist.albumCount}专辑", fontSize = 13.sp,
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
            if (!multiSelectMode) {
                BreezyCustomText("${songs.size} 首", fontSize = 13.sp,
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            if (Preferences.getFolderPlayReplace(context)) PlaybackState.playList(songs)
                            else PlaybackState.appendSongs(songs)
                        }
                    }
                }) {
                    Icon(Icons.Default.PlayArrow, "播放全部",
                        tint = BreezyTheme.colors.primary, modifier = Modifier.size(24.dp))
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("加载中...", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("暂无歌曲", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val trackH = maxHeight
                val total = songs.size
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
                    items(songs, key = { it.path }) { song ->
                        SongItem(
                            song = song,
                            isSelected = song.path in selectedPaths,
                            multiSelectMode = multiSelectMode,
                            onTap = {
                                if (multiSelectMode) {
                                    selectedPaths = if (song.path in selectedPaths)
                                        selectedPaths - song.path
                                    else selectedPaths + song.path
                                } else PlaybackState.play(song)
                            },
                            onLongPress = {
                                if (!multiSelectMode) {
                                    multiSelectMode = true
                                    selectedPaths = setOf(song.path)
                                }
                            }
                        )
                    }
                }

                if (bucketIndex.isNotEmpty()) {
                    QuickScrollBar(
                        modifier = Modifier.matchParentSize(),
                        bucketLabels = SongSortUtil.allBucketLabels,
                        bucketIndex = bucketIndex,
                        totalItems = total,
                        scrollRatio = scrollRatio,
                        trackHeightDp = trackH,
                        onScrollToIndex = { idx ->
                            scope.launch { lazyListState.scrollToItem(idx.coerceIn(0, songs.lastIndex)) }
                        },
                        firstCharAt = { idx -> songs.getOrNull(idx)?.title?.take(1) ?: "" },
                        pinyinAt = { idx -> SongSortUtil.getPinyin(songs.getOrNull(idx)?.title ?: "") }
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
                        }
                    )
                }
            } else null
        )
    }
}

// ═══════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════

data class ArtistInfo(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val coverPath: String = ""
)
