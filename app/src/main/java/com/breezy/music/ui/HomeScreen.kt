package com.breezy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 多选工具栏共享槽位，子页面通过此 CompositionLocal 设置工具栏 */
val LocalMultiSelectDock = compositionLocalOf<((@Composable () -> Unit)?) -> Unit> { {} }

// ── 卡片数据 ──
private data class HomeCard(
    val title: String,
    val icon: ImageVector
)

private val homeCards = listOf(
    HomeCard("所有歌曲", Icons.Default.Favorite),
    HomeCard("文件夹", Icons.Default.PlaylistPlay),
    HomeCard("专辑", Icons.Default.LibraryMusic),
    HomeCard("歌手", Icons.Default.Podcasts),
    HomeCard("歌单", Icons.Default.Mic),
    HomeCard("运动音乐", Icons.Default.DirectionsRun),
    HomeCard("设置", Icons.Default.Audiotrack)
)

/**
 * 主页面：6 宫格卡片 + 底部悬浮音乐播放 Dock
 */
@Composable
fun HomeScreen() {
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    var collapsingIndex by remember { mutableStateOf<Int?>(null) }
    var multiSelectDock by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    // 保存各页面内部路由状态（子页面导航位置），退出再进入时恢复
    var albumSubState by remember { mutableStateOf<Any?>(null) }
    var artistSubState by remember { mutableStateOf<Any?>(null) }
    var playlistSubState by remember { mutableStateOf<Any?>(null) }
    var scrollPositions by remember { mutableStateOf(mutableMapOf<Int, Int>()) }

    // 接管返回键：有展开卡片时触发收起动画
    val backContext = LocalContext.current
    BackHandler(enabled = expandedIndex != null && collapsingIndex == null
        && com.breezy.music.model.Preferences.getBackBehavior(backContext) == "smart") {
        collapsingIndex = expandedIndex
        expandedIndex = null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BreezyTheme.colors.background)
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val gridPadding = 20.dp
        val gridSpacing = 14.dp
        val columns = 3
        val rows = (homeCards.size + columns - 1) / columns
        val cardWidth = (screenWidth - gridPadding * 2 - gridSpacing * (columns - 1)) / columns
        // 卡片高度自适应：3 列 7 宫格时若沿用 1.15 倍宽，底部会被播放 Dock 遮挡，
        // 因此按可用高度（屏高 - 顶部标题区 - 底部 Dock 区）反算最大高度。
        val topOffset = 90.dp
        val bottomReserved = 132.dp
        val availableHeight = (screenHeight - topOffset - bottomReserved)
            .coerceAtLeast(cardWidth)
        val maxCardHeight = (availableHeight - gridSpacing * (rows - 1)) / rows
        val cardHeight = minOf(cardWidth * 1.15f, maxCardHeight).coerceAtLeast(cardWidth * 0.72f)

        // ═══════════════════════════════════════
        // ── 页面标题 ──
        // ═══════════════════════════════════════
        BreezyCustomText(
            text = "BreezyMusic",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = BreezyTheme.colors.onBackground,
            modifier = Modifier
                .offset(x = 24.dp, y = 48.dp)
        )

        // ═══════════════════════════════════════
        // 卡片区 —— 展开的卡片放在最后渲染，确保在最顶层
        // ═══════════════════════════════════════
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalMultiSelectDock provides { dock -> multiSelectDock = dock }) {
            // 未展开卡片（底层）
            homeCards.forEachIndexed { index, card ->
                if (expandedIndex != index) {
                    val row = index / columns; val col = index % columns
                    ExpandableCardItem(
                        card = card, cardWidth = cardWidth, cardHeight = cardHeight,
                        cardOffsetX = gridPadding + (cardWidth + gridSpacing) * col,
                        cardOffsetY = topOffset + (cardHeight + gridSpacing) * row,
                        screenWidth = screenWidth, screenHeight = screenHeight,
                        isExpanded = false,
                        onClick = { expandedIndex = index },
                        onCollapse = {}
                    )
                }
            }
            // 展开卡片（顶层，包含正在收起的卡片以支持动画）
            homeCards.forEachIndexed { index, card ->
                if (expandedIndex == index || collapsingIndex == index) {
                    val row = index / columns; val col = index % columns
                    ExpandableCardItem(
                        card = card, cardWidth = cardWidth, cardHeight = cardHeight,
                        cardOffsetX = gridPadding + (cardWidth + gridSpacing) * col,
                        cardOffsetY = topOffset + (cardHeight + gridSpacing) * row,
                        screenWidth = screenWidth, screenHeight = screenHeight,
                        isExpanded = expandedIndex == index,
                        onClick = { expandedIndex = null },
                        onCollapse = {
                            if (collapsingIndex == index) collapsingIndex = null
                            else expandedIndex = null
                        },
                        expandedContent = when (index) {
                            6 -> { { close -> SettingsScreen(onBack = close) } }
                            0 -> { { close -> AllSongsPage(onBack = close) } }
                            1 -> { { close -> FoldersPage(onBack = close) } }
                            2 -> { { close -> AlbumsPage(onBack = close) } }
                            3 -> { { close -> ArtistsPage(onBack = close) } }
                            4 -> { { close -> PlaylistBrowserPage(onBack = close) } }
                            5 -> { { close -> RunningMusicPage(onBack = close) } }
                            else -> null
                        }
                    )
                }
            }
            } // end CompositionLocalProvider
        }

        // ═══════════════════════════════════════
        // 悬浮音乐播放 Dock（始终可见，最顶层）
        // ═══════════════════════════════════════
        MusicPlayerScreen(
            modifier = Modifier.fillMaxSize()
        )

        // 多选工具栏（顶层渲染，避免被卡片裁剪）
        multiSelectDock?.invoke()
    }
}

