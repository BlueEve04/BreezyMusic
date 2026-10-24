package com.breezy.music.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.breezy.music.model.*
import com.breezy.music.util.SongSortUtil
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 持久化导航栈：离开文件夹页面再返回时保留路径
private val savedNavStack = mutableStateListOf<Uri?>(null)

/**
 * 文件夹层级浏览页面
 *
 * ── 根目录 ──
 * 显示所有已添加的库文件夹（一级菜单）
 *
 * ── 子目录 ──
 * 点击文件夹进入，显示直接子项：
 * - 文件夹：图标 + 文件夹名称（主标题）+ 路径（副标题）
 * - 音乐文件：与 AllSongsPage 相同的列表项样式
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 导航栈：Uri 列表，最后一个为当前目录；null 层级 = 根目录
    // 使用 savedNavStack 确保离开页面再回来保留路由
    val navStack = savedNavStack
    if (navStack.isEmpty()) navStack.add(null)
    val currentUri = navStack.lastOrNull()  // null = 根目录

    // 根文件夹列表（从 DB）
    var rootFolders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    // 当前目录子项
    var children by remember { mutableStateOf<List<ChildItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 多选状态
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var multiSelectMode by remember { mutableStateOf(false) }

    // 搜索状态（与 AllSongsPage 一致）
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayQuery by remember { mutableStateOf("") }
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }

    // 首字母桶索引 + LazyList 滚动状态
    val filteredChildren = if (displayQuery.isNotEmpty())
        children.filter { it.name.contains(displayQuery, ignoreCase = true) }
    else children
    val bucketIndex = remember(filteredChildren) {
        filteredChildren.mapIndexedNotNull { idx, item ->
            val key = item.song?.title ?: item.name  // 音乐文件按标签title分桶，文件夹按名称
            val label = SongSortUtil.getBucketLabel(key)
            label to idx
        }.distinctBy { it.first }.toMap()
    }
    val foldersListState = key(currentUri) { rememberLazyListState() }

    // 返回键：优先退出多选 → 子目录回上级 → 根目录退出
    BackHandler(enabled = com.breezy.music.model.Preferences.getBackBehavior(context) == "smart") {
        when {
            multiSelectMode -> { multiSelectMode = false; selectedPaths = emptySet() }
            navStack.size > 1 -> navStack.removeAt(navStack.lastIndex)
            else -> onBack()
        }
    }

    // 加载根文件夹
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootFolders = MusicDatabase.getInstance(context).musicDao().getAllFolders()
        }
    }

    // 加载当前目录子项
    LaunchedEffect(currentUri) {
        if (currentUri == null) return@LaunchedEffect
        val uri = currentUri
        loading = true
        withContext(Dispatchers.IO) {
            try {
                val doc = DocumentFile.fromTreeUri(context, uri)
                val dao = MusicDatabase.getInstance(context).musicDao()
                val allSongs = dao.getAllSongs() // 缓存一次查询
                val list = doc?.listFiles()?.mapNotNull { file ->
                    if (file.isDirectory) {
                        ChildItem(
                            name = file.name ?: "未知文件夹",
                            path = file.uri.toString(),
                            isFolder = true,
                            uri = file.uri
                        )
                    } else if (file.name?.let { MusicRepository.isMusicFile(it) } == true) {
                        val songEntity = allSongs.find { it.path == file.uri.toString() }
                        ChildItem(
                            name = file.name ?: "未知文件",
                            path = file.uri.toString(),
                            isFolder = false,
                            uri = file.uri,
                            song = songEntity
                        )
                    } else null
                }?.sortedWith(
                    compareBy<ChildItem> { !it.isFolder }
                        .thenBy { it.name }
                ) ?: emptyList()
                // 多语言排序：文件夹优先 → 名称拼音排序
                children = list.sortedWith(Comparator { a, b ->
                    val folderCmp = a.isFolder.compareTo(b.isFolder) // false(文件夹在前) < true(文件)
                    if (folderCmp != 0) folderCmp
                    else {
                        // 音乐文件按元数据 title 排序，文件夹按名称排序
                        val aKey = a.song?.title ?: a.name
                        val bKey = b.song?.title ?: b.name
                        SongSortUtil.stringComparator.compare(aKey, bKey)
                    }
                })
            } catch (e: Exception) {
                Log.w("FoldersPage", "Error listing ${uri}", e)
                children = emptyList()
            }
        }
        loading = false
    }

    // 播放设置
    val folderPlayStrategy = remember { mutableStateOf(Preferences.getFolderPlayStrategy(context)) }
    val folderPlayReplace = remember { mutableStateOf(Preferences.getFolderPlayReplace(context)) }

    // 播放单曲
    fun playSong(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val song = MusicDatabase.getInstance(context).musicDao()
                .getAllSongs().find { it.path == uri.toString() }
            if (song != null) {
                withContext(Dispatchers.Main) { PlaybackState.play(song) }
            }
        }
    }

    // 播放整个文件夹（将点击的单曲置顶）
    fun playFolder(dirUri: Uri, clickedUri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val doc = DocumentFile.fromTreeUri(context, dirUri)
                    ?: DocumentFile.fromSingleUri(context, dirUri)
                val songs = mutableListOf<SongEntity>()
                if (doc != null && doc.isDirectory) {
                    doc.listFiles().forEach { file ->
                        if (!file.isDirectory && MusicRepository.isMusicFile(file.name ?: "")) {
                            val s = MusicDatabase.getInstance(context).musicDao()
                                .getAllSongs().find { it.path == file.uri.toString() }
                            if (s != null) songs.add(s)
                        }
                    }
                }
                // 将点击的项目置顶
                val clicked = songs.find { it.path == clickedUri.toString() }
                if (clicked != null) {
                    songs.remove(clicked)
                    songs.add(0, clicked)
                }
                withContext(Dispatchers.Main) {
                    if (folderPlayReplace.value) {
                        PlaybackState.playList(songs)
                    } else {
                        PlaybackState.appendSongs(songs)
                    }
                }
            } catch (e: Exception) {
                Log.w("FoldersPage", "Error playing folder", e)
            }
        }
    }

    // ── UI ──
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(BreezyTheme.colors.background)) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 44.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (multiSelectMode) {
                    multiSelectMode = false; selectedPaths = emptySet()
                } else if (navStack.size > 1) {
                    navStack.removeAt(navStack.lastIndex)
                } else onBack()
            }) {
                Icon(
                    if (navStack.size > 1) Icons.Default.ArrowBack else Icons.Default.ArrowBack,
                    "返回", tint = BreezyTheme.colors.onSurface
                )
            }
            if (isSearching) {
                // 搜索框
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { q ->
                        searchQuery = q
                        searchJob.value?.cancel()
                        searchJob.value = scope.launch {
                            delay(300)
                            displayQuery = q
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = BreezyTheme.colors.onSurface
                    ),
                    cursorBrush = SolidColor(BreezyTheme.colors.primary),
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
                                BreezyCustomText("搜索文件名...", fontSize = 14.sp,
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
                    text = if (currentUri == null) "文件夹"
                    else DocumentFile.fromSingleUri(context, currentUri)?.name ?: "子目录",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!multiSelectMode) {
                    IconButton(onClick = { isSearching = true; searchQuery = ""; displayQuery = "" }) {
                        Icon(Icons.Default.Search, "搜索", tint = BreezyTheme.colors.onSurface)
                    }
                }
            }
        }

        // 内容
        if (currentUri == null) {
            // ── 根目录：库文件夹列表 ──
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rootFolders) { folder ->
                    val folderName = try {
                        DocumentFile.fromTreeUri(context, Uri.parse(folder.path))?.name
                    } catch (e: Exception) { null } ?: folder.path.substringAfterLast('/')
                    FolderRootItem(
                        name = folderName,
                        path = folder.path,
                        isSelected = selectedPaths.contains(folder.path),
                        onClick = {
                            if (multiSelectMode) {
                                selectedPaths = if (selectedPaths.contains(folder.path))
                                    selectedPaths - folder.path
                                else selectedPaths + folder.path
                            } else {
                                try {
                                    val uri = Uri.parse(folder.path)
                                    navStack.add(uri)
                                } catch (e: Exception) {
                                    Log.w("FoldersPage", "Cannot parse URI: ${folder.path}", e)
                                }
                            }
                        },
                        onLongPress = {
                            if (!multiSelectMode) {
                                multiSelectMode = true
                                selectedPaths = setOf(folder.path)
                            }
                        }
                    )
                }
            }
        } else if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("加载中...", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else {
            // ── 子目录：文件+文件夹列表 ──
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val trackH = maxHeight
                val total = filteredChildren.size
                // 平滑滚动比例：使用 firstVisibleItemScrollOffset 实现亚像素级过渡
                val scrollRatio by remember(total) {
                    derivedStateOf {
                        val visible = foldersListState.layoutInfo.visibleItemsInfo
                        if (visible.isEmpty() || total <= visible.size) {
                            0f
                        } else {
                            val firstIdx = foldersListState.firstVisibleItemIndex
                            val offset = foldersListState.firstVisibleItemScrollOffset
                            val firstH = visible.first().size
                            val frac = if (firstH > 0) offset.toFloat() / firstH else 0f
                            ((firstIdx + frac) / (total - visible.size)).coerceIn(0f, 1f)
                        }
                    }
                }

                LazyColumn(
                    state = foldersListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredChildren) { child ->
                        if (child.isFolder) {
                            FolderChildItem(
                                child = child,
                                onClick = {
                                    try {
                                        navStack.add(child.uri)
                                    } catch (e: Exception) {}
                                },
                                onLongPress = {
                                    multiSelectMode = true
                                    selectedPaths = selectedPaths + child.path
                                }
                            )
                        } else {
                            MusicChildItem(
                                child = child,
                                isSelected = selectedPaths.contains(child.path),
                                onTap = {
                                    if (multiSelectMode) {
                                        selectedPaths = if (selectedPaths.contains(child.path))
                                            selectedPaths - child.path
                                        else selectedPaths + child.path
                                    } else {
                                        if (folderPlayStrategy.value == "folder") {
                                            val parentUri = currentUri
                                            if (parentUri != null) playFolder(parentUri, child.uri)
                                        } else {
                                            playSong(child.uri)
                                        }
                                    }
                                },
                                onLongPress = {
                                    multiSelectMode = true
                                    selectedPaths = selectedPaths + child.path
                                }
                            )
                        }
                    }
                }

                // 快速滑动条
                if (bucketIndex.isNotEmpty()) {
                    QuickScrollBar(
                        modifier = Modifier.matchParentSize(),
                        bucketLabels = SongSortUtil.allBucketLabels,
                        bucketIndex = bucketIndex,
                        totalItems = total,
                        scrollRatio = scrollRatio,
                        trackHeightDp = trackH,
                        onScrollToIndex = { index ->
                            scope.launch {
                                foldersListState.scrollToItem(index.coerceIn(0, filteredChildren.lastIndex))
                            }
                        },
                        firstCharAt = { idx ->
                            val item = filteredChildren.getOrNull(idx)
                            (item?.song?.title ?: item?.name ?: "").take(1)
                        },
                        pinyinAt = { idx ->
                            val item = filteredChildren.getOrNull(idx)
                            SongSortUtil.getPinyin(item?.song?.title ?: item?.name ?: "")
                        }
                    )
                }
            }
        }

        // 底部多选工具栏
        if (multiSelectMode) {
            MultiSelectDock(
                onCancel = { multiSelectMode = false; selectedPaths = emptySet() },
                actions = {
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val dao = MusicDatabase.getInstance(context).musicDao()
                            val allSongs = dao.getAllSongs()
                            val toAdd = mutableListOf<SongEntity>()
                            selectedPaths.forEach { folderPath ->
                                toAdd.addAll(allSongs.filter { it.folderPath == folderPath })
                            }
                            withContext(Dispatchers.Main) {
                                PlaybackState.appendSongs(toAdd)
                                multiSelectMode = false; selectedPaths = emptySet()
                            }
                        }
                    }) {
                        Icon(Icons.Default.PlaylistAdd, "添加文件夹歌曲",
                            tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
                    }
                }
            )
        }
    } // end Box
    }
}

// ═══════════════════════════════════════
// 数据模型
// ═══════════════════════════════════════

private data class ChildItem(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val uri: Uri,
    val song: SongEntity? = null  // 音乐文件的元数据
)

// ═══════════════════════════════════════
// 根目录文件夹卡片
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRootItem(
    name: String,
    path: String,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val bgColor = if (isSelected)
        BreezyTheme.colors.primary.copy(alpha = 0.12f)
    else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress ?: {})
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = BreezyTheme.colors.primary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            BreezyCustomText(
                text = name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = BreezyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BreezyCustomText(
                text = path,
                fontSize = 12.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = BreezyTheme.colors.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(24.dp)
        )
    }
}

// ═══════════════════════════════════════
// 子目录文件夹项
// ═══════════════════════════════════════

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FolderChildItem(
    child: ChildItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress ?: {})
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = BreezyTheme.colors.primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BreezyCustomText(
                text = child.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = BreezyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BreezyCustomText(
                text = child.path,
                fontSize = 11.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = BreezyTheme.colors.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(22.dp)
        )
    }
}

// ═══════════════════════════════════════
// 音乐文件项（与 AllSongsPage 同风格）
// ═══════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicChildItem(
    child: ChildItem,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val bgColor = if (isSelected)
        BreezyTheme.colors.primary.copy(alpha = 0.12f)
    else
        Color.Transparent

    val song = child.song
    val title = song?.title ?: child.name.substringBeforeLast('.')
    val artist = song?.artist ?: "未知艺术家"
    val duration = song?.duration ?: 0L

    // 专辑封面加载
    val context = LocalContext.current
    var coverBitmap by remember(child.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(child.path) {
        withContext(Dispatchers.IO) {
            coverBitmap = AlbumArtCache.get(context, child.path)
        }
    }
    val placeholderColor = remember(child.path) {
        val hash = child.path.hashCode()
        val hue = (hash and 0xFF) * 360f / 256f
        Color.hsl(hue, 0.4f, 0.55f)
    }
    val artShape = when (ThemeState.coverShape) {
        CoverShape.Circle -> CircleShape
        CoverShape.RoundedRect -> RoundedCornerShape(8.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 专辑封面 / 占位图标
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(artShape)
                .background(if (coverBitmap != null) Color.Transparent else placeholderColor),
            contentAlignment = Alignment.Center
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(artShape)
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            BreezyCustomText(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BreezyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            BreezyCustomText(
                text = artist,
                fontSize = 13.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        BreezyCustomText(
            text = formatDurationMs(duration),
            fontSize = 13.sp,
            color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f)
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
