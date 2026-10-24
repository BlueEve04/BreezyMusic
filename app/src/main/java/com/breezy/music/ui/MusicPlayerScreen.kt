package com.breezy.music.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.breezy.music.model.AudioEngine
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.AlbumArtCache
import com.breezy.music.model.ThemeState
import com.breezy.music.model.CoverShape
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.text.BreezyText
import com.breezy.ui.text.BreezyTextLevel
import com.breezy.ui.theme.BreezyVisualStyle
import com.breezy.ui.theme.BreezySurfaceStyle
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToInt

/**
 * 音乐播放器主屏幕
 *
 * ── 交互 ──
 * - **点击** Dock / 展开区域：toggle 展开/收起（带动画）
 * - **上滑** Dock：手指跟随展开，松手后 snap 到最近状态
 * - **下滑** 展开区域：手指跟随收起
 *
 * ── 视觉 ──
 * - 封面为 **单例**，通过 `Modifier.offset()` 平滑位移 + 缩放，无跳变
 * - Dock 内容（文字/按钮）与展开内容交叉淡入淡出
 * - 卡片尺寸/位置/圆角同步插值
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicPlayerScreen(
    modifier: Modifier = Modifier
) {
    // ── 由手势 / 点击驱动的动画进度 0f=Dock, 1f=全屏 ──
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    /** 带动画过渡到目标进度 */
    fun animateTo(target: Float) {
        scope.launch {
            progress.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = 400,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    /** 点击切换 */
    fun toggle() {
        val target = if (progress.value < 0.2f) 1f else 0f
        animateTo(target)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val density = LocalDensity.current
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val screenHeightPx = with(density) { screenHeight.toPx() }

        // ── Dock 尺寸参数 ──
        val dockWidth = screenWidth - 32.dp
        val dockHeight = 72.dp
        val dockOffsetX = 16.dp
        val dockOffsetY = screenHeight - dockHeight - 24.dp

        // ── 实时读取 Animatable 值（derivedStateOf 避免不必要重算） ──
        val p by remember { derivedStateOf { progress.value } }

        // ── 音效面板的展开进度：0 = 底部悬浮条，1 = 整页 ──
        val effectsProgress = remember { Animatable(0f) }

        // 切歌时刷新底栏的技术参数（码率/采样率/声道）。它来自 MediaExtractor，
        // 不依赖 ExoPlayer 的轨道信息，因此暂停状态、甚至还没开始播也能正确显示。
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(PlaybackState.currentSong?.path) {
            PlaybackState.currentSong?.let { AudioEngine.refreshAudioInfo(appContext, it.path) }
        }

        // ── 像素级计算 ──
        val dockWPx = with(density) { dockWidth.toPx() }
        val dockHPx = with(density) { dockHeight.toPx() }
        val dockOxPx = with(density) { dockOffsetX.toPx() }
        val dockOyPx = with(density) { dockOffsetY.toPx() }
        val colors = BreezyTheme.colors

        // ── 内容渐显 ──
        val expandedContentAlpha by remember { derivedStateOf { if (p > 0.4f) (p - 0.4f) / 0.3f else 0f } }

        // ── 卡片左右飞入状态 ──
        val pagePosition = remember { Animatable(0f) }
        var dragStartPage by remember { mutableStateOf(0f) }
        val slidePx = screenWidthPx

        // ── 拖拽目标通道 ──
        // 旧实现每个 drag 事件都 scope.launch(Dispatchers.Main.immediate){ snapTo(...) }，
        // 一次滑动会创建几十上百个协程（每个都要排队、分配、被 MutatorMutex 取消），
        // 在中低端机上直接表现为手势发涩与掉帧。
        // 现在事件回调只写一个 StateFlow 的最新值，由单个常驻协程消费，
        // 每帧最多执行一次 snapTo，且天然合并中间事件。
        val progressDrag = remember { MutableStateFlow(Float.NaN) }
        val pageDrag = remember { MutableStateFlow(Float.NaN) }
        LaunchedEffect(Unit) {
            progressDrag.collect { target ->
                if (!target.isNaN()) progress.snapTo(target)
            }
        }
        LaunchedEffect(Unit) {
            pageDrag.collect { target ->
                if (!target.isNaN()) pagePosition.snapTo(target)
            }
        }

        // ── 返回键逻辑：子页面→播放器→收起Dock
        val playerBackContext = LocalContext.current
        BackHandler(enabled = p > 0.2f
            && com.breezy.music.model.Preferences.getBackBehavior(playerBackContext) == "smart") {
            if (pagePosition.value != 0f) {
                scope.launch { pagePosition.animateTo(0f, tween(250)) }
            } else {
                animateTo(0f)
            }
        }

        // ── 从 PlaybackState 读取当前歌曲信息 ──
        val currentSong = PlaybackState.currentSong
        val isPlayingState = PlaybackState.isPlaying
        val songTitle = currentSong?.title ?: "BreezyMusic"
        val songArtist = currentSong?.artist ?: "点击歌曲开始播放"
        val currentProgress = PlaybackState.progress
        val songDuration = currentSong?.duration ?: 0L

        // ── 卡片插值（derivedStateOf 缓存） ──
        val cardWidthDp by remember { derivedStateOf { lerpDp(dockWidth, screenWidth, p) } }
        val cardHeightDp by remember { derivedStateOf { lerpDp(dockHeight, screenHeight, p) } }
        val cardOffsetXDp by remember { derivedStateOf { lerpDp(dockOffsetX, 0.dp, p) } }
        val cardOffsetYDp by remember { derivedStateOf { lerpDp(dockOffsetY, 0.dp, p) } }
        val cardRadiusDp by remember { derivedStateOf { lerpDp(20.dp, 0.dp, p) } }
        val cardShadowDp by remember { derivedStateOf { lerpDp(12.dp, 0.dp, p) } }
        val cardBackground = lerpColor(colors.surface, colors.background, p)

        // ═══════════════════════════════════════
        // 播放卡片容器
        // ═══════════════════════════════════════
        Box(
            modifier = Modifier
                .offset(x = cardOffsetXDp, y = cardOffsetYDp)
                .size(cardWidthDp, cardHeightDp)
                .graphicsLayer {
                    val depth = abs(pagePosition.value).coerceIn(0f, 1f)
                    val scale = 1f - depth * 0.12f
                    scaleX = scale
                    scaleY = scale
                    translationY = depth * screenHeightPx * 0.04f
                    shadowElevation = with(density) { cardShadowDp.toPx() }
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                }
                .clip(RoundedCornerShape(cardRadiusDp))
                .BreezySurfaceStyle(
                    style = BreezyVisualStyle.Normal,
                    backgroundColor = cardBackground,
                    shape = RoundedCornerShape(cardRadiusDp)
                )
                // 点击展开（仅 dock 状态有效）
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (progress.value < 0.2f) animateTo(1f)
                        }
                    )
                }
                // 左右滑动翻页（播放器发起）
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragStartPage = pagePosition.value },
                        onDragEnd = {
                            scope.launch {
                                val disp = pagePosition.value - dragStartPage
                                val start = dragStartPage.roundToInt().coerceIn(-1, 1).toFloat()
                                val target = when {
                                    disp < -0.18f -> (start - 1).coerceIn(-1f, 1f)
                                    disp > 0.18f  -> (start + 1).coerceIn(-1f, 1f)
                                    else          -> start
                                }
                                pagePosition.animateTo(target, tween(250))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (effectsProgress.value <= 0.5f) {
                                pageDrag.value = (pagePosition.value - dragAmount / slidePx).coerceIn(-1f, 1f)
                            }
                        }
                    )
                }
                // 垂直拖拽展开/收起（跟手）
                .draggable(
                    state = rememberDraggableState { delta ->
                        // 音效页展开时播放页不响应纵向拖动：否则一次下拉会同时收掉音效页与播放页
                        if (effectsProgress.value <= 0.5f) {
                            val fraction = -delta / (screenHeightPx * 0.35f)
                            progressDrag.value = (progress.value + fraction).coerceIn(0f, 1f)
                        }
                    },
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStopped = {
                        if (effectsProgress.value <= 0.5f) {
                            val target = if (progress.value > 0.2f) 1f else 0f
                            animateTo(target)
                        }
                    }
                )
        ) {
            // ═══════════════════════════════════════
            // ── 专辑封面 ──
            // ═══════════════════════════════════════
            val coverSizeDp by remember { derivedStateOf { lerpDp(48.dp, 260.dp, p) } }
            val coverXDp by remember { derivedStateOf { lerpDp(12.dp, (screenWidth - 260.dp) / 2, p) } }
            val coverYDp by remember { derivedStateOf { lerpDp(12.dp, screenHeight * 0.18f, p) } }

            AlbumCover(
                size = coverSizeDp,
                songUri = currentSong?.path,
                modifier = Modifier.offset(x = coverXDp, y = coverYDp)
            )

            // ═══════════════════════════════════════
            // ── 标题 + 艺术家 ──
            // ═══════════════════════════════════════
            val titleDockY = 18.dp
            val titleExpandedY = screenHeight * 0.18f + 260.dp + 36.dp
            val artistDockY = 40.dp
            val artistExpandedY = titleExpandedY + 30.dp + 8.dp

            val titleXDp by remember { derivedStateOf { lerpDp(72.dp, 32.dp, p) } }
            val titleYDp by remember { derivedStateOf { lerpDp(titleDockY, titleExpandedY, p) } }
            val titleWp by remember { derivedStateOf { lerpDp(dockWidth - 204.dp, screenWidth - 64.dp, p) } }
            val titleSizeSp by remember { derivedStateOf { lerpSp(16.sp, 24.sp, p) } }

            Box(
                modifier = Modifier
                    .offset(x = titleXDp, y = titleYDp)
                    .width(titleWp),
                contentAlignment = Alignment.TopStart
            ) {
                BreezyCustomText(
                    text = songTitle,
                    fontSize = titleSizeSp,
                    fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface,
                    maxLines = 1,
                    modifier = if (p > 0.8f) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                )
            }

            val artistXDp by remember { derivedStateOf { lerpDp(72.dp, 32.dp, p) } }
            val artistYDp by remember { derivedStateOf { lerpDp(artistDockY, artistExpandedY, p) } }
            val artistSizeSp by remember { derivedStateOf { lerpSp(13.sp, 18.sp, p) } }
            val artistAlpha by remember { derivedStateOf { lerp(0.6f, 0.7f, p) } }

            Box(
                modifier = Modifier
                    .offset(x = artistXDp, y = artistYDp)
                    .width(titleWp),
                contentAlignment = Alignment.TopStart
            ) {
                BreezyCustomText(
                    text = songArtist,
                    fontSize = artistSizeSp,
                    fontWeight = FontWeight.Normal,
                    color = BreezyTheme.colors.onSurface.copy(alpha = artistAlpha),
                    maxLines = 1,
                    modifier = if (p > 0.8f) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                )
            }

            // ═══════════════════════════════════════
            // ── 进度条 ──
            // ═══════════════════════════════════════
            val progressBarExpandedY = artistExpandedY + 22.dp + 40.dp
            val progBarYDp by remember { derivedStateOf { lerpDp(artistDockY + 60.dp, progressBarExpandedY, p) } }
            val progressBarAlpha by remember { derivedStateOf { if (p > 0.4f) (p - 0.4f) / 0.3f else 0f } }

            MusicProgressBar(
                progress = currentProgress,
                duration = songDuration,
                onSeek = { fraction ->
                    PlaybackState.seekTo(fraction.coerceIn(0f, 1f))
                },
                modifier = Modifier
                    .offset(y = progBarYDp)
                    .padding(horizontal = 32.dp)
                    .graphicsLayer { alpha = progressBarAlpha.coerceIn(0f, 1f) }
            )

            // ═══════════════════════════════════════
            // ── 播放控制按钮 ──
            // ═══════════════════════════════════════
            val buttonsDockX = dockWidth - 144.dp
            val buttonsDockY = (dockHeight - 44.dp) / 2
            val buttonsExpandedY = progressBarExpandedY + 14.dp + 36.dp

            val buttonsXDp by remember { derivedStateOf { lerpDp(buttonsDockX, 0.dp, p) } }
            val buttonsYDp by remember { derivedStateOf { lerpDp(buttonsDockY, buttonsExpandedY, p) } }
            val buttonsWidthDp by remember { derivedStateOf { lerpDp(132.dp, screenWidth, p) } }

            PlayerButtons(
                isPlaying = isPlayingState,
                onPlayPause = { PlaybackState.togglePlayPause() },
                onPrev = { PlaybackState.playPrevious() },
                onNext = { PlaybackState.playNext() },
                modifier = Modifier
                    .offset(x = buttonsXDp, y = buttonsYDp)
                    .width(buttonsWidthDp),
                progress = p
            )

        }  // end card Box children

        // ═══════════════════════════════════════
        // ── 展开后翻页覆盖层（懒加载） ──
        // ═══════════════════════════════════════
        if (p > 0.6f) {
            val pageAlpha = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val pos = pagePosition.value  // 连续值：-1~1
            val slideUpY = (1f - pageAlpha) * screenHeightPx

            // 懒加载标记：仅在用户有翻页意图时才实例化
            var playlistLoaded by remember { mutableStateOf(false) }
            var lyricsLoaded by remember { mutableStateOf(false) }
            if (pos < -0.08f) playlistLoaded = true
            if (pos > 0.08f) lyricsLoaded = true

            // 播放列表（左侧），完全覆盖，左滑返回
            if (playlistLoaded) {
                val playlistTx = -(1f + pos.coerceIn(-1f, 0f)) * slidePx
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = playlistTx
                            translationY = slideUpY
                        }
                        .background(BreezyTheme.colors.background)
                        .systemBarsPadding()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragStartPage = pagePosition.value },
                                onDragEnd = {
                                    scope.launch {
                                        val disp = pagePosition.value - dragStartPage
                                        val start = dragStartPage.roundToInt().coerceIn(-1, 1).toFloat()
                                        val target = when {
                                            disp < -0.18f -> (start - 1).coerceIn(-1f, 1f)
                                            disp > 0.18f  -> (start + 1).coerceIn(-1f, 1f)
                                            else          -> start
                                        }
                                        pagePosition.animateTo(target, tween(250))
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    pageDrag.value = (pagePosition.value - dragAmount / slidePx).coerceIn(-1f, 1f)
                                }
                            )
                        }
                ) { PlaylistPage() }
            }

            // 歌词（右侧），完全覆盖，右滑返回
            if (lyricsLoaded) {
                val lyricsTx = (1f - pos.coerceIn(0f, 1f)) * slidePx
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = lyricsTx
                            translationY = slideUpY
                        }
                        .background(BreezyTheme.colors.background)
                        .systemBarsPadding()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragStartPage = pagePosition.value },
                                onDragEnd = {
                                    scope.launch {
                                        val disp = pagePosition.value - dragStartPage
                                        val start = dragStartPage.roundToInt().coerceIn(-1, 1).toFloat()
                                        val target = when {
                                            disp < -0.18f -> (start - 1).coerceIn(-1f, 1f)
                                            disp > 0.18f  -> (start + 1).coerceIn(-1f, 1f)
                                            else          -> start
                                        }
                                        pagePosition.animateTo(target, tween(250))
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    pageDrag.value = (pagePosition.value - dragAmount / slidePx).coerceIn(-1f, 1f)
                                }
                            )
                        }
                ) { LyricsPage() }
            }
        }

        // ═══════════════════════════════════════
        // ── 音效底栏：展开状态下常驻底部，上拉变形为整页 ──
        // ═══════════════════════════════════════
        if (p > 0.9f) {
            AudioEffectsPanel(onCollapse = { })
        }
    }  // end BoxWithConstraints
}
// ═══════════════════════════════════════════
// 统一的播放控制按钮（全部属性连续插值，零跳变）
// ═══════════════════════════════════════════

