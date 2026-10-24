package com.breezy.music.util

import com.breezy.music.model.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * M3U8 歌单解析单元测试
 *
 * 本次修复的核心问题都在这里：编码识别（BOM/UTF-8/GBK）、`#EXTINF` 元数据、
 * 相对路径基准、Windows 反斜杠与 URL 编码归一化、级联匹配与未匹配统计。
 */
class M3u8ParserTest {

    private fun song(path: String, title: String, artist: String = "A") = SongEntity(
        path = path,
        title = title,
        artist = artist,
        album = "AL",
        duration = 1000L,
        folderPath = "root",
        bpm = 0f
    )

    @Test
    fun `decode utf8 with bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "#EXTM3U\nA.mp3".toByteArray(Charsets.UTF_8)
        val decoded = M3u8Parser.decode(bytes)
        assertTrue(decoded.text.startsWith("#EXTM3U"))
        assertTrue(decoded.charset.contains("UTF-8"))
    }

    @Test
    fun `decode utf16 little endian with bom`() {
        val payload = "#EXTM3U\n歌单.m3u8".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload
        val decoded = M3u8Parser.decode(bytes)
        assertTrue(decoded.text.contains("歌单"))
        assertTrue(decoded.charset.contains("UTF-16"))
    }

    @Test
    fun `decode gbk when bytes are not valid utf8`() {
        val gbkBytes = "#EXTM3U\n周杰伦 - 晴天.mp3".toByteArray(Charset.forName("GBK"))
        val decoded = M3u8Parser.decode(gbkBytes)
        assertTrue(decoded.text.contains("周杰伦"))
        assertTrue(decoded.charset.contains("GB"))
    }

    @Test
    fun `parse extinf title and duration`() {
        val text = """
            #EXTM3U
            #EXTINF:215,Artist - Title
            Music/song.mp3
            #EXTINF:-1,No duration
            other.mp3
        """.trimIndent()
        val entries = M3u8Parser.parseEntries(text, "")
        assertEquals(2, entries.size)
        assertEquals(215_000L, entries[0].durationMs)
        assertEquals("Artist - Title", entries[0].title)
        assertEquals("Music/song.mp3", entries[0].rawPath)
        assertEquals(-1L, entries[1].durationMs)
    }

    @Test
    fun `normalize path handles backslashes url encoding and dot segments`() {
        assertEquals("music/a b.mp3", M3u8Parser.normalizePath("Music\\A%20B.mp3"))
        assertEquals("songs/a.mp3", M3u8Parser.normalizePath("./songs//a.mp3"))
        assertEquals("quoted.mp3", M3u8Parser.normalizePath("\"quoted.mp3\""))
        // cleanPath 保留大小写，供展示与回写使用
        assertEquals("Music/A B.mp3", M3u8Parser.cleanPath("Music\\A%20B.mp3"))
    }

    @Test
    fun `resolve relative path against base dir`() {
        val resolved = M3u8Parser.resolveRelative("music/lists", "../tracks/song.mp3")
        assertEquals("music/tracks/song.mp3", resolved)
    }

    @Test
    fun `match prefers exact normalized path then stem and title`() {
        val s1 = song("/storage/emulated/0/Music/a.mp3", "Song A")
        val s2 = song("/storage/emulated/0/Music/b.mp3", "Song B")
        val s3 = song("/storage/emulated/0/Music/c.mp3", "Song C")
        val all = listOf(s1, s2, s3)
        val index = M3u8Parser.buildPathIndex(all)

        val entries = listOf(
            // 精确路径（规范化后一致）
            M3u8Parser.Entry("/storage/emulated/0/Music/a.mp3"),
            // Windows 反斜杠 + 大小写差异 → 文件名匹配
            M3u8Parser.Entry("MUSIC\\B.MP3"),
            // 仅标题匹配
            M3u8Parser.Entry("whatever/unknown-file.flac", title = "Song C"),
            // 完全不存在
            M3u8Parser.Entry("missing/zzz.mp3")
        )
        val result = M3u8Parser.match(entries, all, index)
        assertEquals(listOf("/storage/emulated/0/Music/a.mp3", "/storage/emulated/0/Music/b.mp3"),
            result.songs.take(2).map { it.path })
        assertEquals(1, result.unmatched.size)
        assertTrue(result.unmatched.first().contains("zzz"))
    }

    @Test
    fun `match deduplicates while preserving order`() {
        val s1 = song("/music/a.mp3", "A")
        val s2 = song("/music/b.mp3", "B")
        val all = listOf(s1, s2)
        val index = M3u8Parser.buildPathIndex(all)
        val entries = listOf(
            M3u8Parser.Entry("/music/b.mp3"),
            M3u8Parser.Entry("/music/a.mp3"),
            M3u8Parser.Entry("/music/b.mp3")
        )
        val result = M3u8Parser.match(entries, all, index)
        assertEquals(listOf("/music/b.mp3", "/music/a.mp3"), result.songs.map { it.path })
    }

    @Test
    fun `stems key ignores punctuation and case`() {
        assertEquals(
            M3u8Parser.stemsKey("Song - Title (Live)"),
            M3u8Parser.stemsKey("song title live")
        )
    }
}
