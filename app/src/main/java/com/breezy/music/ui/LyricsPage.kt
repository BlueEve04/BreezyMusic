package com.breezy.music.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breezy.music.model.LyricParser
import com.breezy.music.model.PlaybackState
import com.breezy.music.model.Preferences
import com.breezy.music.model.SongEntity
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌词滚动页面
 *
 * - 从与音乐文件同名的 .lrc 文件读取歌词
 * - 按时间轴排序，当前播放歌词居中高亮（带圆角矩形背景渐入渐散）
 * - 多轨歌词（原文/翻译/罗马音）同时间排布
 */
@Composable
fun LyricsPage() {
    val context = LocalContext.current
    val song = PlaybackState.currentSong
    val progress = PlaybackState.progress
    val songDuration = song?.duration ?: 1L
    val currentMs = (progress * songDuration).toLong()

    var lyricsGrouped by remember { mutableStateOf<List<Pair<Long, List<LyricParser.LyricLine>>>>(emptyList()) }
    var noLyrics by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // 加载 LRC 文件
    LaunchedEffect(song?.path) {
        if (song == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val lrcUri = try {
                val musicUri = song.path
                // 替换文件扩展名为 .lrc（支持各种音频格式）
                val musicExts = listOf(".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac", ".wma", ".MP3", ".FLAC", ".WAV", ".OGG", ".M4A", ".AAC", ".WMA")
                var lrc = musicUri
                for (ext in musicExts) {
                    if (lrc.endsWith(ext)) {
                        lrc = lrc.dropLast(ext.length) + ".lrc"
                        break
                    }
                }
                Log.i("LyricsPage", "musicUri=$musicUri → lrcUri=$lrc")
                lrc
            } catch (e: Exception) {
                Log.w("LyricsPage", "Failed to build LRC URI", e)
                null
            }
            if (lrcUri != null) {
                Log.i("LyricsPage", "Attempting to parse LRC: $lrcUri")
                val lines = LyricParser.parse(context, lrcUri)
                Log.i("LyricsPage", "Parsed ${lines.size} lyric lines")
                if (lines.isNotEmpty()) {
                    lyricsGrouped = LyricParser.groupByTime(lines)
                } else {
                    noLyrics = true
                }
            } else {
                noLyrics = true
            }
        }
        loaded = true
    }

    // 自动滚动到当前歌词。用户手动滑动后暂停 5 秒（每次滑动都重新计时），
    // 暂停期间只高亮、不滚动——否则刚滑到想看的地方就被拽回去。
    val listState = rememberLazyListState()
    var autoScrollPaused by remember { mutableStateOf(false) }
    var pauseToken by remember { mutableIntStateOf(0) }
    // 回正延迟由设置页决定；进页面时读一次即可，改完设置回到这里会重新组合
    val resumeSeconds = remember { Preferences.getLyricsResumeSeconds(context) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // 用户手动滑动：用拖动交互本身判定。程序发起的 animateScrollToItem 同样会把
    // isScrollInProgress 置真，靠布尔标记区分很容易漏判。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                autoScrollPaused = true
                pauseToken++
            }
        }
    }

    LaunchedEffect(pauseToken) {
        if (pauseToken == 0) return@LaunchedEffect
        delay(resumeSeconds * 1000L)
        autoScrollPaused = false
    }

    // 只在"当前行确实换了"时才滚动。原来以 currentMs（每 250ms 变一次）为 key，
    // 滚动动画每帧都被取消，复位语句也一起被取消，isProgrammaticScroll 永久卡在 true，
    // 于是用户的滑动被当成程序滚动而忽略——表现就是"一滑就跑回去"。
    val currentIdx = remember(currentMs, lyricsGrouped) {
        if (lyricsGrouped.isEmpty()) -1 else lyricsGrouped.indexOfLast { it.first <= currentMs }
    }

    LaunchedEffect(currentIdx, lyricsGrouped.size, autoScrollPaused) {
        if (currentIdx < 0 || autoScrollPaused) return@LaunchedEffect
        isProgrammaticScroll = true
        try {
            listState.animateScrollToItem(currentIdx)
        } finally {
            isProgrammaticScroll = false
        }
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BreezyCustomText("加载歌词中...", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
        }
    } else if (noLyrics) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BreezyCustomText("未找到歌词文件", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            contentPadding = PaddingValues(vertical = 200.dp) // 上下留白使当前歌词可居中
        ) {
            itemsIndexed(lyricsGrouped) { index, (timeMs, lines) ->
                LyricGroup(
                    lines = lines,
                    isCurrent = timeMs <= currentMs &&
                        (index == lyricsGrouped.lastIndex || lyricsGrouped[index + 1].first > currentMs),
                    index = index,
                    timeMs = timeMs,
                    onSeek = { ms ->
                        val dur = songDuration
                        if (dur > 0) {
                            PlaybackState.seekTo((ms.toFloat() / dur).coerceIn(0f, 1f))
                        }
                    }
                )
            }
        }
    }
}

/** 一组同时间的歌词（原文 + 翻译 + 罗马音） */
@Composable
private fun LyricGroup(
    lines: List<LyricParser.LyricLine>,
    isCurrent: Boolean,
    index: Int,
    timeMs: Long,
    onSeek: (Long) -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isCurrent)
            BreezyTheme.colors.primary.copy(alpha = 0.15f)
        else
            Color.Transparent,
        label = "lyricBg"
    )
    val textColor = if (isCurrent)
        BreezyTheme.colors.primary
    else
        BreezyTheme.colors.onSurface.copy(alpha = 0.45f)

    val baseFontSize = if (isCurrent) 18.sp else 14.sp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(if (isCurrent) 12.dp else 0.dp))
            .background(bgColor)
            .clickable { onSeek(timeMs) }
            .padding(horizontal = 12.dp, vertical = if (isCurrent) 8.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        lines.forEachIndexed { i, line ->
            BreezyCustomText(
                text = line.text,
                fontSize = if (i == 0) baseFontSize else (baseFontSize.value * 0.75f).sp,
                fontWeight = if (isCurrent && i == 0) FontWeight.Bold else FontWeight.Normal,
                color = if (i == 0) textColor else textColor.copy(alpha = 0.65f),
                align = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
