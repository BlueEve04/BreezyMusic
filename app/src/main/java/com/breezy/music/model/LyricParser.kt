package com.breezy.music.model

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LRC 歌词解析器
 *
 * 支持标准 LRC 格式 [mm:ss.xx]歌词，以及多轨（同时间轴多条 = 原文+翻译+罗马音）。
 */
object LyricParser {

    /** 单条歌词行 */
    data class LyricLine(
        val timeMs: Long,          // 毫秒
        val text: String,          // 歌词文本
        val track: Int = 0         // 轨道：0=原文, 1=翻译, 2=罗马音
    )

    /**
     * 从 content URI 读取并解析 LRC 文件
     * @return 按时间排序的歌词列表，按时间分组（同时间的多条排在一起）
     */
    fun parse(context: Context, uriString: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        try {
            val uri = Uri.parse(uriString)
            // SAF content URI → AssetFileDescriptor（用完再关）
            val pfd = if (uri.scheme == "content") {
                context.contentResolver.openAssetFileDescriptor(uri, "r")
            } else null

            val inputStream = if (pfd != null) {
                java.io.FileInputStream(pfd.fileDescriptor)
            } else {
                context.contentResolver.openInputStream(uri)
            }

            if (inputStream == null) {
                Log.w("LyricParser", "openInputStream returned null for $uriString")
                pfd?.close()
                return emptyList()
            }
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var trackIndex = 0
            var prevTimeMs = -1L

            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine

                // 匹配 [mm:ss.xx] 或 [mm:ss]
                val timeRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?](.*)""")
                val match = timeRegex.find(trimmed)
                if (match != null) {
                    val min = match.groupValues[1].toInt()
                    val sec = match.groupValues[2].toInt()
                    val msStr = match.groupValues[3]
                    val ms = if (msStr.isNotEmpty()) {
                        (msStr.padEnd(3, '0').take(3).toInt())
                    } else 0
                    val timeMs = min * 60_000L + sec * 1000L + ms
                    val text = match.groupValues[4].trim()

                    // 检测时间是否回退（新的多轨段落开始）
                    if (timeMs < prevTimeMs) {
                        trackIndex++
                    }
                    prevTimeMs = timeMs

                    lines.add(LyricLine(timeMs, text, trackIndex))
                }
                // 忽略元数据行 [ti:] [ar:] 等
            }
            reader.close()
            pfd?.close()
            Log.i("LyricParser", "Parsed ${lines.size} lines from $uriString")
        } catch (e: Exception) {
            Log.w("LyricParser", "Parse failed: $uriString", e)
            return emptyList()
        }

        // 按时间排序，同时间按轨道排序
        return lines.sortedWith(compareBy<LyricLine> { it.timeMs }.thenBy { it.track })
    }

    /**
     * 将解析结果按时间戳分组
     * 返回 Map<timeMs, List<LyricLine>>，每组至少 1 条
     */
    fun groupByTime(lines: List<LyricLine>): List<Pair<Long, List<LyricLine>>> {
        return lines.groupBy { it.timeMs }
            .map { it.key to it.value }
            .sortedBy { it.first }
    }
}
