package com.breezy.music.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.breezy.music.model.Preferences
import com.breezy.music.model.SongEntity
import com.breezy.ui.text.BreezyCustomText
import com.breezy.ui.theme.BreezyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 底部居中多选工具栏 Dock
 */
@Composable
fun MultiSelectDock(
    onCancel: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 112.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .background(BreezyTheme.colors.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "退出多选", tint = BreezyTheme.colors.onSurface)
            }
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = BreezyTheme.colors.onSurface.copy(alpha = 0.15f)
            )
            actions()
        }
    }
}

/**
 * 「加入歌单」按钮 + 歌单选择弹窗
 * @param songsToAdd 要加入歌单的歌曲列表
 */
@Composable
fun AddToPlaylistButton(
    songsToAdd: List<SongEntity>,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    val playlists = remember { Preferences.getM3u8Uris(context).toList() }

    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Default.PlaylistAdd, "加入歌单",
            tint = BreezyTheme.colors.onSurface, modifier = Modifier.size(22.dp))
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BreezyTheme.colors.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    BreezyCustomText("加入歌单 (${songsToAdd.size}首)", fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, color = BreezyTheme.colors.onSurface)
                    Spacer(Modifier.height(12.dp))

                    if (playlists.isEmpty()) {
                        BreezyCustomText("暂无歌单\n请在音乐文件夹中添加 .m3u8 文件后重新扫描",
                            fontSize = 13.sp, color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                    } else {
                        // 新建歌单按钮
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showDialog = false
                                    onDone()
                                    Toast.makeText(context, "新建歌单：请在文件夹中创建 .m3u8 文件后重新扫描", Toast.LENGTH_LONG).show()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, null,
                                tint = BreezyTheme.colors.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            BreezyCustomText("+ 新建歌单", fontSize = 14.sp,
                                fontWeight = FontWeight.Bold, color = BreezyTheme.colors.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = BreezyTheme.colors.onSurface.copy(alpha = 0.1f))
                        Spacer(Modifier.height(4.dp))

                        playlists.forEach { uriStr ->
                            val name = try {
                                val u = android.net.Uri.parse(uriStr)
                                u.lastPathSegment?.removeSuffix(".m3u8")?.removeSuffix(".m3u")
                                    ?: uriStr.substringAfterLast('/')
                            } catch (_: Exception) {
                                uriStr.substringAfterLast('/')
                            }.removeSuffix(".m3u8").removeSuffix(".m3u")
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        addSongsToM3u8(context, uriStr, songsToAdd)
                                        showDialog = false
                                        onDone()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlaylistAdd, null,
                                    tint = BreezyTheme.colors.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                BreezyCustomText(name, fontSize = 14.sp,
                                    color = BreezyTheme.colors.onSurface)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showDialog = false },
                        modifier = Modifier.align(Alignment.End)) {
                        BreezyCustomText("取消", color = BreezyTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/** 将歌曲追加到 M3U8 文件 */
private fun addSongsToM3u8(context: Context, m3u8Uri: String, songs: List<SongEntity>) {
    try {
        val uri = android.net.Uri.parse(m3u8Uri)
        android.util.Log.d("AddToPlaylist", "写入: $m3u8Uri, ${songs.size}首")

        // 读取现有内容
        val existing = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) existing.add(line)
                }
            }
        }
        android.util.Log.d("AddToPlaylist", "现有 ${existing.size} 行")

        // 追加新歌曲路径（仅文件名，去重）
        val songNames = songs.map { it.path.substringAfterLast('/') }
        songNames.forEach { name ->
            if (existing.none { it.contains(name) }) {
                existing.add(name)
            }
        }
        android.util.Log.d("AddToPlaylist", "写入 ${existing.size} 行")

        // 通过 DocumentFile 获取可写 URI
        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
        val writeUri = doc?.uri ?: uri
        context.contentResolver.openOutputStream(writeUri, "wt")?.use { out ->
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (existing.none { it.startsWith("#EXTM3U") }) {
                    writer.write("#EXTM3U\n")
                }
                existing.forEach { writer.write("$it\n") }
            }
        }
        android.util.Log.d("AddToPlaylist", "写入成功")
        // 清除该歌单缓存，下次重新解析
        Preferences.setPlaylistSongsCache(context, m3u8Uri, emptyList())
        Toast.makeText(context, "已加入 ${songNames.size} 首", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.util.Log.e("AddToPlaylist", "写入失败", e)
        Toast.makeText(context, "写入失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
