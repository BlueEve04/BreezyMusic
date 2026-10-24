@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.breezy.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.breezy.music.audio.AudioEffects
import com.breezy.music.audio.BreezyDspProcessor
import com.breezy.music.audio.EqCurve
import com.breezy.music.audio.EqMode
import com.breezy.music.audio.EqPreset
import com.breezy.music.audio.EqPresets
import com.breezy.music.audio.ReverbPresets
import com.breezy.music.model.AudioEngine
import com.breezy.ui.button.BreezyButton
import com.breezy.ui.button.BreezyButtonSize
import com.breezy.ui.button.BreezyButtonStyle
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 音效面板（v1.1）
 *
 * 交互模型：面板本身是一条「常驻底栏」。
 *  - 收起时：底部悬浮条显示当前曲目的技术信息（编码 / 码率 / 采样率）与正在使用的音效；
 *  - 上拉时：整条底栏**跟手**上移、轻微缩小并靠左上，标题「音效」逐渐淡入，原来的信息行沉到标题下方；
 *  - 展开后：下方是两页内容（均衡器 / 音效·混响），下拉标题栏或点收起按钮回到底栏。
 *
 * 这样设计的原因是：音效不是独立页面，而是播放页的一部分。底栏能让用户随时确认「现在用的什么音效」，
 * 又不需要先进入设置再回来。
 *
 * @param progress 0 = 收起为底栏，1 = 完全展开。由本组件驱动，播放页只负责在合适的时候挂载它。
 */
@Composable
fun AudioEffectsPanel(
    onCollapse: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val colors = BreezyTheme.colors

    // 0 = 贴底常驻栏，1 = 整页展开。
    // 刻意**不用 Animatable**：拖动要「每帧直接写值」，而 Animatable 只能经挂起函数修改，
    // 手势回调又处在受限的协程作用域里。绕一层协程或状态流都会出问题：
    // 状态流会合并中间值（表现为突然跳一截），松手后残留的旧值还会把归位动画打断
    //（表现为「手拖回去就卡在半截」）。用一个普通 Float 状态 + 直接赋值最稳。
    var p by remember { mutableFloatStateOf(0f) }

    // 松手后的归位动画：自己管一个 Job，避免「目标值没变就不重启」的问题。
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = p,
                targetValue = target,
                animationSpec = tween(260, easing = FastOutSlowInEasing)
            ) { value, _ -> p = value }
            if (target == 0f) onCollapse()
        }
    }

    var showEqPicker by remember { mutableStateOf(false) }
    var showReverbPicker by remember { mutableStateOf(false) }
    var showNewPreset by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 供满屏手势使用的几个量：由 BoxWithConstraints 的内容体在每帧写入。
    // 用 State 承载，是因为 pointerInput 的 modifier 先于内容体求值。
    var panelCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var travelPxState by remember { mutableStateOf(1f) }
    var headerBottomState by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 收起时它就是一条贴底的通栏：上面两行信息（编码/码率/采样率、当前音效·混响）。
        // 展开时整块面板从这条底栏长成整页——和首页卡片展开是同一套语言。
        //
        // 三条硬约束（都是前面几版翻车换来的）：
        // 1) 标题栏排版全程不变。标题行固定 38dp，收起时只是被面板顶边裁掉，
        //    信息行靠一次 translationY 让位。不要用「高度从 0 长出来」，那会每帧重新测量，看着卡。
        // 2) 手势热区必须固定。挂在面板自己身上时，面板一边长高一边移动，
        //    手指的局部坐标跟着移动，位移被重复叠加——表现就是抽搐、不跟手。
        // 3) 热区只盖住底栏那一条。面板是铺在播放页上的整屏容器，
        //    如果整屏接管事件，播放/上一首/下一首和播放页原有的下滑关闭都会失灵。
        val barHeight = 88.dp
        val titleHeight = 48.dp   // 含 IconButton 的最小可点区域，逼着它就是 48dp
        val infoHeight = 44.dp
        val headerContentHeight = titleHeight + infoHeight
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentOffset = headerContentHeight + 8.dp
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val barHeightPx = with(density) { barHeight.toPx() }
        // 手指走过的距离：底栏顶端 → 屏幕顶端
        val travelPx = (screenHeightPx - barHeightPx).coerceAtLeast(1f)
        travelPxState = travelPx
        val panelHeight = lerpDp(barHeight, maxHeight, p)
        val panelTop = lerpDp(maxHeight - barHeight, 0.dp, p)
        val panelColor = lerpColor(
            // 面板不直接用背景色，而是掺一点当前主题色（约 6%）：
            // 让音效页和播放页在观感上属于同一套配色，又不至于喧宾夺主。
            lerpColor(colors.surface, colors.background, p.coerceIn(0f, 1f)),
            colors.primary,
            0.06f
        )
        // 从任何页面拉起播放页时，这条底栏不要「啪」地出现：组合时从透明淡入。
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val appearAlpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "panelAppear"
        )
        val bodyAlpha = ((p - 0.10f) / 0.45f).coerceIn(0f, 1f)
        // 收起时：把标题整行推到面板外，并让信息块在 88dp 的通栏里垂直居中。
        // 展开时：整体落到状态栏下方，标题淡入，信息行退回标题下面。
        val collapsedShift = -titleHeight + (barHeight - infoHeight) / 2
        val headerShift = lerpDp(collapsedShift, statusTop, p.coerceIn(0f, 1f))

        // 手势直接挂在「面板自身」上：它的大小/位置就是可见范围（收起时 88dp 底栏、
        // 展开时整页），因此不会像整屏覆盖层那样把播放页的按钮和滑动手势全挡住。
        // 位移一律用屏幕坐标算（localToWindow）：面板高度在拖动中不断变化，
        // 用节点局部坐标会把自己「长高」的那部分重复计入，表现为画面突然跳一截。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = panelTop)
                .height(panelHeight)
                .graphicsLayer { alpha = appearAlpha }
                .background(panelColor)
                .onGloballyPositioned { panelCoords = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val expanded = p > 0.2f
                        val headerZonePx = with(density) { (statusTop + headerContentHeight).toPx() }
                        // 底部约 30dp 是系统的"上滑回桌面"手势区：从这里起手，
                        // 系统会先吃掉这次滑动，应用只能收到 CANCEL。
                        // 所以底栏热区要主动避开这一条，只认上方的起手点。
                        val gestureGuardPx = with(density) { GESTURE_GUARD.toPx() }
                        val belowGuard = down.position.y > size.height - gestureGuardPx
                        val inZone = if (expanded) down.position.y <= headerZonePx else !belowGuard
                        if (!inZone) return@awaitEachGesture

                        var total = 0f
                        var lastY = windowY(panelCoords, down.position)
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.isEmpty()) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val y = windowY(panelCoords, change.position)
                            val dy = y - lastY
                            lastY = y
                            total += dy
                            if (!moved && kotlin.math.abs(total) > 12f) {
                                moved = true
                                settleJob?.cancel()
                            }
                            if (moved) {
                                change.consume()
                                p = (p - dy / travelPxState).coerceIn(0f, 1f)
                            }
                        }

                        val threshold = travelPxState * 0.30f
                        if (!expanded) {
                            when {
                                !moved -> settleTo(1f)
                                total < -threshold -> settleTo(1f)
                                else -> settleTo(0f)
                            }
                        } else {
                            if (moved && total > threshold) settleTo(0f) else settleTo(1f)
                        }
                    }
                }
        ) {
            // 内容页：始终从标题栏下方开始，随展开进度让出空间
            if (bodyAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = bodyAlpha }
                        .padding(top = (contentOffset + statusTop) * p)
                ) {
                    EffectsPager(
                        onOpenEqPicker = { showEqPicker = true },
                        onOpenReverbPicker = { showReverbPicker = true },
                        onNewPreset = { showNewPreset = true },
                        onOpenMenu = { showMenu = true }
                    )
                }
            }

            HeaderBar(
                progress = p,
                onCollapseClick = { settleTo(0f) },
                modifier = Modifier
                    .fillMaxWidth()
                    // requiredHeight 忽略父级最大高度约束：面板收起时只有 88dp，
                    // 但标题行必须完整排版后再整体上移，否则信息行会被压成 0 高度（文字显示不全）。
                    .requiredHeight(headerContentHeight)
                    .graphicsLayer { translationY = headerShift.toPx() }
            )

            // 收起态的抓取条：明确「这里能拉」
            if (p < 0.6f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBottom + 5.dp)
                        .size(width = 38.dp, height = 3.dp)
                        .graphicsLayer { alpha = 0.28f * (1f - p / 0.6f) }
                        .background(colors.onSurface, RoundedCornerShape(2.dp))
                )
            }
        }

    }

    // ── 弹窗 ──
    if (showEqPicker) {
        val items = remember(AudioEffects.customPresets.size, AudioEffects.presetId) {
            buildEqPickerItems()
        }
        PresetPickerDialog(
            title = "选择均衡器预设",
            items = items,
            currentId = AudioEffects.presetId,
            onPick = { id ->
                AudioEffects.allPresets.firstOrNull { it.id == id }?.let { AudioEffects.applyPreset(it) }
                showEqPicker = false
            },
            onDelete = { id -> AudioEffects.deleteCustomPreset(id) },
            onDismiss = { showEqPicker = false }
        )
    }

    if (showReverbPicker) {
        val items = remember {
            ReverbPresets.ALL.map {
                PickerItem(
                    id = it.id,
                    name = it.name,
                    subtitle = if (it.id == ReverbPresets.OFF_ID) "不使用混响" else
                        "湿度 ${(it.mix * 100).roundToInt()}% · 房间 ${(it.room * 100).roundToInt()}%",
                    category = PickerCategory.PRESET
                )
            }
        }
        PresetPickerDialog(
            title = "选择混响预设",
            items = items,
            currentId = AudioEffects.reverbPresetId,
            onPick = { id ->
                ReverbPresets.byId(id)?.let { AudioEffects.applyReverbPreset(it) }
                showReverbPicker = false
            },
            onDismiss = { showReverbPicker = false }
        )
    }

    if (showNewPreset) {
        NewPresetDialog(
            onDismiss = { showNewPreset = false },
            onConfirm = { name, fromCurrent, newMode ->
                AudioEffects.createCustomPreset(context, name, fromCurrent, newMode)
                showNewPreset = false
            }
        )
    }

    if (showMenu) {
        EffectsMenuDialog(
            onDismiss = { showMenu = false },
            onNew = { showMenu = false; showNewPreset = true },
            onRestore = { showMenu = false; AudioEffects.restorePreset() },
            onReset = { showMenu = false; AudioEffects.resetAll() }
        )
    }
}

