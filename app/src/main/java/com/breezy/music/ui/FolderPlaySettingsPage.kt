package com.breezy.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breezy.music.model.Preferences
import com.breezy.ui.list.BreezyListItem
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme

/**
 * 播放设置页面
 *
 * ── 文件夹播放策略 ──
 * - 点击单曲：仅将该曲加入播放列表
 * - 点击单曲：将同目录下所有歌曲加入播放列表（不含子目录）
 *
 * ── 替换播放列表 ──
 * 仅在上项选择「整个文件夹加入」时可操作
 * - 是：清空原播放列表，替换为此目录歌曲
 * - 否：将目录歌曲追加到播放列表尾部
 */
@Composable
fun FolderPlaySettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current

    var folderPlayStrategy by remember {
        mutableStateOf(Preferences.getFolderPlayStrategy(context))
    }
    var folderPlayReplace by remember {
        mutableStateOf(Preferences.getFolderPlayReplace(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BreezyTheme.colors.background)
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 44.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "返回", tint = BreezyTheme.colors.onSurface)
            }
            BreezyCustomText(
                text = "播放设置",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = BreezyTheme.colors.onSurface
            )
        }

        // ── 设置项 ──
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. 文件夹播放策略
            BreezyListItem(
                title = "文件夹播放策略",
                subtitle = when (folderPlayStrategy) {
                    "single" -> "仅加入点击的单曲"
                    "folder" -> "加入同目录所有歌曲"
                    else -> "仅加入点击的单曲"
                },
                leadingIcon = Icons.Default.PlaylistPlay,
                trailingText = when (folderPlayStrategy) {
                    "single" -> "单曲"
                    "folder" -> "目录"
                    else -> "单曲"
                },
                showChevron = true,
                onClick = {
                    folderPlayStrategy = if (folderPlayStrategy == "single") "folder" else "single"
                    Preferences.setFolderPlayStrategy(context, folderPlayStrategy)
                }
            )

            // 说明文字
            BreezyCustomText(
                text = if (folderPlayStrategy == "folder")
                    "在文件夹页面中点击任意单曲时，将自动把该文件所在目录下的所有音乐文件（不含子目录）加入播放列表。"
                else
                    "在文件夹页面中点击单曲时，仅将该曲目加入播放列表。",
                fontSize = 13.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(8.dp))

            // 2. 替换播放列表（仅文件夹模式下可操作）
            val replaceEnabled = folderPlayStrategy == "folder"
            Box(modifier = Modifier.alpha(if (replaceEnabled) 1f else 0.4f)) {
                BreezyListItem(
                    title = "替换播放列表",
                    subtitle = if (folderPlayReplace)
                        "清空原列表，替换为目录歌曲"
                    else
                        "将目录歌曲追加到列表尾部",
                    leadingIcon = Icons.Default.SwapHoriz,
                    trailingText = if (folderPlayReplace) "是" else "否",
                    showChevron = true,
                    onClick = {
                        if (replaceEnabled) {
                            folderPlayReplace = !folderPlayReplace
                            Preferences.setFolderPlayReplace(context, folderPlayReplace)
                        }
                    }
                )
            }

            // 说明
            if (folderPlayStrategy == "folder") {
                BreezyCustomText(
                    text = if (folderPlayReplace)
                        "开启后，每次点击歌曲将先清空当前播放列表，再将该目录歌曲加入。"
                    else
                        "关闭后，目录歌曲将追加到当前播放列表尾部，不清除已有歌曲。",
                    fontSize = 13.sp,
                    color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 分界线 ──
            HorizontalDivider(
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.12f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 3. 歌单播放替换
            var playlistReplace by remember {
                mutableStateOf(Preferences.getPlaylistPlayReplace(context))
            }
            BreezyListItem(
                title = "歌单播放替换",
                subtitle = if (playlistReplace)
                    "播放歌单时替换现有队列并记录历史"
                else
                    "播放歌单时追加到现有队列尾部",
                leadingIcon = Icons.Default.QueueMusic,
                trailingText = if (playlistReplace) "替换" else "追加",
                showChevron = true,
                onClick = {
                    playlistReplace = !playlistReplace
                    Preferences.setPlaylistPlayReplace(context, playlistReplace)
                }
            )

            BreezyCustomText(
                text = if (playlistReplace)
                    "播放歌单或全部播放时，先保存当前列表到历史记录，再替换为新列表。"
                else
                    "播放歌单时，将歌曲追加到当前播放列表尾部，不保存历史。",
                fontSize = 13.sp,
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
