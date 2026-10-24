package com.breezy.music.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 快速滚动条 —— 右侧拖拽手柄 + 浮标
 *
 * 置于 LazyColumn 的父 BoxWithConstraints 中（与列表并列）。
 * 轨道高度 = 父容器高度（通过 trackHeightDp 显式传入），手柄位置按 scrollRatio 映射。
 *
 * @param scrollRatio  列表滚动比例 0f..1f（顶部=0，底部=1）
 * @param trackHeightDp  父容器高度（BoxWithConstraints.maxHeight）
 */
@Composable
fun QuickScrollBar(
    bucketLabels: List<String>,
    bucketIndex: Map<String, Int>,
    totalItems: Int,
    scrollRatio: Float = 0f,
    trackHeightDp: androidx.compose.ui.unit.Dp = 0.dp,
    onScrollToIndex: (Int) -> Unit,
    firstCharAt: (Int) -> String = { "" },
    pinyinAt: (Int) -> String = { "" },
    modifier: Modifier = Modifier
) {
    if (bucketIndex.isEmpty() || totalItems <= 1) return

    val primaryColor = BreezyTheme.colors.primary
    val density = LocalDensity.current
    val trackW = 24.dp
    val handleH = 40.dp
    val handleW = 4.dp
    val trackHPx = with(density) { trackHeightDp.toPx() }.coerceAtLeast(1f)
    val handleHPx = with(density) { handleH.toPx() }

    // ── 状态 ──
    var dragging by remember { mutableStateOf(false) }
    var dragR by remember { mutableStateOf(0f) }
    var bucket by remember { mutableStateOf<String?>(null) }
    var labelChar by remember { mutableStateOf("") }
    var labelPinyin by remember { mutableStateOf("") }

    // 手柄粗细
    var thick by remember { mutableStateOf(false) }
    val animW by animateDpAsState(if (thick) 8.dp else handleW, tween(180))
    LaunchedEffect(dragging) {
        if (dragging) thick = true
        else { delay(350); thick = false }
    }

    // 浮标消失 —— 拖拽结束后延迟隐藏，避免闪烁
    LaunchedEffect(bucket, dragging) {
        if (bucket != null && !dragging) { delay(300); bucket = null; labelChar = ""; labelPinyin = "" }
    }

    // ── 触摸 Y → 比例 → 索引（节流：同一索引不重复调用） ──
    var lastScrollIdx by remember { mutableIntStateOf(-1) }
    fun onTouch(y: Float) {
        if (trackHPx <= 0f) return
        val r = (y / trackHPx).coerceIn(0f, 1f)
        dragR = r
        val idx = (r * (totalItems - 1)).roundToInt()
        if (idx != lastScrollIdx) {
            lastScrollIdx = idx
            bucket = bucketForIdx(idx, bucketIndex)
            labelChar = firstCharAt(idx)
            labelPinyin = pinyinAt(idx)
            onScrollToIndex(idx)
        }
    }

    // ── 有效比例 —— 拖拽时 snap 瞬时响应，松手后 animateFloatAsState 平滑过渡 ──
    val targetRatio = if (dragging || bucket != null) dragR else scrollRatio.coerceIn(0f, 1f)
    val ratio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = if (dragging) snap() else tween(250, easing = LinearEasing)
    )
    // 手柄底部留出音乐 Dock 高度（~80dp），避免与悬浮播放器重叠
    val bottomReserve = with(density) { 80.dp.toPx() }
    val movable = (trackHPx - handleHPx - bottomReserve).coerceAtLeast(0f)
    val handleYPx = ratio * movable
    val indOffX = -with(density) { (trackW + 8.dp).toPx().roundToInt() }
    // 浮标 TopEnd 对齐，偏移使浮标中心对准手柄中心
    // 浮标中心对准手柄中心：handleYPx + 20dp(手柄半高) - 28dp(正方形半高) = handleYPx - 8dp
    val indOffY = (handleYPx - with(density) { 8.dp.toPx() }).roundToInt()

    // ═══════════════════════════════════════
    // 外 Box 撑满父容器（matchParentSize），仅右侧 24dp 为触摸轨道
    // ═══════════════════════════════════════
    Box(modifier = modifier) {
        // 右侧触摸轨道
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(trackW)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { off -> dragging = true; onTouch(off.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false; bucket = null },
                        onVerticalDrag = { ch, _ -> ch.consume(); onTouch(ch.position.y) }
                    )
                }
        ) {
            // 手柄 —— TopEnd 对齐，ratio=0 时紧贴轨道顶部
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, handleYPx.roundToInt()) }
                    .width(animW)
                    .height(handleH)
                    .clip(RoundedCornerShape(animW / 2))
                    .background(primaryColor.copy(alpha = if (dragging) 0.88f else 0.38f))
            )
        }

        // 浮标（紧贴轨道左侧）—— 主文字为歌名首字，下方为拼音（仅中文）
        if (bucket != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(indOffX, indOffY) }
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(primaryColor),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 主显示：歌名首字符（汉字/英文首字母），随歌曲实时变化
                    BreezyCustomText(text = labelChar.ifEmpty { bucket ?: "" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    // 副显示：拼音（如 AI, AN, BA），仅中文字符显示，日韩英文不显示
                    if (labelPinyin.isNotEmpty())
                        BreezyCustomText(text = labelPinyin, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(0.85f))
                }
            }
        }
    }
}

private fun bucketForIdx(idx: Int, map: Map<String, Int>): String? {
    if (map.isEmpty()) return null
    var last: String? = null
    for ((k, v) in map) { if (idx < v) return last ?: k; last = k }
    return last
}


