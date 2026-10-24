package com.breezy.music.util

import android.content.Context
import android.net.Uri
import com.breezy.music.model.MusicDatabase
import com.breezy.music.model.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * M3U / M3U8 歌单解析器（重写版）
 *
 * 旧实现的缺陷（本次修复的出发点）：
 *  1. 固定按 UTF-8 读取：中文 Windows 下导出的 m3u 常为 GBK，直接乱码，路径全部匹配失败；
 *  2. 忽略 `#EXTINF:` 行，丢掉了时长与标题这两个最可靠的匹配依据；
 *  3. 不做相对路径归一化：不处理 `\\`、`./`、`%20`、重复斜杠，跨平台歌单一律失效；
 *  4. 匹配只用「路径后缀 + 标题包含」，遇到同名歌曲（不同专辑/不同码率）会匹配到错误曲目；
 *  5. 匹配结果用 `LinkedHashSet` 但去重依赖 `SongEntity` 的 equals，且没有「未匹配条目」反馈，
 *     用户无法知道歌单为什么少了歌。
 *
 * 新实现按主流播放器（Poweramp、foobar2000、AIMP）的处理顺序执行：
 *   编码探测（BOM → 严格 UTF-8 → GB18030） → 逐行状态机解析 EXTINF → 路径归一化 →
 *   多级匹配（精确路径 → 后缀 → 文件名唯一 → 标题规范化等价 → 标题包含） → 保序去重 → 统计未匹配。
 */
object M3u8Parser {

    /** 单条歌单记录（可能是 `#EXTINF` + 路径的组合） */
    data class Entry(
        val rawPath: String,
        /** `#EXTINF:123,标题` 中的标题，可能为空 */
        val title: String = "",
        /** `#EXTINF` 中的时长（毫秒），未知为 -1 */
        val durationMs: Long = -1L
    )

    data class PlaylistInfo(
        val name: String,
        val uri: String,
        val songs: List<SongEntity>,
        /** 未能匹配到本地曲库的条目（用于提示用户） */
        val unmatched: List<String> = emptyList(),
        /** 实际使用的编码，便于排查乱码问题 */
        val encoding: String = "UTF-8",
        /** 歌单里记录的总条目数 */
        val totalEntries: Int = 0
    )

    private val GB18030: Charset = Charset.forName("GB18030")

    // ─────────────────────────────────────────────
    // 对外入口
    // ─────────────────────────────────────────────

    /**
     * 解析一个 M3U8 文件并匹配歌曲（高效版，使用预加载的歌曲列表和路径索引）
     */
    suspend fun parse(
        context: Context,
        m3u8Uri: Uri,
        allSongs: List<SongEntity>,
        pathIndex: Map<String, SongEntity>
    ): PlaylistInfo = withContext(Dispatchers.IO) {
        val bytes = readBytes(context, m3u8Uri)
        val decoded = decode(bytes)
        val entries = parseEntries(decoded.text, baseDirOf(m3u8Uri))
        val result = match(entries, allSongs, pathIndex)
        PlaylistInfo(
            name = playlistName(m3u8Uri),
            uri = m3u8Uri.toString(),
            songs = result.songs,
            unmatched = result.unmatched,
            encoding = decoded.charset,
            totalEntries = entries.size
        )
    }

    /** 解析一个 M3U8 文件（每次查询数据库，较慢，用于冷启动场景） */
    suspend fun parse(context: Context, m3u8Uri: Uri): PlaylistInfo = withContext(Dispatchers.IO) {
        val allSongs = MusicDatabase.getInstance(context).musicDao().getAllSongs()
        val pathIndex = buildPathIndex(allSongs)
        val bytes = readBytes(context, m3u8Uri)
        val decoded = decode(bytes)
        val entries = parseEntries(decoded.text, baseDirOf(m3u8Uri))
        val result = match(entries, allSongs, pathIndex)
        PlaylistInfo(
            name = playlistName(m3u8Uri),
            uri = m3u8Uri.toString(),
            songs = result.songs,
            unmatched = result.unmatched,
            encoding = decoded.charset,
            totalEntries = entries.size
        )
    }

    /** 构建路径索引（归一化路径 → 歌曲），供批量解析复用 */
    fun buildPathIndex(songs: List<SongEntity>): Map<String, SongEntity> {
        val map = HashMap<String, SongEntity>(songs.size * 2)
        songs.forEach { song ->
            map[normalizePath(song.path)] = song
        }
        return map
    }

    // ─────────────────────────────────────────────
    // 读取与编码探测
    // ─────────────────────────────────────────────

    private fun readBytes(context: Context, uri: Uri): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    data class Decoded(val text: String, val charset: String)

