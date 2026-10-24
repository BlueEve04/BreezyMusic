package com.breezy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
private val savedSelectedAlbum = mutableStateOf<AlbumCover?>(null)

/**
 * 专辑页面 —— 圆角正方形网格
 * 点击专辑进入该专辑歌曲列表
 */
@Composable
fun AlbumsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current

    // 专辑列表（album → 该专辑下第一首歌的path用于封面）
    var albumCovers by remember { mutableStateOf<List<AlbumCover>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 网格大小
    var gridSize by remember { mutableStateOf(Preferences.getAlbumGridSize(context)) }
    var showSizeDialog by remember { mutableStateOf(false) }

    // 搜索
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 详情导航（文件级持久化）
    var selectedAlbum by savedSelectedAlbum

    // 多选
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler(enabled = multiSelectMode
        && Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedAlbums = emptySet()
    }

    // 加载专辑
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = MusicDatabase.getInstance(context).musicDao()
            val all = dao.getAllSongs()
            // 按专辑分组，拼音排序
            albumCovers = all.groupBy { it.album.ifBlank { "未知专辑" } }
                .map { (album, songs) ->
                    AlbumCover(
                        name = album,
                        coverPath = songs.firstOrNull()?.path ?: "",
                        songCount = songs.size,
                        artist = songs.firstOrNull()?.artist ?: ""
                    )
                }
                .sortedWith(compareBy(SongSortUtil.stringComparator) { it.name })
        }
        loading = false
    }

    // 过滤
    val filteredAlbums = if (displayQuery.isNotEmpty())
        albumCovers.filter { it.name.contains(displayQuery, ignoreCase = true) }
    else albumCovers

    // 桶索引
    val bucketIndex = remember(filteredAlbums) {
        SongSortUtil.buildBucketIndex(filteredAlbums.map { it.name })
    }

    // 专辑详情页
    if (selectedAlbum != null) {
        AlbumDetailView(
            album = selectedAlbum!!,
            onBack = { selectedAlbum = null },
            multiSelectMode = multiSelectMode,
            selectedPaths = selectedAlbums,
            onSelectionChange = { selectedAlbums = it }
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
                    if (multiSelectMode) { multiSelectMode = false; selectedAlbums = emptySet() }
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
                                    BreezyCustomText("搜索专辑...", fontSize = 14.sp,
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
                    BreezyCustomText("专辑", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f))
                    if (!multiSelectMode) {
                        BreezyCustomText("${filteredAlbums.size} 张", fontSize = 13.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 4.dp))
                        // 布局大小
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
            } else if (filteredAlbums.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("暂无专辑", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val trackH = maxHeight
                    val cellSize = when (gridSize) {
                        "small" -> 100.dp; "large" -> 170.dp; "xlarge" -> 210.dp
                        else -> 130.dp // medium
                    }
                    val spacing = 10.dp
                    val padding = 12.dp
                    val raw = ((maxWidth - padding * 2 + spacing) / (cellSize + spacing)).toInt()
                    val gridCols = if (raw < 2) 2 else raw
                    val gridState = rememberLazyGridState()
                    val total = filteredAlbums.size
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
                        items(filteredAlbums, key = { it.name }) { album ->
                            AlbumGridItem(
                                album = album,
                                size = cellSize,
                                isSelected = album.name in selectedAlbums,
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) {
                                        selectedAlbums = if (album.name in selectedAlbums)
                                            selectedAlbums - album.name
                                        else selectedAlbums + album.name
                                    } else {
                                        selectedAlbum = album
                                    }
                                },
                                onLongPress = {
                                    if (!multiSelectMode) {
                                        multiSelectMode = true
                                        selectedAlbums = setOf(album.name)
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
                                filteredAlbums.getOrNull(idx)?.name?.take(1) ?: ""
                            },
                            pinyinAt = { idx ->
                                SongSortUtil.getPinyin(filteredAlbums.getOrNull(idx)?.name ?: "")
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
                        onCancel = { multiSelectMode = false; selectedAlbums = emptySet() },
                        actions = {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val dao = MusicDatabase.getInstance(context).musicDao()
                                    val toAdd = mutableListOf<SongEntity>()
                                    selectedAlbums.forEach { albumName ->
                                        toAdd.addAll(dao.getSongsByAlbum(albumName))
                                    }
                                    withContext(Dispatchers.Main) {
                                        PlaybackState.appendSongs(toAdd)
                                        multiSelectMode = false; selectedAlbums = emptySet()
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

        // 网格大小弹窗
        if (showSizeDialog) {
            GridSizeDialog(
                current = gridSize,
                onSelect = { size ->
                    gridSize = size
                    Preferences.setAlbumGridSize(context, size)
                    showSizeDialog = false
                },
                onDismiss = { showSizeDialog = false }
            )
        }
    }
}

// ═══════════════════════════════════════
// 网格项
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridItem(
    album: AlbumCover,
    size: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    multiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    var coverBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(album.coverPath) {
        withContext(Dispatchers.IO) {
            coverBitmap = album.coverPath.takeIf { it.isNotEmpty() }
                ?.let { AlbumArtCache.get(context, it) }
        }
    }

    Column(
        modifier = Modifier.width(size)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面区域：圆角正方形
        Box(
            modifier = Modifier.size(size)
                .clip(RoundedCornerShape(14.dp))
                .background(BreezyTheme.colors.onSurface.copy(alpha = 0.08f))
                .then(
                    if (isSelected) Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(BreezyTheme.colors.primary.copy(alpha = 0.3f))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (coverBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverBitmap!!,
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.Album, "专辑",
                    tint = BreezyTheme.colors.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(size * 0.45f)
                )
            }
            // 多选勾选
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle, "已选",
                    tint = BreezyTheme.colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 专辑名
        BreezyCustomText(
            text = album.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BreezyTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(size)
        )

        // 歌手 + 歌曲数
        val info = buildString {
            if (album.artist.isNotEmpty()) append(album.artist)
            if (album.songCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("${album.songCount}首")
            }
        }
        if (info.isNotEmpty()) {
            BreezyCustomText(
                text = info,
                fontSize = 11.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(size)
            )
        }
    }
}

// ═══════════════════════════════════════
// 专辑详情（子页面）
// ═══════════════════════════════════════

@Composable
private fun AlbumDetailView(
    album: AlbumCover,
    onBack: () -> Unit,
    multiSelectMode: Boolean,
    selectedPaths: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setDock = LocalMultiSelectDock.current
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val lazyListState = listState

    LaunchedEffect(album.name) {
        withContext(Dispatchers.IO) {
            songs = MusicDatabase.getInstance(context).musicDao()
                .getSongsByAlbum(album.name)
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
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                BreezyCustomText(album.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (album.artist.isNotEmpty()) {
                    BreezyCustomText(album.artist, fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            }
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
                                    onSelectionChange(
                                        if (song.path in selectedPaths) selectedPaths - song.path
                                        else selectedPaths + song.path
                                    )
                                } else PlaybackState.play(song)
                            },
                            onLongPress = {
                                if (!multiSelectMode) {
                                    onSelectionChange(setOf(song.path))
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
                        onCancel = { onSelectionChange(emptySet()) },
                        actions = {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val songsToAdd = selectedPaths.mapNotNull { sp ->
                                        songs.find { it.path == sp }
                                    }
                                    withContext(Dispatchers.Main) {
                                        PlaybackState.appendSongs(songsToAdd)
                                        onSelectionChange(emptySet())
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
// 网格大小弹窗
// ═══════════════════════════════════════

@Composable
fun GridSizeDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "small" to "小",
        "medium" to "中",
        "large" to "大",
        "xlarge" to "超大"
    )
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BreezyTheme.colors.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                BreezyCustomText("图标大小", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface)
                Spacer(Modifier.height(12.dp))
                options.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (key == current) BreezyTheme.colors.primary.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onSelect(key) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = key == current,
                            onClick = { onSelect(key) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = BreezyTheme.colors.primary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        BreezyCustomText(label, fontSize = 15.sp,
                            color = BreezyTheme.colors.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    BreezyCustomText("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    }
}

// ═══════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════

data class AlbumCover(
    val name: String,
    val coverPath: String,
    val songCount: Int,
    val artist: String
)