@Composable
private fun PlayerButtons(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float
) {
    val buttonSize = lerpDp(40.dp, 56.dp, progress)
    val iconSize = lerpDp(24.dp, 36.dp, progress)
    val playButtonSize = lerpDp(48.dp, 72.dp, progress)
    val playIconSize = lerpDp(26.dp, 40.dp, progress)
    val playBgColor = lerpColor(
        BreezyTheme.colors.primary.copy(alpha = 0.15f),
        BreezyTheme.colors.primary,
        progress
    )
    val playIconTint = lerpColor(
        BreezyTheme.colors.onSurface,
        BreezyTheme.colors.onPrimary,
        progress
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(
            onClick = onPrev,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = BreezyTheme.colors.onSurface,
                modifier = Modifier.size(iconSize)
            )
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(playButtonSize)
                .clip(CircleShape)
                .background(playBgColor)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = playIconTint,
                modifier = Modifier.size(playIconSize)
            )
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = BreezyTheme.colors.onSurface,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

// ═══════════════════════════════════════════
// 专辑封面
// ═══════════════════════════════════════════

@Composable
private fun AlbumCover(
    modifier: Modifier = Modifier,
    size: Dp,
    songUri: String? = null
) {
    val context = LocalContext.current
    var bitmap by remember(songUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(songUri) {
        if (songUri != null) {
            withContext(Dispatchers.IO) {
                bitmap = AlbumArtCache.get(context, songUri)
            }
        }
    }

    val artShape = when (ThemeState.coverShape) {
        CoverShape.Circle -> CircleShape
        CoverShape.RoundedRect -> RoundedCornerShape(12.dp)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(artShape)
            .background(
                brush = if (bitmap == null) Brush.linearGradient(
                    colors = listOf(Color(0xFF667eea), Color(0xFF764ba2))
                ) else Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.Transparent)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!,
                contentDescription = "专辑封面",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "专辑封面",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}

// ═══════════════════════════════════════════
// 音乐进度条（加粗 8dp，支持拖拽 Seek）
// ═══════════════════════════════════════════

@Composable
private fun MusicProgressBar(
    progress: Float,
    duration: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMs = (progress * duration).toLong()

    Column(modifier = modifier.fillMaxWidth()) {
        // 进度轨道（加粗 8dp，可交互拖拽）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BreezyTheme.colors.onSurface.copy(alpha = 0.2f))
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val fraction = (tapOffset.x / size.width).coerceIn(0f, 1f)
                        onSeek(fraction)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeek(fraction)
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BreezyTheme.colors.primary)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 时间标签（实时）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BreezyText(
                text = formatDuration(currentMs),
                level = BreezyTextLevel.Caption,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f)
            )
            BreezyText(
                text = formatDuration(duration),
                level = BreezyTextLevel.Caption,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

/** 格式化时长 ms → m:ss */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

/** Float 线性插值 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

/** 颜色线性插值 */
private fun lerpColor(a: Color, b: Color, fraction: Float): Color {
    return Color(
        red = lerp(a.red, b.red, fraction),
        green = lerp(a.green, b.green, fraction),
        blue = lerp(a.blue, b.blue, fraction),
        alpha = lerp(a.alpha, b.alpha, fraction)
    )
}

/** Dp 线性插值 */
private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return Dp(lerp(start.value, stop.value, fraction))
}

/** TextUnit(Sp) 线性插值——用于字号渐变 */
private fun lerpSp(start: TextUnit, stop: TextUnit, fraction: Float): TextUnit {
    return lerp(start.value, stop.value, fraction).sp
}