    /**
     * 编码探测：
     *  - UTF-8 BOM / UTF-16 LE/BE BOM 直接按 BOM 解码；
     *  - 无 BOM 时先用严格 UTF-8 解码（非法字节即抛错），失败回退 GB18030（兼容 GBK/GB2312）。
     * 这覆盖了国内用户从 foobar2000 / 酷狗 / 网易云导出的绝大多数 m3u 文件。
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded("", "UTF-8")
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8(BOM)")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), "UTF-8")
        } catch (e: CharacterCodingException) {
            Decoded(String(bytes, GB18030), "GB18030")
        }
    }

    // ─────────────────────────────────────────────
    // 行解析
    // ─────────────────────────────────────────────

    /**
     * 逐行解析：维护当前 `#EXTINF` 的标题/时长，遇到非注释行时组装成 [Entry]。
     * 支持：
     *  - `#EXTINF:123,标题`
     *  - `#EXTINF:123 tvg-name="..." ,标题`
     *  - `#PLAYLIST:名称`
     *  - 空行与其余 `#` 指令
     */
    fun parseEntries(text: String, baseDir: String = ""): List<Entry> {
        val entries = ArrayList<Entry>()
        var pendingTitle = ""
        var pendingDuration = -1L
        val pendingBase = baseDir

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim('\uFEFF')
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        val body = line.substringAfter(':')
                        // 时长在逗号之前，标题在逗号之后（最后一个逗号，避免标题里含逗号）
                        val commaIdx = body.indexOf(',')
                        if (commaIdx >= 0) {
                            pendingTitle = body.substring(commaIdx + 1).trim()
                            val durationPart = body.substring(0, commaIdx).trim().substringBefore(' ')
                            // 负时长（-1 表示未知）保持 -1，不要写成 -1000ms
                            pendingDuration = durationPart.toDoubleOrNull()
                                ?.takeIf { it >= 0 }
                                ?.let { (it * 1000).toLong() } ?: -1L
                        } else {
                            pendingDuration = body.trim().toDoubleOrNull()
                                ?.takeIf { it >= 0 }
                                ?.let { (it * 1000).toLong() } ?: -1L
                        }
                    }
                    line.startsWith("#EXTALBUM", ignoreCase = true) ||
                        line.startsWith("#EXTARTIST", ignoreCase = true) -> {
                        // 部分播放器会写专辑/歌手，作为标题兜底
                        val v = line.substringAfter(':', "").trim()
                        if (pendingTitle.isEmpty() && v.isNotEmpty()) pendingTitle = v
                    }
                    line.startsWith("#PLAYLIST:", ignoreCase = true) -> { /* 名称单独处理 */ }
                    else -> { /* 其它扩展指令忽略 */ }
                }
                return@forEach
            }

            val cleaned = cleanPath(line)
            if (cleaned.isEmpty()) return@forEach
            entries.add(
                Entry(
                    rawPath = if (pendingBase.isNotEmpty() && !cleaned.startsWith("/") && !cleaned.contains("://")) {
                        resolveRelative(pendingBase, cleaned)
                    } else cleaned,
                    title = pendingTitle,
                    durationMs = pendingDuration
                )
            )
            pendingTitle = ""
            pendingDuration = -1L
        }
        return entries
    }

    private fun playlistName(uri: Uri): String {
        val fromUri = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.substringAfterLast('/')
            ?.trim()
            .orEmpty()
        return fromUri.ifEmpty { "未知歌单" }
    }

    /** 歌单所在目录，用于解析相对路径 */
    fun baseDirOf(uri: Uri): String {
        val path = uri.path ?: return ""
        val idx = path.lastIndexOf('/')
        return if (idx > 0) path.substring(0, idx) else ""
    }

    // ─────────────────────────────────────────────
    // 路径归一化
    // ─────────────────────────────────────────────

    /**
     * 路径归一化（跨平台兼容）：
     *  反斜杠 → 斜杠、去掉引号、URL 解码 `%20`、折叠重复斜杠、去掉 `./`、
     *  统一小写用于比较（返回值已小写，展示时请用原始字符串）。
     */
    fun normalizePath(input: String): String = cleanPath(input).lowercase(Locale.ROOT)

    /**
     * 路径清理（**保留大小写**）：反斜杠 → 斜杠、去引号、URL 解码 `%20`、
     * 折叠重复斜杠、去掉 `./`。
     *
     * 相对 `normalizePath` 的区别只在于不转小写——展示与回写（`rawPath`）必须保留原始大小写，
     * 否则用户在歌单里看到全小写路径、并且写回后会破坏原文件路径。
     */
    fun cleanPath(input: String): String {
        var p = input.trim().trim('"', '\'', '\uFEFF')
        if (p.isEmpty()) return ""
        p = p.replace('\\', '/')
        if (p.contains('%')) {
            p = try {
                java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                p
            }
        }
        while (p.contains("//")) p = p.replace("//", "/")
        while (p.startsWith("./")) p = p.substring(2)
        p = p.removeSuffix("/")
        return p
    }

    /** 相对路径拼接并消解 `..` */
    fun resolveRelative(baseDir: String, relative: String): String {
        val dir = if (baseDir.endsWith("/")) baseDir else "$baseDir/"
        val parts = (dir + relative).split('/')
        val stack = ArrayDeque<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        val joined = stack.joinToString("/")
        return if ((dir + relative).startsWith("/")) "/$joined" else joined
    }

    /** 文件主名（不含扩展名），小写 */
    private fun fileStem(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        return stemsKey(stem)
    }

    /** 标题/文件名的宽松比较键：去掉空格、标点、大小写差异 */
    fun stemsKey(s: String): String {
        val sb = StringBuilder(s.length)
        s.lowercase(Locale.ROOT).forEach { c ->
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    // ─────────────────────────────────────────────
    // 匹配
    // ─────────────────────────────────────────────

    data class MatchResult(
        val songs: List<SongEntity>,
        val unmatched: List<String>
    )

    fun match(
        entries: List<Entry>,
        allSongs: List<SongEntity>,
        pathIndex: Map<String, SongEntity>
    ): MatchResult {
        val result = LinkedHashSet<SongEntity>()
        val unmatched = ArrayList<String>()

        // 预建索引：文件名唯一表、标题规范化表
        val byStem = HashMap<String, MutableList<SongEntity>>()
        val byTitleKey = HashMap<String, MutableList<SongEntity>>()
        allSongs.forEach { song ->
            byStem.getOrPut(fileStem(normalizePath(song.path))) { mutableListOf() }.add(song)
            byTitleKey.getOrPut(stemsKey(song.title)) { mutableListOf() }.add(song)
        }
        val normalizedIndex = HashMap<String, SongEntity>(pathIndex.size * 2)
        pathIndex.forEach { (k, v) -> normalizedIndex[normalizePath(k)] = v }

        entries.forEach { entry ->
            val norm = normalizePath(entry.rawPath)
            if (norm.isEmpty()) return@forEach

            val matched = matchOne(entry, norm, allSongs, normalizedIndex, byStem, byTitleKey)
            if (matched != null) {
                result.add(matched)
            } else {
                unmatched.add(entry.rawPath)
            }
        }
        return MatchResult(result.toList(), unmatched)
    }

    private fun matchOne(
        entry: Entry,
        norm: String,
        allSongs: List<SongEntity>,
        pathIndex: Map<String, SongEntity>,
        byStem: Map<String, List<SongEntity>>,
        byTitleKey: Map<String, List<SongEntity>>
    ): SongEntity? {
        // 1) 完整路径精确匹配（最快路径）
        pathIndex[norm]?.let { return it }

        // 2) 路径后缀匹配：歌单里常存绝对路径，而本地库路径可能是 content:// 或不同挂载点
        pathIndex.entries.firstOrNull { (k, _) -> k.endsWith(norm) || norm.endsWith(k) }?.let { return it.value }

        // 3) 文件名唯一匹配（忽略扩展名差异）
        val stem = fileStem(norm)
        if (stem.isNotEmpty()) {
            byStem[stem]?.let { candidates ->
                // 多个同名文件时，用父目录进一步缩小范围
                if (candidates.size == 1) return candidates[0]
                val parent = norm.substringBeforeLast('/', "").substringAfterLast('/')
                if (parent.isNotEmpty()) {
                    candidates.firstOrNull { normalizePath(it.path).contains("/$parent/") }?.let { return it }
                }
            }
        }

        // 4) EXTINF 标题规范化等价
        if (entry.title.isNotEmpty()) {
            val titleKey = stemsKey(entry.title)
            byTitleKey[titleKey]?.let { if (it.size == 1) return it[0] }
            if (titleKey.length >= 2) {
                allSongs.firstOrNull { stemsKey(it.title) == titleKey }?.let { return it }
            }
        }

        // 5) 标题包含（最后的兜底，要求足够长以避免误匹配）
        if (entry.title.length >= 2) {
            allSongs.firstOrNull {
                val t = it.title.lowercase(Locale.ROOT)
                t.contains(entry.title.lowercase(Locale.ROOT)) ||
                    entry.title.lowercase(Locale.ROOT).contains(t)
            }?.let { return it }
        }

        // 6) 文件名包含（旧版行为，保留为兜底）
        if (stem.length >= 3) {
            allSongs.firstOrNull { fileStem(normalizePath(it.path)).contains(stem) }?.let { return it }
        }
        return null
    }
}
