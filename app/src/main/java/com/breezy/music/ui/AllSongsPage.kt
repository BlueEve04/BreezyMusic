package com.breezy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.SongEntity
import com.breezy.music.model.AlbumArtCache
import com.breezy.music.model.CoverShape
import com.breezy.music.model.ThemeState
import com.breezy.music.model.musicDao
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「所有歌曲」展开页面
 *
 * - 从 Room 加载全部歌曲，LazyColumn 高效渲染
 * - 长按进入多选模式（Checkbox 出现）
 * - 点击歌曲开始播放
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllSongsPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val setDock = LocalMultiSelectDock.current
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 多选状态
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 返回键：优先退出多选
    BackHandler(enabled = multiSelectMode
        && com.breezy.music.model.Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedPaths = emptySet()
    }

    // 搜索状态
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }  // 实际用于过滤的查询
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 异步加载歌曲列表（自动排序）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            songs = musicDao(context).getAllSongs()
                .sortedWith(com.breezy.music.util.SongSortUtil.byTitle)
        }
        loading = false
    }

    // 首字母桶索引（仅在非搜索模式下使用）
    val bucketIndex = remember(songs) {
        if (songs.isNotEmpty()) com.breezy.music.util.SongSortUtil.buildBucketIndex(songs)
        else emptyMap()
    }
    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏（仿设置页面：返回箭头 + 标题） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 48.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
                }
                if (isSearching) {
                    // 搜索框
                    val searchScope = rememberCoroutineScope()
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { q ->
                            searchQuery = q
                            searchJob.value?.cancel()
                            searchJob.value = searchScope.launch {
                                delay(300)
                                displayQuery = q
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            color = BreezyTheme.colors.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(BreezyTheme.colors.primary),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .focusRequester(focusRequester)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BreezyTheme.colors.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    BreezyCustomText("搜索标题...", fontSize = 14.sp,
                                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(horizontal = 12.dp))
                                }
                                inner()
                            }
                        }
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    IconButton(onClick = { searchQuery = ""; displayQuery = ""; isSearching = false }) {
                        Icon(Icons.Default.Close, "关闭", tint = BreezyTheme.colors.onSurface)
                    }
                } else {
                    BreezyCustomText(
                        "所有歌曲", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f)
                    )
                    if (!multiSelectMode) {
                        BreezyCustomText(
                            text = if (loading) "加载中..." else "${songs.size} 首",
                            fontSize = 13.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = { isSearching = true; searchQuery = ""; displayQuery = "" }) {
                        Icon(Icons.Default.Search, "搜索", tint = BreezyTheme.colors.onSurface)
                    }
                }
            }

            // 搜索过滤（保持排序顺序）
            val filteredSongs = if (displayQuery.isNotEmpty())
                songs.filter { it.title.contains(displayQuery, ignoreCase = true) }
            else songs

            // ── 列表 ──
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText("正在加载歌曲列表...",
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                }
            } else if (songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BreezyCustomText(
                        "暂无歌曲，请先在设置中添加音乐文件夹",
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val trackH = maxHeight
                    val total = filteredSongs.size
                    // 平滑滚动比例：使用 firstVisibleItemScrollOffset 实现亚像素级过渡
                    val scrollRatio by remember(total) {
                        derivedStateOf {
                            val visible = lazyListState.layoutInfo.visibleItemsInfo
                            if (visible.isEmpty() || total <= visible.size) {
                                0f
                            } else {
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
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredSongs,
                            key = { it.path }
                        ) { song ->
                            SongItem(
                                song = song,
                                isSelected = song.path in selectedPaths,
                                multiSelectMode = multiSelectMode,
                                onTap = {
                                    if (multiSelectMode) {
                                        selectedPaths = if (song.path in selectedPaths)
                                            selectedPaths - song.path
                                        else
                                            selectedPaths + song.path
                                    } else {
                                        PlaybackState.play(song)
                                    }
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

                    // 快速滑动条（仅非搜索模式显示）
                    // matchParentSize 确保与 LazyColumn 视口完全重合
                    if (displayQuery.isEmpty() && bucketIndex.isNotEmpty()) {
                        QuickScrollBar(
                            modifier = Modifier.matchParentSize(),
                            bucketLabels = com.breezy.music.util.SongSortUtil.allBucketLabels,
                            bucketIndex = bucketIndex,
                            totalItems = total,
                            scrollRatio = scrollRatio,
                            trackHeightDp = trackH,
                            onScrollToIndex = { index ->
                                scope.launch {
                                    lazyListState.scrollToItem(index.coerceIn(0, filteredSongs.lastIndex))
                                }
                            },
                            firstCharAt = { idx ->
                                filteredSongs.getOrNull(idx)?.title?.take(1) ?: ""
                            },
                            pinyinAt = { idx ->
                                com.breezy.music.util.SongSortUtil.getPinyin(filteredSongs.getOrNull(idx)?.title ?: "")
                            }
                        )
                    }
                }
            }

        // 底部多选工具栏
        setDock(
            if (multiSelectMode) {
                { MultiSelectDock(
                    onCancel = { multiSelectMode = false; selectedPaths = emptySet() },
                    actions = {
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val dao = musicDao(context)
                                val all = dao.getAllSongs()
                                val toAdd = all.filter { it.path in selectedPaths }
                                withContext(Dispatchers.Main) {
                                    PlaybackState.appendSongs(toAdd)
                                    multiSelectMode = false; selectedPaths = emptySet()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Add, "添加到播放列表",
                                tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                        }
                        AddToPlaylistButton(
                            songsToAdd = songs.filter { it.path in selectedPaths },
                            onDone = { multiSelectMode = false; selectedPaths = emptySet() }
                        )
                    }
                )}
            } else null
        )
    }
    }
}

// ═══════════════════════════════════════════
// 单首歌曲行
// ═══════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: SongEntity,
    isSelected: Boolean,
    multiSelectMode: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
val bgColor = if (isSelected)
        BreezyTheme.colors.primary.copy(alpha = 0.18f)
    else Color.Transparent

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 左：专辑封面占位 ──
            AlbumArtPlaceholder(song = song)

            Spacer(Modifier.width(12.dp))

            // ── 歌曲名 / 艺术家 （上下排布，一深一浅） ──
            Column(modifier = Modifier.weight(1f)) {
                BreezyCustomText(
                    text = song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = BreezyTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                BreezyCustomText(
                    text = song.artist,
                    fontSize = 13.sp,
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // ── 右侧：时长 ──
            BreezyCustomText(
                text = formatDuration(song.duration),
                fontSize = 13.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f)
            )
        }
    }

// ═══════════════════════════════════════════
// 专辑封面（即时 URI 读取 + 内存 LRU 缓存）
// ═══════════════════════════════════════════

@Composable
private fun AlbumArtPlaceholder(song: SongEntity) {
    val context = LocalContext.current
        var bitmap by remember(song.path) { mutableStateOf<ImageBitmap?>(null) }

        // 异步加载封面，不阻塞列表滚动
        LaunchedEffect(song.path) {
            withContext(Dispatchers.IO) {
                bitmap = AlbumArtCache.get(context, song.path)
            }
        }

        val placeholderColor = remember(song.album) {
            val hash = song.album.hashCode()
            val hue = (hash and 0xFF) * 360f / 256f
            Color.hsl(hue, 0.4f, 0.55f)
        }

        val artShape = when (ThemeState.coverShape) {
            CoverShape.Circle -> CircleShape
            CoverShape.RoundedRect -> RoundedCornerShape(8.dp)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(artShape)
                .background(if (bitmap != null) Color.Transparent else placeholderColor),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(artShape)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
