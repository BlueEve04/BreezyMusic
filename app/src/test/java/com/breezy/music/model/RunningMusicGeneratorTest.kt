package com.breezy.music.model

import com.breezy.music.audio.EqPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运动音乐生成与音效预设的单元测试（纯 JVM 逻辑，无需设备）。
 */
class RunningMusicGeneratorTest {

    private fun song(path: String, bpm: Float, durationMs: Long = 180_000L) = SongEntity(
        path = path,
        title = path.substringAfterLast('/'),
        artist = "A",
        album = "AL",
        duration = durationMs,
        folderPath = "root",
        bpm = bpm
    )

    @Test
    fun `target bpm honours half time`() {
        assertEquals(170, RunningMusicGenerator.targetBpmFor(170, false))
        assertEquals(85, RunningMusicGenerator.targetBpmFor(170, true))
    }

    @Test
    fun `songs outside tolerance are excluded`() {
        val pool = listOf(
            song("/170.mp3", 170f),
            song("/175.mp3", 175f),
            song("/120.mp3", 120f),
            song("/unknown.mp3", 0f)
        )
        val result = RunningMusicGenerator.generate(
            pool,
            RunningMusicGenerator.RunningMusicConfig(targetSpm = 170, toleranceBpm = 6, durationMinutes = 15)
        )
        assertTrue(result.matches.none { it.song.path == "/120.mp3" })
        assertTrue(result.matches.none { it.song.path == "/unknown.mp3" })
        assertEquals(2, result.inToleranceCount)
        assertFalse(result.isEmpty)
    }

    @Test
    fun `playlist respects requested duration`() {
        val pool = (0 until 40).map { i -> song("/t$i.mp3", 160f + (i % 5), 200_000L) }
        val result = RunningMusicGenerator.generate(
            pool,
            RunningMusicGenerator.RunningMusicConfig(targetSpm = 162, toleranceBpm = 10, durationMinutes = 15)
        )
        // 15 分钟 ≈ 4~5 首 200s 曲目，允许结尾多 45s 缓冲
        assertTrue(result.totalDurationMs >= 15 * 60_000L - 45_000L)
        assertTrue(result.totalDurationMs <= 15 * 60_000L + 250_000L)
    }

    @Test
    fun `empty pool yields empty result`() {
        val result = RunningMusicGenerator.generate(
            emptyList(),
            RunningMusicGenerator.RunningMusicConfig()
        )
        assertTrue(result.isEmpty)
    }

    @Test
    fun `flat preset produces silent curve at any band count`() {
        val flat = EqPresets.findBuiltin("flat")!!
        for (count in listOf(5, 10, 16)) {
            val gains = EqPresets.gainsForBandCount(flat, count)
            assertEquals(count, gains.size)
            assertTrue(gains.all { kotlin.math.abs(it) < 0.01f })
        }
    }

    @Test
    fun `bass boost preset boosts low bands only`() {
        val bass = EqPresets.findBuiltin("bass_boost")!!
        val gains = EqPresets.gainsForBandCount(bass, 16)
        val freqs = com.breezy.music.audio.BreezyDspProcessor.centerFrequencies(16)
        val low = gains.filterIndexed { i, _ -> freqs[i] < 250f }.average()
        val high = gains.filterIndexed { i, _ -> freqs[i] > 4000f }.average()
        assertTrue("low bands should be boosted, was $low", low > 1.0)
        assertTrue("high bands should be flat or cut, was $high", high <= 1.0)
    }

    @Test
    fun `builtin presets have unique ids and expected count`() {
        val ids = EqPresets.BUILTIN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(EqPresets.BUILTIN.size >= 10)
    }
}
