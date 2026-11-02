package com.breezy.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词外发的协议组装测试（不依赖 Android 运行时，纯 JVM）。
 *
 * 这些用例盯的是「向下兼容」最容易踩的三个点：
 *
 *  1. `lines` 必须按 start 严格升序——4.0 构造器会校验；
 *  2. 行结束时间必须被填成下一行的开始，最后一行要有兜底，
 *     否则 3.7 的 legacy 通道里会出现“上一行永远不结束”；
 *  3. 翻译/注音在降级重发时必须能被剥掉（帧超过 512 KB 时的退路）。
 */
class LyricPublisherTest {

    private fun line(t: Long, text: String, track: Int = 0) =
        LyricParser.LyricLine(timeMs = t, text = text, track = track)

    @Test
    fun `同一时间戳的多轨合并成一行`() {
        val raw = listOf(
            line(1000, "原文 A"),
            line(1000, "翻译 A", track = 1),
            line(1000, "romaji A", track = 2),
            line(3000, "原文 B"),
        )
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 10_000)

        assertEquals(2, out.size)
        assertEquals("原文 A", out[0].text)
        assertEquals("翻译 A", out[0].translation)
        assertEquals("romaji A", out[0].phonetic)
    }

    @Test
    fun `翻译时间戳有几毫秒偏差时仍然能吸附到原文行`() {
        // 真实 LRC 里译文很少和原文毫秒级对齐，旧实现按「时间戳完全相等」分组，
        // 这一偏差就让 translation 直接消失——这正是「只发布原文」的现场。
        val raw = listOf(
            line(1000, "原文 A"),
            line(1012, "翻译 A", track = 1),
            line(3000, "原文 B"),
            line(3005, "翻译 B", track = 1),
        )
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 10_000)

        assertEquals(2, out.size)
        assertEquals("翻译 A", out[0].translation)
        assertEquals("翻译 B", out[1].translation)
    }

    @Test
    fun `偏差过大时不会把下一句的翻译串过来`() {
        val raw = listOf(
            line(1000, "原文 A"),
            line(2600, "翻译 B", track = 1),
            line(3000, "原文 B"),
        )
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 10_000)

        assertEquals(2, out.size)
        assertEquals(null, out[0].translation)
    }

    @Test
    fun `结束时间取下一行开始_最后一行用总时长兜底`() {
        val raw = listOf(line(0, "a"), line(1500, "b"))
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 60_000)

        assertEquals(0L, out[0].start)
        assertEquals(1500L, out[0].end, )
        assertEquals(1500L, out[1].start)
        assertEquals(60_000L, out[1].end)
        assertTrue(out.all { it.end >= it.start })
    }

    @Test
    fun `总时长未知时最后一行用固定兜底而不是零长`() {
        val raw = listOf(line(0, "a"), line(2000, "b"))
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 0)

        assertTrue(out.last().end > out.last().start)
        assertEquals(out.last().start + 4000L, out.last().end)
    }

    @Test
    fun `乱序输入会被排好序`() {
        val raw = listOf(line(5000, "c"), line(1000, "a"), line(3000, "b"))
        val out = LyricPublisher.toProtocolLines(raw, durationMs = 9000)

        assertEquals(listOf(1000L, 3000L, 5000L), out.map { it.start })
        assertTrue(out.zipWithNext().all { (a, b) -> a.start < b.start })
    }

    @Test
    fun `降级重发会剥掉翻译与注音`() {
        val raw = listOf(
            line(0, "原文", 0),
            line(0, "翻译", 1),
            line(0, "romaji", 2),
        )
        val decorated = LyricPublisher.toProtocolLines(raw, 10_000, decorate = true)
        val plain = LyricPublisher.toProtocolLines(raw, 10_000, decorate = false)

        assertEquals("翻译", decorated.single().translation)
        assertEquals(null, plain.single().translation)
        assertEquals(null, plain.single().phonetic)
    }

    @Test
    fun `lyricId 对同一首歌稳定_对不同歌不同`() {
        val a = SongEntity(
            path = "/storage/emulated/0/Music/a.flac", title = "A", artist = "x",
            album = "y", duration = 200_000, folderPath = "/storage/emulated/0/Music",
        )
        val a2 = a.copy(title = "A（改名后）")
        val b = a.copy(path = "/storage/emulated/0/Music/b.flac")
        val longer = a.copy(duration = 200_001)

        assertEquals(LyricPublisher.lyricId(a), LyricPublisher.lyricId(a2))
        assertTrue(LyricPublisher.lyricId(a) != LyricPublisher.lyricId(b))
        assertTrue(LyricPublisher.lyricId(a) != LyricPublisher.lyricId(longer))
    }

    @Test
    fun `lrc 路径由音频路径推导`() {
        assertEquals(
            "/storage/emulated/0/Music/song.lrc",
            LyricPublisher.lrcPathFor("/storage/emulated/0/Music/song.flac")
        )
        // 没有扩展名时不乱猜
        assertEquals(null, LyricPublisher.lrcPathFor("/storage/emulated/0/Music/song"))
    }
}