// ══════════════════════════════════════════════════
// 会变形的标题栏（底栏 → 顶栏）
// ══════════════════════════════════════════════════

@Composable
private fun HeaderBar(
    progress: Float,
    onCollapseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 「音效」二字在展开途中淡入；收起时它不存在，于是两行信息就顶在底栏上边缘。
    // 这正是用户要的那种「文字往上走、标题渐显」的动画，而不是另一套从下往上滑的面板。
    // 标题始终参与排版，只靠透明度出现；信息行靠 translationY 让位（不改布局高度）。
    val titleAlpha = ((progress - 0.18f) / 0.40f).coerceIn(0f, 1f)
    val infoAlpha = 1f - 0.10f * progress.coerceIn(0f, 1f)
    val colors = BreezyTheme.colors

    val preset = AudioEffects.currentPreset()
    val reverbName = ReverbPresets.byId(AudioEffects.reverbPresetId)?.name ?: "关闭"
    val eqName = when {
        !AudioEffects.enabled -> "均衡关闭"
        preset != null -> preset.name
        else -> "自定义"
    }

    Column(modifier = modifier.padding(start = 20.dp, end = 20.dp)) {
        // ── 标题行：固定 38dp，不参与展开动画 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .graphicsLayer {
                    alpha = titleAlpha
                    translationY = (1f - titleAlpha) * 6.dp.toPx()
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            BreezyCustomText(
                text = "音效",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCollapseClick) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    "收起",
                    tint = colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // ── 信息行：底栏时这两行就是全部内容 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BreezyCustomText(
                    text = trackInfoText(),
                    fontSize = 12.sp,
                    color = colors.onSurface.copy(alpha = 0.72f * infoAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                BreezyCustomText(
                    text = "$eqName · $reverbName",
                    fontSize = 11.sp,
                    color = colors.primary.copy(alpha = 0.9f * infoAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress < 0.35f) {
                BreezyCustomText(
                    text = "上拉",
                    fontSize = 11.sp,
                    color = colors.onSurface.copy(alpha = 0.4f * (1f - progress / 0.35f)),
                    maxLines = 1
                )
            }
        }
    }
}

/** 当前曲目的编码 / 码率 / 采样率，来自 ExoPlayer 的轨道信息 */
private fun trackInfoText(): String {
    val sr = AudioEngine.audioSampleRate
    val br = AudioEngine.audioBitrate
    val codec = AudioEngine.audioCodec.ifEmpty { "AUDIO" }
    val srText = if (sr > 0) String.format("%.1f kHz", sr / 1000f) else "—"
    val brText = if (br > 0) "${br / 1000} kbps" else "—"
    val chText = when (val ch = AudioEngine.audioChannelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        else -> if (ch > 2) "${ch}ch" else ""
    }
    return listOf(codec, brText, srText, chText).filter { it.isNotEmpty() }.joinToString(" · ")
}

// ══════════════════════════════════════════════════
// 两页内容
// ══════════════════════════════════════════════════

@Composable
private fun EffectsPager(
    onOpenEqPicker: () -> Unit,
    onOpenReverbPicker: () -> Unit,
    onNewPreset: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TabChip("均衡器", pagerState.currentPage == 0, Modifier.weight(1f)) {
                scope.launch { pagerState.animateScrollToPage(0) }
            }
            TabChip("音效 · 混响", pagerState.currentPage == 1, Modifier.weight(1f)) {
                scope.launch { pagerState.animateScrollToPage(1) }
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> EqualizerPage(
                    onOpenPicker = onOpenEqPicker,
                    onNewPreset = onNewPreset,
                    onOpenMenu = onOpenMenu
                )

                else -> ReverbPage(onOpenPicker = onOpenReverbPicker)
            }
        }
    }
}

// ══════════════════════════════════════════════════
// 均衡器页
// ══════════════════════════════════════════════════

