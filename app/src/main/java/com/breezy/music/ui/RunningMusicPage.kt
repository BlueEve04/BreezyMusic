package com.breezy.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.breezy.music.model.BpmRepository
import com.breezy.music.model.CadenceTracker
import com.breezy.music.model.FolderEntity
import com.breezy.music.model.MusicDatabase
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.Preferences
import com.breezy.music.model.RunningMusicGenerator
import com.breezy.music.model.RunningMusicGenerator.RunningMusicConfig
import com.breezy.music.model.RunningMusicGenerator.Source
import com.breezy.music.model.SongEntity
import com.breezy.music.util.M3u8Parser
import com.breezy.music.util.SongSortUtil
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运动音乐页面
 *
 * 交互流程（对应作业要求中的“分析曲库 BPM → 读取当前步频 → 生成定长歌单”）：
 *  1. 【曲库分析】批量分析全部歌曲 BPM（可断点续跑、可取消），并实时显示进度；
 *  2. 【步频】通过计步传感器读取最近 8 秒的步频（SPM），也允许手动指定目标步频；
 *  3. 【匹配】从「全部音乐 / 文件夹 / 歌单」中选择曲库范围，设定容差与时长；
 *  4. 【生成】贪心最邻近算法生成 BPM 平滑过渡的定长播放列表，直接播放。
 */
