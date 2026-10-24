package com.breezy.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.breezy.music.model.*
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistHistoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<PlaylistHistory.Entry>>(emptyList()) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedTimestamps by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var detailEntry by remember { mutableStateOf<PlaylistHistory.Entry?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            entries = PlaylistHistory.load(context)
        }
    }

    // 详情子页面
    if (detailEntry != null) {
        BackHandler { detailEntry = null }
        HistoryDetailPage(
            entry = detailEntry!!,
            onBack = { detailEntry = null }
        )
        return
    }

    // 返回键：优先退出多选
    val historyBackCtx = LocalContext.current
    BackHandler(enabled = multiSelectMode
        && com.breezy.music.model.Preferences.getBackBehavior(historyBackCtx) == "smart") {
        multiSelectMode = false; selectedTimestamps = emptySet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(BreezyTheme.colors.background)) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (multiSelectMode) { multiSelectMode = false; selectedTimestamps = emptySet() }
                else onBack()
            }) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            BreezyCustomText("历史记录", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = BreezyTheme.colors.onSurface, modifier = Modifier.weight(1f))
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BreezyCustomText("暂无历史记录", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries) { entry ->
                    val firstSong = entry.songs.firstOrNull()
                    val bgColor = if (entry.timestamp in selectedTimestamps)
                        BreezyTheme.colors.primary.copy(alpha = 0.12f)
                    else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .combinedClickable(
                                onClick = {
                                    if (multiSelectMode) {
                                        selectedTimestamps = if (entry.timestamp in selectedTimestamps)
                                            selectedTimestamps - entry.timestamp
                                        else selectedTimestamps + entry.timestamp
                                    } else {
                                        detailEntry = entry
                                    }
                                },
                                onLongClick = {
                                    if (!multiSelectMode) {
                                        multiSelectMode = true
                                        selectedTimestamps = setOf(entry.timestamp)
                                    }
                                }
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：三封面叠加
                        Box(modifier = Modifier.size(56.dp)) {
                            entry.songs.take(3).forEachIndexed { i, s ->
                                val offsetX = (i * 6).dp
                                val offsetY = (i * 6).dp
                                val size = 44.dp
                                Box(
                                    modifier = Modifier
                                        .offset(x = offsetX, y = offsetY)
                                        .size(size)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BreezyTheme.colors.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val cover = remember(s.path) { mutableStateOf<ImageBitmap?>(null) }
                                    LaunchedEffect(s.path) {
                                        withContext(Dispatchers.IO) {
                                            cover.value = AlbumArtCache.get(context, s.path)
                                        }
                                    }
                                    if (cover.value != null) {
                                        Image(cover.value!!, null, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                                    } else {
                                        Icon(Icons.Default.MusicNote, null,
                                            tint = BreezyTheme.colors.primary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            if (firstSong != null) {
                                BreezyCustomText(firstSong.title, fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium, color = BreezyTheme.colors.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BreezyCustomText("${firstSong.artist} · ${firstSong.album}",
                                    fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            BreezyCustomText("共 ${entry.songs.size} 首",
                                fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f))
                            BreezyCustomText(
                                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                fontSize = 11.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.3f))
                        }

                        // 右侧：设为当前按钮
                        BreezyCustomText("设为当前", fontSize = 13.sp,
                            color = BreezyTheme.colors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    // 先保存当前列表到历史
                                    if (PlaybackState.playlist.isNotEmpty()) {
                                        PlaylistHistory.save(context, PlaybackState.playlist)
                                    }
                                    // 转换并设为当前列表
                                    val dao = MusicDatabase.getInstance(context).musicDao()
                                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                        val allSongs = dao.getAllSongs()
                                        val songs = entry.songs.mapNotNull { hs ->
                                            allSongs.find { it.path == hs.path }
                                        }
                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                            PlaybackState.playList(songs, entry.currentIndex.coerceIn(0, songs.lastIndex))
                                        }
                                    }
                                }
                                .background(BreezyTheme.colors.primary.copy(alpha = 0.1f))
                                .padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }

        // 底部多选工具栏
        if (multiSelectMode) {
            MultiSelectDock(
                onCancel = { multiSelectMode = false; selectedTimestamps = emptySet() },
                actions = {
                    IconButton(onClick = {
                        selectedTimestamps.forEach { PlaylistHistory.delete(context, it) }
                        entries = entries.filter { it.timestamp !in selectedTimestamps }
                        multiSelectMode = false; selectedTimestamps = emptySet()
                    }) {
                        Icon(Icons.Default.Delete, "删除", tint = BreezyTheme.colors.error)
                    }
                }
            )
        }
    } // end Box
    }
}

@Composable
private fun HistoryDetailPage(entry: PlaylistHistory.Entry, onBack: () -> Unit) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BreezyTheme.colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                BreezyCustomText("历史歌单", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = BreezyTheme.colors.onSurface)
                BreezyCustomText("共 ${entry.songs.size} 首 · ${
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(entry.timestamp))
                }", fontSize = 12.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
            }
            // 设为当前
            BreezyCustomText("设为当前", fontSize = 13.sp,
                color = BreezyTheme.colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (PlaybackState.playlist.isNotEmpty()) {
                            PlaylistHistory.save(context, PlaybackState.playlist, PlaybackState.currentIndex)
                        }
                        val dao = MusicDatabase.getInstance(context).musicDao()
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            val allSongs = dao.getAllSongs()
                            val songs = entry.songs.mapNotNull { hs -> allSongs.find { it.path == hs.path } }
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                PlaybackState.playList(songs, entry.currentIndex.coerceIn(0, songs.lastIndex))
                            }
                        }
                    }
                    .background(BreezyTheme.colors.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(entry.songs.size) { index ->
                val s = entry.songs[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreezyCustomText("${index + 1}", fontSize = 13.sp,
                        color = BreezyTheme.colors.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        BreezyCustomText(s.title, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, color = BreezyTheme.colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BreezyCustomText(s.artist, fontSize = 13.sp,
                            color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val sec = s.duration / 1000
                    BreezyCustomText("${sec/60}:${(sec%60).toString().padStart(2,'0')}",
                        fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.4f))
                }
            }
        }
    }
}