// ═══════════════════════════════════════════
// 单个可展开卡片（点击打开，横滑关闭，展开后图标+文字移至左上角标题栏）
// ═══════════════════════════════════════════

@Composable
private fun ExpandableCardItem(
    card: HomeCard,
    cardWidth: Dp,
    cardHeight: Dp,
    cardOffsetX: Dp,
    cardOffsetY: Dp,
    screenWidth: Dp,
    screenHeight: Dp,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onCollapse: () -> Unit,
    expandedContent: (@Composable (requestClose: () -> Unit) -> Unit)? = null
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val screenWidthPx = with(LocalDensity.current) { screenWidth.toPx() }

    // 拖拽状态：用 StateFlow 串行化 snapTo，避免每个触摸事件都创建协程（性能优化）
    var dragTarget by remember { mutableStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    val dragRequests = remember { MutableStateFlow(Float.NaN) }
    LaunchedEffect(Unit) {
        dragRequests.collect { target ->
            if (!target.isNaN() && dragActive) {
                progress.snapTo(target.coerceIn(0f, 1f))
            }
        }
    }

    var internalExpanded by remember { mutableStateOf(isExpanded) }

    // 展开动画完成后再渲染重内容，避免动画卡顿
    var contentReady by remember { mutableStateOf(false) }
    if (isExpanded && !contentReady) {
        // 内容将在动画接近完成时渲染
    }

    // 父级通知展开/收起
    LaunchedEffect(isExpanded) {
        if (isExpanded && !internalExpanded) {
            internalExpanded = true
        } else if (!isExpanded && internalExpanded) {
            internalExpanded = false
        }
    }

    /** 带动画过渡（与 Dock 完全相同模式） */
    fun animateTo(target: Float) {
        scope.launch {
            progress.animateTo(target, tween(300, easing = LinearEasing))
            if (target == 0f) onCollapse()
        }
    }

    // 状态变化驱动动画
    LaunchedEffect(internalExpanded) {
        if (internalExpanded) {
            contentReady = false
            animateTo(1f)
        } else animateTo(0f)
    }

    val p = progress.value

    // ── 卡片插值 ──
    val animWidth = lerpDp(cardWidth, screenWidth, p)
    val animHeight = lerpDp(cardHeight, screenHeight, p)
    val animOffsetX = lerpDp(cardOffsetX, 0.dp, p)
    val animOffsetY = lerpDp(cardOffsetY, 0.dp, p)
    val animCornerRadius = lerpDp(16.dp, 0.dp, p)
    val cardBg = lerpColor(
        androidx.compose.ui.graphics.lerp(BreezyTheme.colors.surface, BreezyTheme.colors.primary, 0.12f),
        BreezyTheme.colors.background,
        p
    )

    // ── 图标：宫格居中 → 左上角标题栏 ──
    val iconSize = lerpDp(36.dp, 24.dp, p)
    val iconX = lerpDp((cardWidth - 36.dp) / 2, 20.dp, p)
    val iconY = lerpDp((cardHeight - 36.dp) / 2 - 10.dp, 52.dp, p)

    // ── 展开后内容区渐显 ──
    val expandedAlpha = if (p > 0.5f) (p - 0.5f) / 0.5f else 0f

    Box(
        modifier = Modifier
            .offset(x = animOffsetX, y = animOffsetY)
            .size(animWidth, animHeight)
            .shadow(lerpDp(6.dp, 0.dp, p), RoundedCornerShape(animCornerRadius))
            .clip(RoundedCornerShape(animCornerRadius))
            .background(cardBg)
            // 统一手势处理：点击切换 + 横滑跟手收起（与 Dock 完全一致）
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (!internalExpanded) onClick()
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, _ -> change.consume() }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragTarget = progress.value
                        dragActive = true
                    },
                    onDragEnd = {
                        dragActive = false
                        if (progress.value < 0.7f) internalExpanded = false
                        else scope.launch { progress.animateTo(1f, tween(150, easing = LinearEasing)) }
                    },
                    onDragCancel = { dragActive = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (progress.value > 0.1f) {
                            val delta = dragAmount / (screenWidthPx * 0.8f)
                            dragTarget = (dragTarget - delta).coerceIn(0f, 1f)
                            // 性能优化：不再为每个 move 事件 launch 协程，
                            // 而是把目标值推入 StateFlow，由单个收集协程顺序 snapTo。
                            dragRequests.value = dragTarget
                        }
                    }
                )
            }
    ) {
        // ── 图标（渐隐消失） ──
        Icon(
            imageVector = card.icon,
            contentDescription = card.title,
            tint = BreezyTheme.colors.onSurface.copy(alpha = (1f - p).coerceIn(0f, 1f)),
            modifier = Modifier
                .offset(x = iconX, y = iconY)
                .size(iconSize)
        )

        // ── 标题（从宫格居中 → 左上角移动并渐隐） ──
        val moveTitleX = lerpDp((cardWidth - (cardWidth - 12.dp)) / 2, 24.dp, p)
        val moveTitleY = lerpDp(iconY + iconSize + 8.dp, 52.dp, p)
        val moveTitleAlpha = (1f - p * 1.2f).coerceIn(0f, 1f)  // 到头前消失

        Box(
            modifier = Modifier
                .offset(x = moveTitleX, y = moveTitleY)
                .width(cardWidth - 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BreezyCustomText(
                text = card.title,
                fontSize = lerpSp(14.sp, 18.sp, p),
                fontWeight = FontWeight.Bold,
                color = BreezyTheme.colors.onSurface.copy(alpha = moveTitleAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (expandedAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = expandedAlpha.coerceIn(0f, 1f) }
            ) {
                // 关闭按钮（有自定义内容时隐藏，页面自带返回箭头）
                if (expandedContent == null) {
                    IconButton(
                        onClick = { internalExpanded = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 44.dp, end = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = BreezyTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // 展开后内容区（动画接近完成时才渲染重内容）
                if (expandedContent != null) {
                    if (p > 0.9f) {
                        LaunchedEffect(Unit) { contentReady = true }
                    }
                    if (contentReady) {
                        expandedContent { internalExpanded = false }
                    } else {
                        // 动画期间轻量占位
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = BreezyTheme.colors.primary.copy(alpha = 0.5f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BreezyCustomText(
                            text = "即将推出更多内容",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            align = TextAlign.Center
                        )
                    }
            }
        }
    }
}
}

// ═══════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun lerpColor(a: Color, b: Color, fraction: Float): Color {
    return Color(
        red = lerp(a.red, b.red, fraction),
        green = lerp(a.green, b.green, fraction),
        blue = lerp(a.blue, b.blue, fraction),
        alpha = lerp(a.alpha, b.alpha, fraction)
    )
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return Dp(lerp(start.value, stop.value, fraction))
}

private fun lerpSp(start: androidx.compose.ui.unit.TextUnit, stop: androidx.compose.ui.unit.TextUnit, fraction: Float): androidx.compose.ui.unit.TextUnit {
    return lerp(start.value, stop.value, fraction).sp
}