@Composable
private fun EqualizerPage(
    onOpenPicker: () -> Unit,
    onNewPreset: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val context = LocalContext.current
    val colors = BreezyTheme.colors
    val locked = AudioEffects.locked
    val preset = AudioEffects.currentPreset()
    var showPreampHelp by remember { mutableStateOf(false) }
    // 选中/未选中：0 或 1 个频段。图示与参数共用同一个下标，
    // 切模式时清空，避免用参数的下标去指图示的某一列。
    var selectedBand by remember(AudioEffects.mode) { mutableIntStateOf(-1) }

    // 面板每次拉起来都从顶部开始：上一次浏览留下的滚动位置会让"启用均衡器"那一行凭空消失，
    // 用户会以为功能没了。
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 40.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LimiterButton(
                    enabled = AudioEffects.limiterEnabled,
                    onToggle = { AudioEffects.enableLimiter(!AudioEffects.limiterEnabled) }
                )
                Spacer(Modifier.width(10.dp))
                BreezyCustomText("启用均衡器", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                SwitchSmall(AudioEffects.enabled) { AudioEffects.enable(it) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(colors.surface, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenPicker() }
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BreezyCustomText(
                                text = preset?.name ?: "自定义曲线",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            BreezyCustomText(
                                text = when {
                                    preset?.builtin == true && AudioEffects.modified -> "已修改 · 可一键还原"
                                    preset?.builtin == true -> "内置预设 · 锁定"
                                    preset == null -> "未命名曲线"
                                    else -> "自定义 · 可编辑"
                                },
                                fontSize = 10.sp,
                                color = colors.onSurface.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                        Icon(
                            Icons.Default.Search,
                            null,
                            tint = colors.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                LockButton(locked) { AudioEffects.toggleLock() }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onOpenMenu,
                    modifier = Modifier
                        .size(44.dp)
                        .background(colors.surface, RoundedCornerShape(14.dp))
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        "更多",
                        tint = colors.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 类型不是「模式开关」，而是这个预设自带的属性：
                // 图示均衡按等分频段摆推子，参数均衡按中心频率/Q/增益摆频段，
                // 两者的数据模型不同，硬切换只会把用户的曲线弄丢。
                // 所以这里只做展示，想换类型请新建一个预设。
                var showBandCount by remember { mutableStateOf(false) }
                val context = LocalContext.current
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.primary.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { },
                            onLongClick = {
                                // 段数是结构性改动：锁定态下不许改，给一句提示而不是默默无效
                                if (locked) {
                                    Toast.makeText(context, "解锁后调整段数", Toast.LENGTH_SHORT).show()
                                } else {
                                    showBandCount = true
                                }
                            }
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    BreezyCustomText(
                        text = if (AudioEffects.mode == EqMode.PARAMETRIC) {
                            "参数均衡 · ${AudioEffects.paramBands.size} 段"
                        } else {
                            "图示均衡 · ${AudioEffects.bandCount} 段"
                        },
                        fontSize = 11.sp,
                        color = colors.primary
                    )
                }
                if (showBandCount) {
                    BandCountDialog(onDismiss = { showBandCount = false })
                }
                Spacer(Modifier.weight(1f))
                // 曲线平滑开关：默认平滑，关掉就按段直连，方便看清每一段落在哪
                val smooth = AudioEffects.curveSmooth
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (smooth) colors.primary.copy(alpha = 0.10f) else colors.onSurface.copy(alpha = 0.05f))
                        .clickable { AudioEffects.setCurveSmooth(context, !smooth) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    BreezyCustomText(
                        if (smooth) "曲线平滑" else "折线",
                        fontSize = 10.sp,
                        color = if (smooth) colors.primary else colors.onSurface.copy(alpha = 0.55f)
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (locked) {
                    BreezyCustomText(
                        "已锁定 · 点锁图标解锁",
                        fontSize = 10.sp,
                        color = colors.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }

        // 曲线可视化（切换预设时渐变动画）
        item {
            Spacer(Modifier.height(10.dp))
            val target = remember(
                AudioEffects.mode,
                AudioEffects.gains.toList(),
                AudioEffects.bandCount,
                AudioEffects.paramBands
            ) { currentCurve() }
            val animated = rememberAnimatedCurve(target)
            val freqs = remember { EqCurve.logFrequencies() }
            val bandCurves = if (AudioEffects.mode == EqMode.PARAMETRIC) {
                remember(AudioEffects.paramBands) {
                    AudioEffects.paramBands.mapIndexed { i, b ->
                        bandColor(i) to EqCurve.parametricDb(freqs, listOf(b))
                    }
                }
            } else emptyList()
            EqCurveView(
                curve = animated,
                bandCurves = bandCurves,
                selectedBand = selectedBand,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
        }

        if (AudioEffects.mode == EqMode.GRAPHIC) {
            item {
                Spacer(Modifier.height(6.dp))
                GraphicFaders(locked = locked, selected = selectedBand) { i ->
                    selectedBand = if (selectedBand == i) -1 else i
                }
            }
        } else {
            item {
                Spacer(Modifier.height(6.dp))
                ParametricEditor(locked = locked, selected = selectedBand) { i ->
                    selectedBand = if (selectedBand == i) -1 else i
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreezyCustomText("前级增益", fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                // 带圈问号：这个词对普通用户不透明，点开给一段人话解释
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { showPreampHelp = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = "前级增益是什么",
                        tint = colors.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                BreezyCustomText(
                    "${formatDb(AudioEffects.preamp)} dB",
                    fontSize = 13.sp,
                    color = colors.primary
                )
            }
            Slider(
                value = AudioEffects.preamp,
                onValueChange = { AudioEffects.applyPreamp(it) },
                valueRange = -12f..12f,
                enabled = !locked,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = colors.primary.copy(alpha = if (locked) 0.35f else 1f),
                    inactiveTrackColor = colors.onSurface.copy(alpha = 0.18f)
                ),
                thumb = {
                    Box(
                        Modifier
                            .size(width = 9.dp, height = 24.dp)
                            .background(
                                colors.primary.copy(alpha = if (locked) 0.35f else 1f),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BreezyButton(
                    text = "新建均衡器",
                    onClick = onNewPreset,
                    style = BreezyButtonStyle.Secondary,
                    modifier = Modifier.weight(1f)
                )
                if (preset?.builtin == true) {
                    BreezyButton(
                        text = "还原预设",
                        onClick = { AudioEffects.restorePreset() },
                        style = BreezyButtonStyle.Outlined,
                        enabled = AudioEffects.modified,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    if (showPreampHelp) {
        InfoDialog(
            title = "前级增益是什么",
            body = "增益作用在均衡器之前，是一层整体音量微调。\n\n" +
                "提升低频或整体增益后，多个频段的峰值会叠加，声音容易过载削波（发破、发闷）。" +
                "这时把前级增益往负方向拉一点，相当于先把音量降低再进均衡器，就能留住曲线形状而不削顶。\n\n" +
                "反过来，整体偏小的录音可以把它往上推，让后面各段有更多余量。\n" +
                "单位是分贝（dB），±12 dB 已经足够；调到听着舒服、不破音即可，不必追求数值。",
            onDismiss = { showPreampHelp = false }
        )
    }
}

/**
 * 通用说明弹窗：一段标题 + 若干行正文。用于解释界面上那些「专业词汇」。
 */
@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    val colors = BreezyTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.background)
                .padding(20.dp)
        ) {
            BreezyCustomText(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            BreezyCustomText(
                body,
                fontSize = 13.sp,
                color = colors.onSurface.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(16.dp))
            BreezyButton("知道了", onDismiss, fullWidth = true)
        }
    }
}
@Composable
private fun GraphicFaders(locked: Boolean, selected: Int = -1, onSelect: (Int) -> Unit = {}) {
    val colors = BreezyTheme.colors
    val bandCount = AudioEffects.bandCount
    val gains = AudioEffects.gains
    val labels = remember(bandCount) { AudioEffects.bandLabels() }
    val scrollState = rememberScrollState()
    // 点数字直接输入：拖动很难做到 ±1 dB 的精度，尤其是小屏、或者手指有汗的时候。
    var editing by remember { mutableStateOf<Int?>(null) }

    // 段数一多，把十几条推子压进一屏宽会让每条只剩二三十 dp，手指很难精确点到；
    // 改成固定宽度、横向滚动：五段时刚好铺满，十六段时可以左右推。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(top = 6.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until bandCount) {
            val isSelected = i == selected
            // 选中那一列：整列铺一层从透明到主题色的竖向渐变当背景，
            // 其余列整体压暗——不需要精确遮罩，视觉上自然把注意力收过去。
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .graphicsLayer {
                        alpha = if (selected >= 0 && !isSelected) 0.42f else 1f
                    }
                    .then(
                        if (isSelected) {
                            Modifier.background(
                                Brush.verticalGradient(
                                    listOf(
                                        colors.primary.copy(alpha = 0.02f),
                                        colors.primary.copy(alpha = 0.14f),
                                        colors.primary.copy(alpha = 0.02f)
                                    )
                                ),
                                RoundedCornerShape(12.dp)
                            )
                        } else Modifier
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
            Column(
                modifier = Modifier.width(68.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BreezyCustomText(
                    text = formatDb(gains.getOrElse(i) { 0f }),
                    fontSize = 9.sp,
                    color = colors.onSurface.copy(alpha = if (locked) 0.4f else 0.75f),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !locked) { editing = i }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(4.dp))
                VerticalFader(
                    value = gains.getOrElse(i) { 0f },
                    range = -12f..12f,
                    enabled = !locked,
                    onHorizontalDrag = { dx -> scrollState.dispatchRawDelta(-dx) },
                    modifier = Modifier
                        .height(150.dp)
                        .fillMaxWidth()
                ) { db -> AudioEffects.setBandGain(i, db) }
                Spacer(Modifier.height(6.dp))
                BreezyCustomText(
                    text = labels.getOrElse(i) { "" },
                    fontSize = 7.5.sp,
                    color = if (isSelected) colors.primary else colors.onSurface.copy(alpha = 0.5f),
                    maxLines = 1
                )
            }
            }
        }
    }

    // 常驻滑杆：段数多到一屏放不下时，给一条可见的进度条，
    // 让「这排推子还能左右推」这件事不需要靠猜。
    FaderScrollBar(scrollState)

    val editIndex = editing
    if (editIndex != null && editIndex < bandCount) {
        BandValueDialog(
            title = labels.getOrElse(editIndex) { "频段" },
            initial = gains.getOrElse(editIndex) { 0f },
            onDismiss = { editing = null },
            onConfirm = { db ->
                AudioEffects.setBandGain(editIndex, db)
                editing = null
            }
        )
    }
}

/**
 * 推子那一排的常驻横向滑杆。
 *
 * 只在内容真的超出视口时出现；拖动滑块按比例映射成横向滚动量，
 * 点一下轨道也会直接跳到对应位置（和系统滚动条的手感一致）。
 */
@Composable
private fun FaderScrollBar(scrollState: ScrollState) {
    val colors = BreezyTheme.colors
    val maxScroll = scrollState.maxValue
    if (maxScroll <= 0) return
    val viewport = scrollState.viewportSize
    if (viewport <= 0) return

    var dragging by remember { mutableStateOf(false) }
    val thumbHeight by animateFloatAsState(
        targetValue = if (dragging) 18f else 5f,
        animationSpec = tween(140),
        label = "thumbHeight"
    )
    val thumbAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.95f else 0.55f,
        animationSpec = tween(140),
        label = "thumbAlpha"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(top = 4.dp)
            .pointerInput(maxScroll, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dragging = true
                    val w = size.width.toFloat()
                    val vp = viewport.toFloat()
                    val content = vp + maxScroll
                    val thumbW = (w * vp / content).coerceAtLeast(32f)
                    val travel = (w - thumbW).coerceAtLeast(1f)
                    fun jumpTo(x: Float) {
                        val t = ((x - thumbW / 2f) / travel).coerceIn(0f, 1f)
                        scrollState.dispatchRawDelta(t * maxScroll - scrollState.value)
                    }
                    jumpTo(down.position.x)
                    down.consume()
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        // 手柄走 1px，内容滚动 travel 分之 maxScroll 像素
                        scrollState.dispatchRawDelta(dx * maxScroll / travel)
                        change.consume()
                    }
                    dragging = false
                }
            }
    ) {
        val trackH = 4.dp.toPx()
        val cy = size.height / 2f
        drawRoundRect(
            color = colors.onSurface.copy(alpha = 0.10f),
            topLeft = Offset(0f, cy - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
        )
        val content = viewport.toFloat() + maxScroll
        val thumbW = (size.width * viewport.toFloat() / content).coerceAtLeast(32.dp.toPx())
        val travel = (size.width - thumbW).coerceAtLeast(1f)
        val frac = (scrollState.value.toFloat() / maxScroll).coerceIn(0f, 1f)
        // 按住时手柄明显变粗：4dp 的细条根本捏不住，
        // 放到 18dp 之后手指有实体感，拖动时也不容易被判成别的滚动。
        val h = thumbHeight.dp.toPx()
        drawRoundRect(
            color = colors.primary.copy(alpha = thumbAlpha),
            topLeft = Offset(frac * travel, cy - h / 2f),
            size = Size(thumbW, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
    }
}

/**
 * 数值输入弹窗。拖动只能做到「大概」，需要精确值时点开数字直接键入。
 * 输入框允许带正负号与小数，超出 ±12 dB 会被夹住。
 */
@Composable
private fun BandValueDialog(
    title: String,
    initial: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    val colors = BreezyTheme.colors
    var text by remember { mutableStateOf(((initial * 10).roundToInt() / 10f).toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            BreezyCustomText("调整 $title", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            BreezyCustomText(
                "输入 -12 到 +12 之间的数值，单位 dB",
                fontSize = 12.sp,
                color = colors.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-6f, -3f, 0f, 3f, 6f).forEach { v ->
                    val label = if (v > 0) "+${v.toInt()}" else v.toInt().toString()
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .clickable { text = v.toInt().toString() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BreezyCustomText(label, fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.75f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BreezyButton("取消", onDismiss, style = BreezyButtonStyle.Text, modifier = Modifier.weight(1f))
                BreezyButton(
                    "确定",
                    {
                        val parsed = text.trim().toFloatOrNull()
                        if (parsed != null) onConfirm(parsed.coerceIn(-12f, 12f)) else onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 参数均衡：频段选择 + 频率 / Q / 增益编辑 */
@Composable
private fun ParametricEditor(
    locked: Boolean,
    selected: Int = -1,
    onSelect: (Int) -> Unit = {}
) {
    val colors = BreezyTheme.colors
    val bands = AudioEffects.paramBands
    val band = bands.getOrNull(selected)

    Column(Modifier.fillMaxWidth()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(bands.size) { i ->
                val b = bands[i]
                val active = i == selected
                val accent = bandColor(i)
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) accent.copy(alpha = 0.18f) else colors.surface,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 颜色始终跟着频段走：未选中时也能一眼对上是哪条曲线
                    BreezyCustomText(
                        BreezyDspProcessor.formatFrequency(b.freqHz),
                        fontSize = 11.sp,
                        color = if (active) accent else accent.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                    BreezyCustomText(
                        formatDb(b.gainDb),
                        fontSize = 10.sp,
                        color = accent.copy(alpha = if (active) 0.8f else 0.5f),
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 没有选中频段时不显示任何调整控件：先选段，再调参
        if (band == null) return@Column

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(bandColor(selected), RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
            BreezyCustomText("频段 ${selected + 1}", fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            ParamTypeSelector(
                type = band.type,
                enabled = !locked,
                accent = bandColor(selected)
            ) { picked -> AudioEffects.setParamType(selected, picked) }
            Spacer(Modifier.weight(1f))
            BreezyCustomText(
                "${BreezyDspProcessor.formatFrequency(band.freqHz)} Hz · Q ${"%.2f".format(band.q)}",
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.55f)
            )
        }

        ParamSlider(
            label = "频率",
            value = ln(band.freqHz / 20f) / ln(1000f),
            valueText = "${BreezyDspProcessor.formatFrequency(band.freqHz)} Hz",
            enabled = !locked,
            accent = bandColor(selected)
        ) { t ->
            AudioEffects.setParamFrequency(selected, 20f * 1000f.pow(t.coerceIn(0f, 1f)))
        }

        ParamSlider(
            label = "Q 值",
            value = (band.q - 0.3f) / 9.7f,
            valueText = "%.2f".format(band.q),
            enabled = !locked && band.type == BreezyDspProcessor.TYPE_PEAK,
            accent = bandColor(selected)
        ) { t ->
            AudioEffects.setParamQ(selected, 0.3f + t.coerceIn(0f, 1f) * 9.7f)
        }

        ParamSlider(
            label = "增益",
            value = (band.gainDb + 12f) / 24f,
            valueText = "${formatDb(band.gainDb)} dB",
            enabled = !locked,
            accent = bandColor(selected)
        ) { t ->
            AudioEffects.setParamGain(selected, t.coerceIn(0f, 1f) * 24f - 12f)
        }
    }
}

// ══════════════════════════════════════════════════
// 混响页
// ══════════════════════════════════════════════════

@Composable
private fun ReverbPage(onOpenPicker: () -> Unit) {
    val colors = BreezyTheme.colors
    val preset = ReverbPresets.byId(AudioEffects.reverbPresetId)

    // 面板每次拉起来都从顶部开始：上一次浏览留下的滚动位置会让"启用均衡器"那一行凭空消失，
    // 用户会以为功能没了。
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 40.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreezyCustomText("启用混响", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                SwitchSmall(AudioEffects.reverbEnabled) { AudioEffects.enableReverb(it) }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenPicker() }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BreezyCustomText(
                            preset?.name ?: "自定义混响",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        BreezyCustomText(
                            "湿度 ${(AudioEffects.reverbMix * 100).roundToInt()}% · 房间 ${(AudioEffects.reverbRoom * 100).roundToInt()}%",
                            fontSize = 11.sp,
                            color = colors.onSurface.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = colors.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            ParamSlider(
                label = "湿度",
                value = AudioEffects.reverbMix,
                valueText = "${(AudioEffects.reverbMix * 100).roundToInt()}%",
                enabled = AudioEffects.reverbEnabled
            ) { AudioEffects.applyReverbMix(it) }
            ParamSlider(
                label = "房间大小",
                value = ((AudioEffects.reverbRoom - 0.5f) / 0.42f).coerceIn(0f, 1f),
                valueText = "${(AudioEffects.reverbRoom * 100).roundToInt()}%",
                enabled = AudioEffects.reverbEnabled
            ) { t -> AudioEffects.applyReverbRoom(0.5f + t * 0.42f) }

            Spacer(Modifier.height(12.dp))
            BreezyCustomText(
                "输出设备：${AudioEffects.device.label}（音效按设备分别记忆）",
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            BreezyCustomText(
                "信号链：ExoPlayer 解码 → 前级增益 → 均衡（图示/参数）→ 混响 → AudioTrack",
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

// ══════════════════════════════════════════════════
// 曲线
// ══════════════════════════════════════════════════

/** 当前参数下的目标曲线（dB），供可视化 */
private fun currentCurve(): FloatArray {
    val freqs = EqCurve.logFrequencies()
    return if (AudioEffects.mode == EqMode.PARAMETRIC) {
        EqCurve.parametricDb(freqs, AudioEffects.paramBands)
    } else {
        val bandFreqs = BreezyDspProcessor.centerFrequencies(AudioEffects.bandCount)
        if (AudioEffects.curveSmooth) {
            EqCurve.graphicDbSmooth(freqs, bandFreqs, AudioEffects.gains)
        } else {
            EqCurve.graphicDb(freqs, bandFreqs, AudioEffects.gains)
        }
    }
}

/**
 * 让曲线在参数变化时平滑过渡。
 *
 * 实现方式：以内容哈希为键，重新启动一个逐帧插值协程，从「当前显示值」插到「目标值」。
 * 之所以不用 animateFloatAsState，是因为曲线是一整条数组，逐点动画会产生上百个动画对象。
 */
@Composable
private fun rememberAnimatedCurve(target: FloatArray): FloatArray {
    var shown by remember { mutableStateOf(target.copyOf()) }
    val key = target.contentHashCode()
    LaunchedEffect(key) {
        val start = shown.copyOf()
        val end = target.copyOf()
        if (start.size != end.size) {
            shown = end
            return@LaunchedEffect
        }
        val begin = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - begin) / 1e9f / 0.32f).coerceIn(0f, 1f)
            val e = FastOutSlowInEasing.transform(t)
            shown = FloatArray(end.size) { i -> start[i] + (end[i] - start[i]) * e }
            if (t >= 1f) break
        }
    }
    return shown
}

/**
 * 限压器按钮：圆角，左侧一个随触发强度闪烁的圆点 + 文字。
 * 闪烁频率固定 3.8 Hz（约 260 ms 一程），亮度由当前增益衰减量决定——
 * 压得越狠闪得越亮，而不是简单的"开关"两态，这样用户在听感之外还能看到它确实在工作。
 */
@Composable
private fun LimiterButton(enabled: Boolean, onToggle: () -> Unit) {
    val colors = BreezyTheme.colors
    var level by remember { mutableFloatStateOf(0f) }
    var showStrength by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            level = 0f
            return@LaunchedEffect
        }
        while (true) {
            level = AudioEffects.limiterLevel()
            delay(33)
        }
    }

    val pulse = rememberInfiniteTransition(label = "limiter")
    val blink by pulse.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(260, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    val active = enabled && level > 0.02f
    val bg = when {
        !enabled -> colors.surface
        active -> colors.primary.copy(alpha = 0.12f + 0.55f * level.coerceIn(0f, 1f) * blink)
        else -> colors.primary.copy(alpha = 0.10f)
    }
    val fg = when {
        !enabled -> colors.onSurface.copy(alpha = 0.45f)
        else -> colors.primary
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg, RoundedCornerShape(9.dp))
            .combinedClickable(
                onClick = onToggle,
                onLongClick = { showStrength = true }
            )
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(
                    if (active) colors.primary.copy(alpha = 0.35f + 0.65f * level.coerceIn(0f, 1f) * blink)
                    else fg.copy(alpha = 0.5f),
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(6.dp))
        BreezyCustomText(
            text = "限压",
            fontSize = 11.sp,
            color = fg,
            maxLines = 1
        )
    }

    if (showStrength) {
        LimiterStrengthDialog(onDismiss = { showStrength = false })
    }
}

/**
 * 限压强度：本质是阈值。阈值越低越早开始压，听感上越"硬"；
 * 所以界面用"强度"这个词，但把 dBFS 原样写出来，免得用户不知道自己在改什么。
 */
@Composable
private fun LimiterStrengthDialog(onDismiss: () -> Unit) {
    val colors = BreezyTheme.colors
    var enabled by remember { mutableStateOf(AudioEffects.limiterEnabled) }
    var db by remember { mutableFloatStateOf(AudioEffects.limiterThresholdDb) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.background)
                .padding(20.dp)
        ) {
            BreezyCustomText("限压强度", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            BreezyCustomText(
                "阈值越低，越早开始压，声音越紧、越不容易破音；代价是动态被压扁。",
                fontSize = 12.sp,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                BreezyCustomText("启用", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                SwitchSmall(enabled) {
                    enabled = it
                    AudioEffects.enableLimiter(it)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreezyCustomText("阈值", fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                BreezyCustomText("%.1f dBFS".format(db), fontSize = 13.sp, color = colors.primary)
            }
            Slider(
                value = db,
                onValueChange = {
                    db = it
                    AudioEffects.applyLimiterThreshold(it)
                },
                valueRange = -12f..0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.onSurface.copy(alpha = 0.15f)
                ),
                thumb = {
                    Box(
                        Modifier
                            .size(width = 9.dp, height = 24.dp)
                            .background(colors.primary, RoundedCornerShape(3.dp))
                    )
                }
            )
            BreezyCustomText(
                "参考：−1 dBFS 是常见母带的峰值上限；−6 dBFS 已经算很激进的压制。",
                fontSize = 10.sp,
                color = colors.onSurface.copy(alpha = 0.45f)
            )

            Spacer(Modifier.height(12.dp))
            BreezyButton(
                text = "完成",
                onClick = onDismiss,
                fullWidth = true,
                style = BreezyButtonStyle.Secondary,
                size = BreezyButtonSize.Medium
            )
        }
    }
}

@Composable
private fun EqCurveView(
    curve: FloatArray,
    bandCurves: List<Pair<Color, FloatArray>> = emptyList(),
    /** 选中的频段下标；-1 表示没有选中。选中时压暗其它曲线、加粗提亮自己 */
    selectedBand: Int = -1,
    modifier: Modifier = Modifier
) {
    val colors = BreezyTheme.colors
    val primary = colors.primary
    val grid = colors.onSurface.copy(alpha = 0.10f)
    val labelColor = colors.onSurface.copy(alpha = 0.35f)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val rangeDb = 15f
            fun yOf(db: Float) = h / 2f - (db / rangeDb) * (h / 2f - 6.dp.toPx())

            // 水平网格：±12、±6、0 dB
            listOf(12f, 6f, 0f, -6f, -12f).forEach { db ->
                val y = yOf(db)
                drawLine(
                    color = if (db == 0f) grid.copy(alpha = 0.28f) else grid,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (db == 0f) 1.4.dp.toPx() else 1.dp.toPx()
                )
            }
            // 垂直网格：31 / 125 / 500 / 2k / 8k / 16k
            listOf(31f, 125f, 500f, 2000f, 8000f, 16000f).forEach { f ->
                val x = (ln(f / 20f) / ln(1000f)).coerceIn(0f, 1f) * w
                drawLine(color = grid, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1.dp.toPx())
            }

            if (curve.isEmpty()) return@Canvas

            // 先铺每个频段自己的响应曲线（细、半透明），再叠总的合成曲线。
            // 这样"谁把这一段抬起来的"在图上直接可见，不用去猜。
            bandCurves.forEachIndexed { index, pair ->
                val color = pair.first
                val values = pair.second
                if (values.size < 2) return@forEachIndexed
                val bandPath = Path()
                var px = 0f
                var py = 0f
                values.forEachIndexed { i, db ->
                    val x = w * i / (values.size - 1).toFloat()
                    val y = yOf(db)
                    if (i == 0) {
                        bandPath.moveTo(x, y)
                    } else {
                        // 以相邻两点的中点为终点、以当前点为控制点，
                        // 每段都相切，整条线在视觉上是连续光滑的
                        bandPath.quadraticBezierTo(px, py, (px + x) / 2f, (py + y) / 2f)
                    }
                    px = x
                    py = y
                }
                bandPath.lineTo(px, py)
                drawPath(
                    path = bandPath,
                    color = when {
                        selectedBand < 0 -> color.copy(alpha = 0.75f)
                        index == selectedBand -> color
                        else -> color.copy(alpha = 0.15f)
                    },
                    style = Stroke(
                        width = (if (index == selectedBand) 2.6.dp else 1.3.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            val path = Path()
            val fill = Path()
            var prevX = 0f
            var prevY = 0f
            curve.forEachIndexed { i, db ->
                val x = w * i / (curve.size - 1).toFloat()
                val y = yOf(db)
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, h / 2f)
                    fill.lineTo(x, y)
                    prevX = x
                    prevY = y
                } else {
                    val mx = (prevX + x) / 2f
                    val my = (prevY + y) / 2f
                    path.quadraticBezierTo(prevX, prevY, mx, my)
                    fill.quadraticBezierTo(prevX, prevY, mx, my)
                    prevX = x
                    prevY = y
                }
            }
            fill.lineTo(w, h / 2f)
            fill.close()

            // 合成曲线只留阴影面积，不画轮廓：
            // 轮廓会和上面各频段自己的彩色曲线抢视觉重心，而"整体形状"用面积表达已经够了。
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.34f), primary.copy(alpha = 0.03f))
                )
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("20", "100", "1k", "10k", "20k").forEach {
                BreezyCustomText(it, fontSize = 9.sp, color = labelColor, maxLines = 1)
            }
        }
    }
}

// ══════════════════════════════════════════════════
// 预设选择弹窗（搜索 + 分类标签）
// ══════════════════════════════════════════════════

enum class PickerCategory(val label: String) {
    PRESET("预设"),
    CUSTOM("自定义")
}

data class PickerItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: PickerCategory,
    val deletable: Boolean = false
)

private fun buildEqPickerItems(): List<PickerItem> {
    val builtin = EqPresets.BUILTIN.map { p ->
        PickerItem(
            id = p.id,
            name = p.name,
            subtitle = describePreset(p),
            category = PickerCategory.PRESET
        )
    }
    val custom = AudioEffects.customPresets.map { p ->
        PickerItem(
            id = p.id,
            name = p.name,
            subtitle = describePreset(p),
            category = PickerCategory.CUSTOM,
            deletable = true
        )
    }
    return builtin + custom
}

private fun describePreset(p: EqPreset): String {
    val maxBoost = p.gains.maxOrNull() ?: 0f
    val maxCut = p.gains.minOrNull() ?: 0f
    val shape = when {
        maxBoost > 6f -> "强提升"
        maxBoost > 2f -> "轻度提升"
        maxCut < -6f -> "明显衰减"
        maxCut < -2f -> "轻度衰减"
        else -> "接近平直"
    }
    // 副标题要写「实际落到当前段数上的样子」，否则用户会以为 16 段没生效：
    // 内置预设是定义在 10 段 ISO 上的，套用到别的段数时按对数频率插值。
    return if (p.mode == EqMode.PARAMETRIC) {
        "参数均衡 · ${p.paramBands.size} 段 · $shape"
    } else {
        // 每个预设显示它自己的段数：用当前选中的段数会让列表里所有预设看起来都被改过
        val applied = AudioEffects.bandCountOf(p)
        if (applied == 10) "10 段 ISO · $shape" else "$applied 段（按 10 段 ISO 插值）· $shape"
    }
}

@Composable
private fun PresetPickerDialog(
    title: String,
    items: List<PickerItem>,
    currentId: String,
    onPick: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val colors = BreezyTheme.colors
    var query by remember { mutableStateOf("") }
    var selectedCats by remember { mutableStateOf(setOf<PickerCategory>()) }
    // 弹窗一打开就抢焦点会直接把输入法弹出来，遮住半个列表；先清掉，用户点搜索框时再起来
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus() }

    val filtered = remember(query, selectedCats, items) {
        items.filter { item ->
            val matchQuery = query.isBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                item.subtitle.contains(query, ignoreCase = true)
            val matchCat = selectedCats.isEmpty() || selectedCats.contains(item.category)
            matchQuery && matchCat
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreezyCustomText(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭", tint = colors.onSurface.copy(alpha = 0.6f))
                }
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    BreezyCustomText("搜索预设", fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.4f))
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = colors.onSurface.copy(alpha = 0.5f)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // 分类标签：可多选、再点取消
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerCategory.entries.forEach { cat ->
                    val active = selectedCats.contains(cat)
                    Box(
                        Modifier
                            .background(
                                if (active) colors.primary.copy(alpha = 0.18f) else colors.surface,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                selectedCats = if (active) selectedCats - cat else selectedCats + cat
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        BreezyCustomText(
                            cat.label,
                            fontSize = 12.sp,
                            color = if (active) colors.primary else colors.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.height(380.dp)) {
                items(filtered.size) { index ->
                    val item = filtered[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (item.id == currentId) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onPick(item.id) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BreezyCustomText(item.name, fontSize = 14.sp, maxLines = 1)
                                if (item.category == PickerCategory.CUSTOM) {
                                    Spacer(Modifier.width(6.dp))
                                    BreezyCustomText(
                                        "自定义",
                                        fontSize = 9.sp,
                                        color = colors.primary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            BreezyCustomText(
                                item.subtitle,
                                fontSize = 10.sp,
                                color = colors.onSurface.copy(alpha = 0.45f),
                                maxLines = 1
                            )
                        }
                        if (item.id == currentId) {
                            Icon(Icons.Default.Check, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                        }
                        if (item.deletable && onDelete != null) {
                            IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Default.Delete,
                                    "删除",
                                    tint = colors.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BreezyCustomText(
                                "没有匹配的预设",
                                fontSize = 13.sp,
                                color = colors.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPresetDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, fromCurrent: Boolean, mode: EqMode) -> Unit
) {
    val colors = BreezyTheme.colors
    var name by remember { mutableStateOf("") }
    var fromCurrent by remember { mutableStateOf(true) }
    var pickedMode by remember { mutableStateOf(AudioEffects.mode) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            BreezyCustomText("新建均衡器", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = {
                    BreezyCustomText("名称", fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.4f))
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            BreezyCustomText(
                "类型（创建后固定，想换类型请另建一个）",
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(2.dp))
            OptionRow(
                "图示均衡 · 等分频段推子",
                pickedMode == EqMode.GRAPHIC
            ) { pickedMode = EqMode.GRAPHIC }
            OptionRow(
                "参数均衡 · 中心频率 / Q / 增益",
                pickedMode == EqMode.PARAMETRIC
            ) { pickedMode = EqMode.PARAMETRIC }
            Spacer(Modifier.height(10.dp))
            BreezyCustomText(
                "起始曲线",
                fontSize = 11.sp,
                color = colors.onSurface.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(2.dp))
            OptionRow("从当前曲线复制", fromCurrent) { fromCurrent = true }
            OptionRow("全新（平直曲线）", !fromCurrent) { fromCurrent = false }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BreezyButton("取消", onDismiss, style = BreezyButtonStyle.Text, modifier = Modifier.weight(1f))
                BreezyButton(
                    "创建",
                    { onConfirm(name, fromCurrent, pickedMode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = BreezyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(colors.onSurface.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(if (selected) 18.dp else 0.dp)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(Modifier.size(7.dp).background(colors.background, CircleShape))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        BreezyCustomText(text, fontSize = 13.sp)
    }
}

@Composable
private fun EffectsMenuDialog(
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onRestore: () -> Unit,
    onReset: () -> Unit
) {
    val colors = BreezyTheme.colors
    val canRestore = AudioEffects.isCurrentBuiltin() && AudioEffects.modified

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(24.dp))
                .padding(14.dp)
        ) {
            MenuRow("新建均衡器") { onNew() }
            MenuRow("还原当前预设", enabled = canRestore, onClick = onRestore)
            MenuRow("全部重置") { onReset() }
            Spacer(Modifier.height(4.dp))
            BreezyButton("取消", onDismiss, style = BreezyButtonStyle.Text, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MenuRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = BreezyTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        BreezyCustomText(
            text,
            fontSize = 14.sp,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.3f)
        )
    }
}

// ══════════════════════════════════════════════════
// 小部件
// ══════════════════════════════════════════════════

@Composable
private fun TabChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = BreezyTheme.colors
    Box(
        modifier = modifier
            .background(
                if (selected) colors.primary.copy(alpha = 0.18f) else colors.surface,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BreezyCustomText(
            text,
            fontSize = 13.sp,
            color = if (selected) colors.primary else colors.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * 频段类型的示意图标：用一条 12×10 的折线画出滤波器的形状。
 * 峰值是中间鼓起的小包，低架是左边抬起、右边落地，高架反过来——
 * 比"峰值/低架/高架"四个字直观得多。
 */
@Composable
private fun TypeGlyph(type: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path()
        val midY = h * 0.55f
        when (type) {
            BreezyDspProcessor.TYPE_LOW_SHELF -> {
                path.moveTo(0f, h * 0.15f)
                path.lineTo(w * 0.45f, h * 0.15f)
                path.cubicTo(w * 0.62f, h * 0.15f, w * 0.6f, midY, w * 0.78f, midY)
                path.lineTo(w, midY)
            }
            BreezyDspProcessor.TYPE_HIGH_SHELF -> {
                path.moveTo(0f, midY)
                path.lineTo(w * 0.22f, midY)
                path.cubicTo(w * 0.4f, midY, w * 0.38f, h * 0.15f, w * 0.55f, h * 0.15f)
                path.lineTo(w, h * 0.15f)
            }
            else -> {
                path.moveTo(0f, midY)
                path.cubicTo(w * 0.28f, midY, w * 0.34f, h * 0.1f, w * 0.5f, h * 0.1f)
                path.cubicTo(w * 0.66f, h * 0.1f, w * 0.72f, midY, w, midY)
            }
        }
        drawPath(path, color = color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun typeLabelOf(type: Int): String = when (type) {
    BreezyDspProcessor.TYPE_LOW_SHELF -> "低架"
    BreezyDspProcessor.TYPE_HIGH_SHELF -> "高架"
    else -> "峰值"
}

private fun typeHintOf(type: Int): String = when (type) {
    BreezyDspProcessor.TYPE_LOW_SHELF -> "低频整体抬起或压低，转折点在中心频率"
    BreezyDspProcessor.TYPE_HIGH_SHELF -> "高频整体抬起或压低，转折点在中心频率"
    else -> "只影响中心频率附近的一段，Q 越大影响越窄"
}

@Composable
private fun ParamTypeSelector(
    type: Int,
    enabled: Boolean,
    accent: Color,
    onPick: (Int) -> Unit
) {
    val colors = BreezyTheme.colors
    var open by remember { mutableStateOf(false) }
    val fg = if (enabled) accent else colors.onSurface.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) accent.copy(alpha = 0.14f) else colors.surface, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { open = true }
            .padding(start = 7.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeGlyph(
            type = type,
            color = fg,
            modifier = Modifier
                .size(width = 14.dp, height = 11.dp)
        )
        Spacer(Modifier.width(5.dp))
        BreezyCustomText(typeLabelOf(type), fontSize = 11.sp, color = fg, maxLines = 1)
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = fg.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp)
        )
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.background)
                    .padding(vertical = 16.dp, horizontal = 8.dp)
            ) {
                BreezyCustomText(
                    "频段类型",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    BreezyDspProcessor.TYPE_PEAK,
                    BreezyDspProcessor.TYPE_LOW_SHELF,
                    BreezyDspProcessor.TYPE_HIGH_SHELF
                ).forEach { t ->
                    val selected = t == type
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                onPick(t)
                                open = false
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TypeGlyph(
                            type = t,
                            color = if (selected) accent else colors.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(width = 18.dp, height = 14.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            BreezyCustomText(
                                typeLabelOf(t),
                                fontSize = 14.sp,
                                color = if (selected) accent else colors.onSurface,
                                maxLines = 1
                            )
                            BreezyCustomText(
                                typeHintOf(t),
                                fontSize = 10.sp,
                                color = colors.onSurface.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = BreezyTheme.colors
    Box(
        Modifier
            .background(
                if (active) colors.primary.copy(alpha = 0.16f) else colors.surface,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        BreezyCustomText(
            text,
            fontSize = 10.sp,
            color = if (active) colors.primary else colors.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun LockButton(locked: Boolean, onToggle: () -> Unit) {
    val colors = BreezyTheme.colors
    Box(
        Modifier
            .size(44.dp)
            .background(
                if (locked) colors.primary.copy(alpha = 0.16f) else colors.surface,
                RoundedCornerShape(14.dp)
            )
            .clickable { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (locked) "解锁" else "锁定",
            tint = if (locked) colors.primary else colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SwitchSmall(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = BreezyTheme.colors
    Box(
        Modifier
            .width(44.dp)
            .height(24.dp)
            .background(
                if (checked) colors.primary else colors.onSurface.copy(alpha = 0.18f),
                CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(18.dp)
                .background(if (checked) colors.background else colors.onSurface.copy(alpha = 0.7f), CircleShape)
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueText: String,
    enabled: Boolean,
    accent: Color? = null,
    onChange: (Float) -> Unit
) {
    val colors = BreezyTheme.colors
    val tint = accent ?: colors.primary
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BreezyCustomText(label, fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            BreezyCustomText(valueText, fontSize = 12.sp, color = tint.copy(alpha = 0.9f))
        }
        // 手柄做成竖立的圆角矩形（1:2.6），比圆点更好抓，也更像硬件推子
        val thumbColor = if (enabled) tint else colors.onSurface.copy(alpha = 0.35f)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = tint.copy(alpha = if (enabled) 1f else 0.35f),
                inactiveTrackColor = colors.onSurface.copy(alpha = 0.15f)
            ),
            thumb = {
                Box(
                    Modifier
                        .size(width = 9.dp, height = 24.dp)
                        .background(thumbColor, RoundedCornerShape(3.dp))
                )
            }
        )
    }
}

/**
 * 竖向推子：Canvas 自绘轨道与滑块，用 detectVerticalDragGestures 计算 dB 增量。
 * 宽度由调用方决定（16 段时每段只有 ~60px，写死宽度会挤爆一行）。
 */
@Composable
private fun VerticalFader(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onHorizontalDrag: ((Float) -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    val primary = BreezyTheme.colors.primary
    val bgColor = BreezyTheme.colors.background
    val trackColor = BreezyTheme.colors.onSurface.copy(alpha = if (enabled) 0.16f else 0.08f)
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnHorizontalDrag by rememberUpdatedState(onHorizontalDrag)
    var dragStart by remember { mutableStateOf(value) }

    Canvas(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // 在 Initial 阶段就吃掉按下事件：
                    // 滑块躺在 LazyColumn 里，如果等父级滚动容器先判定，
                    // 竖向拖动会被它当成列表滚动抢走，表现就是「划不动、只动一点点」。
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    val h = size.height.toFloat()
                    if (h <= 0f) return@awaitEachGesture
                    var startValue = currentValue
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var totalX = 0f
                    var totalY = 0f
                    // 0 = 还没判定方向，1 = 竖向调值，2 = 横向交给外层滚动
                    var axis = 0
                    dragStart = startValue
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val dx = change.position.x - lastX
                        val dy = change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        totalX += dx
                        totalY += dy
                        if (axis == 0 && (kotlin.math.abs(totalX) > 8f || kotlin.math.abs(totalY) > 8f)) {
                            // 起手方向决定这条手势归谁：竖向调增益，横向交给外层的横向滚动。
                            // 不判定方向的话，横向滑动会被推子吃掉（每段都跟着动），
                            // 用户根本推不动那一排推子。
                            axis = if (kotlin.math.abs(totalY) >= kotlin.math.abs(totalX)) 1 else 2
                        }
                        if (axis == 2) {
                            change.consume()
                            currentOnHorizontalDrag?.invoke(dx)
                            continue
                        }
                        change.consume()
                        if (axis == 0) continue
                        val next = (startValue - dy / h * span).coerceIn(range.start, range.endInclusive)
                        startValue = next
                        currentOnChange(next)
                    }
                }
            }
    ) {
        val trackW = 5.dp.toPx()
        val cx = size.width / 2f
        val thumbY = size.height * (1f - fraction)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(cx - trackW / 2f, 0f),
            size = Size(trackW, size.height),
            cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
        )
        // 0 dB 中线
        drawRoundRect(
            color = trackColor.copy(alpha = 0.5f),
            topLeft = Offset(cx - 7.dp.toPx(), size.height / 2f - 0.5.dp.toPx()),
            size = Size(14.dp.toPx(), 1.dp.toPx())
        )
        val from = minOf(thumbY, size.height / 2f)
        val to = maxOf(thumbY, size.height / 2f)
        drawRoundRect(
            color = (if (enabled) primary else primary.copy(alpha = 0.4f)).copy(alpha = 0.55f),
            topLeft = Offset(cx - trackW / 2f, from),
            size = Size(trackW, to - from),
            cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
        )
        // 圆角矩形手柄：横 13dp、竖 9dp，圆心换成矩形后更好按，也不会遮住相邻频段
        val thumbW = 13.dp.toPx()
        val thumbH = 9.dp.toPx()
        drawRoundRect(
            color = if (enabled) primary else primary.copy(alpha = 0.4f),
            topLeft = Offset(cx - thumbW / 2f, thumbY - thumbH / 2f),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )

    }
}

/**
 * 参数均衡的频段配色。
 * 十段用十个色相，相邻色相拉开 30° 以上，保证在浅色和深色主题下都能分辨；
 * 只有曲线的颜色和频段对得上，用户才能一眼看出"这一坨是哪个频段抬起来的"。
 */
private val ParamBandColors = listOf(
    Color(0xFFE05252), // 红
    Color(0xFFE0833A), // 橙
    Color(0xFFC9A227), // 金
    Color(0xFF7FA828), // 草绿
    Color(0xFF2F9E63), // 绿
    Color(0xFF2496A8), // 青
    Color(0xFF3B82D9), // 蓝
    Color(0xFF6366E0), // 靛
    Color(0xFF9C5AE0), // 紫
    Color(0xFFCE4FA0)  // 品红
)

private fun bandColor(index: Int): Color = ParamBandColors[index % ParamBandColors.size]

private fun formatDb(db: Float): String {
    val rounded = (db * 10).roundToInt() / 10f
    val text = String.format("%.1f", rounded)
    return if (rounded > 0f) "+$text" else text
}

// ══════════════════════════════════════════════════
// 插值小工具（本文件内部使用）
// ══════════════════════════════════════════════════

private fun lerpDp(start: androidx.compose.ui.unit.Dp, stop: androidx.compose.ui.unit.Dp, t: Float) =
    androidx.compose.ui.unit.Dp(start.value + (stop.value - start.value) * t.coerceIn(0f, 1f))

private fun lerpColor(start: androidx.compose.ui.graphics.Color, stop: androidx.compose.ui.graphics.Color, t: Float) =
    androidx.compose.ui.graphics.lerp(start, stop, t.coerceIn(0f, 1f))

/** 把节点内的触摸点换算成屏幕坐标；节点自身在拖动中移动，局部坐标不能直接用来求位移。 */
private fun windowY(coords: LayoutCoordinates?, local: androidx.compose.ui.geometry.Offset): Float {
    if (coords == null) return local.y
    return coords.localToWindow(local).y
}

/**
 * 段数弹窗。
 * 减段是破坏性的（末尾几段的增益会丢），所以先弹一次确认；
 * 加段只是追加默认值，直接生效。
 */
@Composable
private fun BandCountDialog(onDismiss: () -> Unit) {
    val colors = BreezyTheme.colors
    val context = LocalContext.current
    val limit = AudioEffects.bandCountLimit()
    val current = AudioEffects.currentBandCount()
    var target by remember { mutableIntStateOf(current) }
    var confirming by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.background)
                .padding(20.dp)
        ) {
            BreezyCustomText("调整段数", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            BreezyCustomText(
                if (confirming) {
                    "减少到 $target 段会删除末尾 ${current - target} 段的设置，这部分无法恢复。"
                } else {
                    "当前 $current 段，最多 $limit 段。加段会追加在末尾，已有段不变。"
                },
                fontSize = 12.sp,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))

            if (!confirming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BreezyCustomText("段数", fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.weight(1f))
                    BreezyCustomText("$target", fontSize = 15.sp, color = colors.primary)
                }
                Slider(
                    value = target.toFloat(),
                    onValueChange = { target = it.roundToInt() },
                    valueRange = 3f..limit.toFloat(),
                    steps = (limit - 3) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Transparent,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.onSurface.copy(alpha = 0.15f)
                    ),
                    thumb = {
                        Box(
                            Modifier
                                .size(width = 9.dp, height = 24.dp)
                                .background(colors.primary, RoundedCornerShape(3.dp))
                        )
                    }
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.onSurface.copy(alpha = 0.05f))
                            .clickable { target = (target - 1).coerceAtLeast(3) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BreezyCustomText("− 1 段", fontSize = 12.sp, color = colors.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.onSurface.copy(alpha = 0.05f))
                            .clickable { target = (target + 1).coerceAtMost(limit) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BreezyCustomText("+ 1 段", fontSize = 12.sp, color = colors.onSurface)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BreezyButton(
                        text = "取消",
                        onClick = onDismiss,
                        style = BreezyButtonStyle.Outlined,
                        fullWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                    BreezyButton(
                        text = "确定",
                        onClick = {
                            if (target < current) confirming = true
                            else {
                                AudioEffects.setBandCountPreserving(context, target)
                                onDismiss()
                            }
                        },
                        fullWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = target != current
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BreezyButton(
                        text = "取消",
                        onClick = { confirming = false },
                        style = BreezyButtonStyle.Outlined,
                        fullWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                    BreezyButton(
                        text = "删除并继续",
                        onClick = {
                            AudioEffects.setBandCountPreserving(context, target)
                            onDismiss()
                        },
                        style = BreezyButtonStyle.Danger,
                        fullWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 系统「上滑回桌面」手势区高度：底栏热区避开这一条，避免起手就被系统吃掉 */
private val GESTURE_GUARD = 30.dp
