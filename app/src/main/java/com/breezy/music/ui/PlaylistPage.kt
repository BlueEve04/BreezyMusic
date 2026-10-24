package com.breezy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.util.Log
import com.breezy.music.model.AlbumArtCache
import com.breezy.music.model.CoverShape
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.PlaylistHistory
import com.breezy.music.model.SongEntity
import com.breezy.music.model.ThemeState
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "PlaylistPage"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistPage() {
    val context = LocalContext.current
    val songs = PlaybackState.playlist
    val currentIndex = PlaybackState.currentIndex

    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showHistory by remember { mutableStateOf(false) }

    if (showHistory) {
        BackHandler { showHistory = false }
        PlaylistHistoryPage(onBack = { showHistory = false })
        return
    }

    // 返回键：优先退出多选
    BackHandler(enabled = multiSelectMode
        && com.breezy.music.model.Preferences.getBackBehavior(context) == "smart") {
        multiSelectMode = false; selectedIndices = emptySet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreezyCustomText("播放列表", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f))
            BreezyCustomText("${songs.size} 首", fontSize = 13.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Default.History, "历史记录", tint = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = {
                if (songs.isNotEmpty()) {
                    PlaylistHistory.save(context, songs, PlaybackState.currentIndex)
                    PlaybackState.clear()
                }
            }) {
                Icon(Icons.Default.ClearAll, "清空", tint = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp))
            }
        }
        // 工具栏：去重 + 播放模式
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 去重
            TextButton(onClick = {
                val seen = mutableSetOf<String>()
                val deduped = songs.filter { seen.add(it.path) }
                if (deduped.size != songs.size) {
                    PlaybackState.playList(deduped, 0)
                }
            }) {
                Icon(Icons.Default.FilterList, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                BreezyCustomText("去重", fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f))
            }
            // 播放模式切换
            val modeIcon = when (PlaybackState.playMode) {
                1 -> Icons.Default.Shuffle
                2 -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            }
            val modeLabel = when (PlaybackState.playMode) {
                1 -> "随机"
                2 -> "单曲循环"
                else -> "顺序"
            }
            TextButton(onClick = {
                PlaybackState.playMode = (PlaybackState.playMode + 1) % 3
            }) {
                Icon(modeIcon, null, modifier = Modifier.size(16.dp),
                    tint = if (PlaybackState.playMode == 0) BreezyTheme.colors.onSurface.copy(alpha = 0.4f)
                    else BreezyTheme.colors.primary)
                Spacer(Modifier.width(2.dp))
                BreezyCustomText(modeLabel, fontSize = 12.sp,
                    color = if (PlaybackState.playMode == 0) BreezyTheme.colors.onSurface.copy(alpha = 0.4f)
                    else BreezyTheme.colors.primary)
            }
        }
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("播放列表为空", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else {
            // 拖拽状态
            var draggedIdx by remember { mutableStateOf<Int?>(null) }
            var dragOffsetY by remember { mutableStateOf(0f) }
            var draggedSongPath by remember { mutableStateOf<String?>(null) }
            var itemHeightPx by remember { mutableStateOf(60f) }
            var reorderVersion by remember { mutableIntStateOf(0) }
            var settling by remember { mutableStateOf(false) }
            var pendingReorder by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (from, to)
            val settleAnim = remember { Animatable(0f) }
            val lazyListState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // 拖拽目标索引（共享）
            val dragTargetIdx by remember {
                derivedStateOf {
                    if (draggedIdx != null) {
                        (draggedIdx!! + dragOffsetY / itemHeightPx.coerceAtLeast(1f)).roundToInt()
                            .coerceIn(0, songs.lastIndex)
                    } else null
                }
            }

            // 边缘自动滚动
            LaunchedEffect(draggedIdx) {
                if (draggedIdx == null) return@LaunchedEffect
                while (draggedIdx != null) {
                    val layout = lazyListState.layoutInfo
                    val vh = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                    if (vh <= 0f) { delay(16); continue }
                    val it = layout.visibleItemsInfo.find { it.index == draggedIdx }
                    val edgeSize = (itemHeightPx * 3f).coerceAtMost(vh * 0.40f)
                    val firstIdx = layout.visibleItemsInfo.first().index
                    val lastIdx = layout.visibleItemsInfo.last().index
                    if (it != null) {
                        val center = it.offset + it.size / 2f + dragOffsetY - layout.viewportStartOffset
                        if (center < 0f && firstIdx > 0) {
                            lazyListState.scroll { scrollBy(center) }
                        } else if (center > vh && lastIdx < songs.lastIndex) {
                            lazyListState.scroll { scrollBy(center - vh) }
                        } else if (center < edgeSize && firstIdx > 0) {
                            val factor = (1f - center / edgeSize).coerceIn(0.1f, 1f)
                            lazyListState.scroll { scrollBy(-itemHeightPx * factor * 0.15f) }
                        } else if (center > vh - edgeSize && lastIdx < songs.lastIndex) {
                            val factor = (1f - (vh - center) / edgeSize).coerceIn(0.1f, 1f)
                            lazyListState.scroll { scrollBy(itemHeightPx * factor * 0.15f) }
                        }
                    } else {
                        if (draggedIdx!! <= firstIdx && firstIdx > 0)
                            lazyListState.scroll { scrollBy(-itemHeightPx * 0.25f) }
                        else if (draggedIdx!! >= lastIdx && lastIdx < songs.lastIndex)
                            lazyListState.scroll { scrollBy(itemHeightPx * 0.25f) }
                    }
                    delay(16)
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(songs) { index, song ->
                    val isDragging = draggedIdx == index

                    val visualOffsetY = if (isDragging) {
                        if (settling) settleAnim.value else dragOffsetY
                    } else if (draggedIdx != null && dragTargetIdx != null) {
                        val ti = dragTargetIdx!!
                        val target = when {
                            draggedIdx!! < ti && index in (draggedIdx!! + 1)..ti -> -itemHeightPx
                            draggedIdx!! > ti && index in ti..(draggedIdx!! - 1) -> +itemHeightPx
                            else -> 0f
                        }
                        animateFloatAsState(targetValue = target, animationSpec = tween(150)).value
                    } else {
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
                                    // 拖拽仅在非多选或多选≤1项时启用
                                    if (!multiSelectMode || selectedIndices.size <= 1)
                                        Modifier.pointerInput(multiSelectMode, selectedIndices.size, reorderVersion) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val downPos = down.position
                                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                                val touchSlop = viewConfiguration.touchSlop

                                                var isTap = false
                                                val timedOut = withTimeoutOrNull(longPressTimeout) {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (!change.pressed) {
                                                            if ((change.position - downPos).getDistance() < touchSlop)
                                                                isTap = true
                                                            break
                                                        }
                                                        if ((change.position - downPos).getDistance() > touchSlop)
                                                            break
                                                    }
                                                }

                                                if (isTap) {
                                                    // 消费事件，防止 inner combinedClickable 重复触发
                                                    down.consume()
                                                    if (multiSelectMode) {
                                                        selectedIndices = if (index in selectedIndices) selectedIndices - index
                                                        else selectedIndices + index
                                                    } else {
                                                        PlaybackState.currentIndex = index
                                                        PlaybackState.play(song)
                                                    }
                                                    return@awaitEachGesture
                                                }
                                                if (timedOut != null) {
                                                    Log.d(TAG, "★ longpress cancelled (timedOut) idx=$index")
                                                    return@awaitEachGesture
                                                }
                                                // ── 长按触发拖拽 ──
                                                down.consume()
                                                Log.d(TAG, "★ DRAG START: index=$index song=${song.title} multiSelect=$multiSelectMode selectedSize=${selectedIndices.size}")
                                                if (!multiSelectMode) {
                                                    multiSelectMode = true
                                                    selectedIndices = setOf(index)
                                                    Log.d(TAG, "  → entered multiSelect, selected=[${index}]")
                                                }
                                                draggedSongPath = song.path
                                                draggedIdx = index; dragOffsetY = 0f
                                                drag(down.id) { change ->
                                                    change.consume()
                                                    dragOffsetY += change.position.y - change.previousPosition.y
                                                }
                                                // ── 拖拽结束：彻底释放，无残留状态 ──
                                                val dropOffset = dragOffsetY
                                                val dropFrom = songs.indexOfFirst { it.path == draggedSongPath }
                                                Log.d(TAG, "★ DRAG END: from=$dropFrom offset=$dropOffset itemH=$itemHeightPx path=$draggedSongPath")

                                                if (dropFrom >= 0 && abs(dropOffset) > 40f) {
                                                    val steps = (dropOffset / itemHeightPx.coerceAtLeast(1f)).roundToInt()
                                                    val to = (dropFrom + steps).coerceIn(0, songs.lastIndex)
                                                    if (to != dropFrom) {
                                                        pendingReorder = Pair(dropFrom, to)  // 延迟 commit
                                                    }
                                                }
                                                // ── 平滑归位到空槽（目标偏移 = (to-from)*itemHeight，直接滑入避让空位） ──
                                                settling = true
                                                val finalOffset = dragOffsetY
                                                val targetOffset = if (pendingReorder != null) {
                                                    (pendingReorder!!.second - pendingReorder!!.first).toFloat() * itemHeightPx
                                                } else 0f
                                                scope.launch {
                                                    settleAnim.snapTo(finalOffset)
                                                    settleAnim.animateTo(targetOffset, tween(180))
                                                    pendingReorder?.let { (from, to) ->
                                                        pendingReorder = null
                                                        val playingPath = songs.getOrNull(PlaybackState.currentIndex)?.path
                                                        val newList = songs.toMutableList().apply { add(to, removeAt(from)) }
                                                        val newIdx = newList.indexOfFirst { it.path == playingPath }.let { if (it >= 0) it else 0 }
                                                        PlaybackState.reorderPlaylist(newList, newIdx)
                                                        selectedIndices = setOf(to)
                                                        reorderVersion++
                                                    }
                                                    settling = false
                                                    draggedIdx = null; dragOffsetY = 0f; draggedSongPath = null
                                                }
                                            }
                                        }
                                    else Modifier
                                )
                        ) {
                            // 拖拽可用时由外层手势处理，SongItem 不收长按；仅多选>1时内层处理点击
                            val dragEnabled = !multiSelectMode || selectedIndices.size <= 1
                            PlaylistSongItem(
                                song = song, isPlaying = index == currentIndex,
                                isSelected = index in selectedIndices, multiSelectMode = multiSelectMode,
                                onTap = if (!dragEnabled) {
                                    {
                                        selectedIndices = if (index in selectedIndices) selectedIndices - index
                                        else selectedIndices + index
                                    }
                                } else {
                                    {} // 外层手势已处理
                                },
                                onLongPress = null // 拖拽手势由外层 Box 统一管理
                            )
                        }
                    }
                }
            }
        }
        } // end Column
        // 底部多选工具栏
        if (multiSelectMode) {
            MultiSelectDock(
                onCancel = { multiSelectMode = false; selectedIndices = emptySet() },
                actions = {
                    IconButton(onClick = {
                        val toRemove = selectedIndices.sortedDescending()
                        val newList = songs.toMutableList()
                        toRemove.forEach { newList.removeAt(it) }
                        PlaybackState.playList(newList, 0)
                        multiSelectMode = false; selectedIndices = emptySet()
                    }) {
                        Icon(Icons.Default.Delete, "删除选中", tint = BreezyTheme.colors.error)
                    }
                }
            )
        }
    } // end Box
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistSongItem(
    song: SongEntity, isPlaying: Boolean, isSelected: Boolean,
    multiSelectMode: Boolean, onTap: () -> Unit, onLongPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(song.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(song.path) { withContext(Dispatchers.IO) { bitmap = AlbumArtCache.get(context, song.path) } }

    val isActuallyPlaying = isPlaying && PlaybackState.isPlaying
    val bgColor = if (isPlaying) BreezyTheme.colors.primary.copy(alpha = 0.2f)
    else if (isSelected) BreezyTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent
    val ph = remember(song.album) { Color.hsl((song.album.hashCode() and 0xFF) * 360f / 256f, 0.4f, 0.55f) }

    // ── 播放指示器脉冲动画 ──
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
    )

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bgColor)
        .combinedClickable(onClick = onTap, onLongClick = onLongPress).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val artShape = when (ThemeState.coverShape) { CoverShape.Circle -> CircleShape; CoverShape.RoundedRect -> RoundedCornerShape(8.dp) }
        Box(Modifier.size(44.dp).clip(artShape).background(if (bitmap != null) Color.Transparent else ph), contentAlignment = Alignment.Center) {
            if (bitmap != null) androidx.compose.foundation.Image(bitmap!!, null, Modifier.fillMaxSize().clip(artShape))
            else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            // ── 正在播放指示器：播放时脉冲动画，暂停时静态 ──
            if (isPlaying) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, contentDescription = if (isActuallyPlaying) "正在播放" else "已暂停",
                        tint = BreezyTheme.colors.primary,
                        modifier = Modifier.size((if (isActuallyPlaying) 28f * pulseScale else 26f).dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BreezyCustomText(song.title, fontSize = 15.sp, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isPlaying) BreezyTheme.colors.primary else BreezyTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            BreezyCustomText(song.artist, fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val sec = song.duration / 1000
        BreezyCustomText("${sec/60}:${(sec%60).toString().padStart(2,'0')}", fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f))
    }
}