@Composable
fun RunningMusicPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }
    var playlistUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 设置项（与 Preferences 同步）
    var targetSpm by remember { mutableStateOf(Preferences.getRunningTargetSpm(context)) }
    var tolerance by remember { mutableStateOf(Preferences.getRunningTolerance(context)) }
    var durationMinutes by remember { mutableStateOf(Preferences.getRunningDuration(context)) }
    var sourceKey by remember { mutableStateOf(Preferences.getRunningSource(context)) }
    var useCadence by remember { mutableStateOf(Preferences.getRunningUseCadence(context)) }
    var halfTime by remember { mutableStateOf(false) }
    var smoothChain by remember { mutableStateOf(true) }

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }

    var result by remember { mutableStateOf<RunningMusicGenerator.Result?>(null) }
    var generating by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val cadenceSpm = CadenceTracker.cadenceSpm
    val isTracking = CadenceTracker.isTracking

    // 运行时权限：计步器需要 ACTIVITY_RECOGNITION（Android 10+）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CadenceTracker.start(context) else errorMsg = "未授予活动识别权限，无法读取步频"
    }

    fun startTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        CadenceTracker.start(context)
    }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = MusicDatabase.getInstance(context)
            songs = db.musicDao().getAllSongs()
            folders = db.musicDao().getAllFolders()
            playlistUris = Preferences.getM3u8Uris(context).toList()
            BpmRepository.refreshStats(context)
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        if (useCadence) startTracking()
    }

    // 曲库变化（切换到某文件夹/歌单）时更新目标范围
    LaunchedEffect(sourceKey, selectedFolder, selectedPlaylist) {
        Preferences.setRunningSource(context, sourceKey)
    }

    /** 生成播放列表 */
    fun generate() {
        scope.launch {
            generating = true
            errorMsg = null
            try {
                var pool: List<SongEntity> = withContext(Dispatchers.IO) {
                    when (sourceKey) {
                        Source.FOLDER.name -> {
                            val folder = selectedFolder
                            if (folder.isNullOrEmpty()) songs
                            else songs.filter { it.folderPath == folder }
                        }
                        Source.PLAYLIST.name -> {
                            val uri = selectedPlaylist
                            if (uri.isNullOrEmpty()) {
                                songs
                            } else {
                                val info = M3u8Parser.parse(context, Uri.parse(uri))
                                info.songs
                            }
                        }
                        else -> songs
                    }
                }
                if (pool.isEmpty()) {
                    errorMsg = "曲库为空，请先在「文件夹」中添加音乐目录"
                    return@launch
                }
                // 若选中的范围内还有未分析 BPM 的歌曲，先补齐分析
                if (pool.any { it.bpm == 0f }) {
                    BpmRepository.analyzeAll(context, onlySongs = pool)?.join()
                    pool = withContext(Dispatchers.IO) { MusicDatabase.getInstance(context).musicDao().getAllSongs() }
                }
                val spm = if (useCadence && cadenceSpm > 0f) cadenceSpm.toInt() else targetSpm
                val config = RunningMusicConfig(
                    targetSpm = spm,
                    toleranceBpm = tolerance,
                    durationMinutes = durationMinutes,
                    smoothBpm = smoothChain,
                    halfTime = halfTime,
                    source = runCatching { Source.valueOf(sourceKey) }.getOrDefault(Source.ALL),
                    sourceKey = selectedFolder ?: selectedPlaylist
                )
                val generated = withContext(Dispatchers.Default) {
                    RunningMusicGenerator.generate(pool.filter { it.bpm > 0f }, config)
                }
                result = generated
                if (generated.isEmpty) {
                    errorMsg = "没有找到与目标 BPM 接近的歌曲，可适当放宽容差"
                }
            } catch (e: Exception) {
                errorMsg = "生成失败：${e.message}"
            } finally {
                generating = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BreezyTheme.colors.background)) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 44.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            Icon(
                Icons.Default.DirectionsRun,
                null,
                tint = BreezyTheme.colors.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            BreezyCustomText("运动音乐", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 200.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. 步频 ──
            item {
                SectionCard(title = "当前步频") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreezyCustomText(
                            text = if (cadenceSpm > 0f) "${cadenceSpm.toInt()}" else "--",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = BreezyTheme.colors.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        BreezyCustomText(
                            text = "SPM",
                            fontSize = 14.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 14.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BreezyCustomText(
                                text = if (isTracking) "感知中" else "未开启",
                                fontSize = 12.sp,
                                color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                            Switch(
                                checked = isTracking,
                                onCheckedChange = { on ->
                                    if (on) startTracking() else CadenceTracker.stop()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreezyCustomText("使用实时步频作为目标", fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = useCadence,
                            onCheckedChange = {
                                useCadence = it
                                Preferences.setRunningUseCadence(context, it)
                                if (it) startTracking() else CadenceTracker.stop()
                            }
                        )
                    }
                    if (!useCadence) {
                        SliderRow(
                            label = "目标步频",
                            valueText = "$targetSpm SPM",
                            value = targetSpm.toFloat(),
                            range = 120f..200f,
                            steps = 39,
                            onChange = {
                                targetSpm = it.toInt()
                                Preferences.setRunningTargetSpm(context, targetSpm)
                            }
                        )
                    }
                }
            }

            // ── 2. 匹配设置 ──
            item {
                SectionCard(title = "匹配设置") {
                    SliderRow(
                        label = "BPM 容差",
                        valueText = "±$tolerance",
                        value = tolerance.toFloat(),
                        range = 2f..30f,
                        steps = 27,
                        onChange = {
                            tolerance = it.toInt()
                            Preferences.setRunningTolerance(context, tolerance)
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    BreezyCustomText(
                        "列表时长",
                        fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            ChoiceChip(
                                text = "$minutes 分钟",
                                selected = durationMinutes == minutes,
                                onClick = {
                                    durationMinutes = minutes
                                    Preferences.setRunningDuration(context, minutes)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreezyCustomText("BPM 平滑过渡", fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = smoothChain, onCheckedChange = { smoothChain = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreezyCustomText("半拍匹配（曲速 = SPM/2）", fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = halfTime, onCheckedChange = { halfTime = it })
                    }
                }
            }

            // ── 3. 曲库来源 ──
            item {
                SectionCard(title = "曲库来源") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Source.entries.forEach { s ->
                            ChoiceChip(
                                text = s.label,
                                selected = sourceKey == s.name,
                                onClick = { sourceKey = s.name }
                            )
                        }
                    }
                    if (sourceKey == Source.FOLDER.name) {
                        Spacer(Modifier.height(8.dp))
                        if (folders.isEmpty()) {
                            Hint("还没有添加文件夹")
                        } else {
                            folders.take(6).forEach { folder ->
                                val name = folder.path.substringAfterLast('/').ifEmpty { folder.path }
                                ChoiceRow(
                                    text = name,
                                    selected = selectedFolder == folder.path,
                                    onClick = { selectedFolder = folder.path }
                                )
                            }
                        }
                    }
                    if (sourceKey == Source.PLAYLIST.name) {
                        Spacer(Modifier.height(8.dp))
                        if (playlistUris.isEmpty()) {
                            Hint("还没有导入 m3u/m3u8 歌单")
                        } else {
                            playlistUris.take(8).forEach { uri ->
                                val name = Uri.parse(uri).lastPathSegment
                                    ?.substringBeforeLast('.') ?: uri
                                ChoiceRow(
                                    text = name,
                                    selected = selectedPlaylist == uri,
                                    onClick = { selectedPlaylist = uri }
                                )
                            }
                        }
                    }
                }
            }

            // ── 4. 曲库分析 ──
            item {
                val total = BpmRepository.libraryCount
                val done = BpmRepository.analyzedCount
                val analyzing = BpmRepository.isAnalyzing
                SectionCard(title = "曲库 BPM 分析") {
                    BreezyCustomText(
                        text = if (analyzing) "正在分析 ${BpmRepository.currentTitle}" else "已分析 $done / $total 首",
                        fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.height(8.dp))
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = BreezyTheme.colors.primary,
                        trackColor = BreezyTheme.colors.onSurface.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip(
                            text = if (analyzing) "分析中…" else "分析全部",
                            selected = false,
                            enabled = !analyzing,
                            onClick = {
                                scope.launch { BpmRepository.analyzeAll(context) }
                            }
                        )
                        if (analyzing) {
                            ChoiceChip(
                                text = "取消",
                                selected = false,
                                onClick = { BpmRepository.cancel() }
                            )
                        }
                    }
                }
            }

            // ── 5. 生成 ──
            item {
                Column {
                    BreezyCustomText(
                        text = "目标 BPM：${if (useCadence && cadenceSpm > 0) (if (halfTime) cadenceSpm / 2 else cadenceSpm).toInt() else if (halfTime) targetSpm / 2 else targetSpm}",
                        fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.breezy.ui.button.BreezyButton(
                            text = if (generating) "生成中…" else "生成运动歌单",
                            onClick = { generate() },
                            enabled = !generating && !loading,
                            modifier = Modifier.weight(1f),
                            fullWidth = true,
                            loading = generating
                        )
                        result?.let { r ->
                            if (!r.isEmpty) {
                                com.breezy.ui.button.BreezyButton(
                                    text = "播放",
                                    onClick = {
                                        PlaybackState.playList(r.matches.map { it.song }, 0)
                                    },
                                    icon = Icons.Default.PlayArrow
                                )
                            }
                        }
                    }
                    errorMsg?.let {
                        Spacer(Modifier.height(8.dp))
                        BreezyCustomText(
                            text = it,
                            fontSize = 12.sp,
                            color = BreezyTheme.colors.error
                        )
                    }
                }
            }

            // ── 6. 结果 ──
            result?.let { r ->
                if (!r.isEmpty) {
                    item {
                        SectionCard(title = "推荐列表") {
                            BreezyCustomText(
                                text = "共 ${r.matches.size} 首 · 平均 ${r.averageBpm.toInt()} BPM · " +
                                    "约 ${r.totalDurationMs / 60000} 分钟（容差内 ${r.inToleranceCount} 首 / 曲池 ${r.poolSize} 首）",
                                fontSize = 12.sp,
                                color = BreezyTheme.colors.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                    items(r.matches, key = { it.song.path }) { match ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(BreezyTheme.colors.surface)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Speed,
                                null,
                                tint = BreezyTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                BreezyCustomText(
                                    text = match.song.title,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                BreezyCustomText(
                                    text = match.song.artist,
                                    fontSize = 11.sp,
                                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                            BreezyCustomText(
                                text = "${match.song.bpm.toInt()} BPM",
                                fontSize = 12.sp,
                                color = BreezyTheme.colors.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 组件
// ─────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BreezyTheme.colors.surface)
            .padding(14.dp)
    ) {
        BreezyCustomText(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column {
        Row {
            BreezyCustomText(label, fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            BreezyCustomText(valueText, fontSize = 13.sp, color = BreezyTheme.colors.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) BreezyTheme.colors.primary
                else BreezyTheme.colors.onSurface.copy(alpha = 0.08f)
            )
            .then(
                if (enabled) Modifier.clickableNoRipple(onClick) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        BreezyCustomText(
            text = text,
            fontSize = 12.sp,
            color = if (selected) BreezyTheme.colors.onPrimary
            else BreezyTheme.colors.onSurface.copy(alpha = if (enabled) 0.8f else 0.35f)
        )
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) BreezyTheme.colors.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickableNoRipple(onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BreezyCustomText(text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (selected) {
            BreezyCustomText("已选", fontSize = 11.sp, color = BreezyTheme.colors.primary)
        }
    }
}

@Composable
private fun Hint(text: String) {
    BreezyCustomText(text, fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
}

/** 无水波纹点击（列表内使用，减少层叠与性能开销） */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )
