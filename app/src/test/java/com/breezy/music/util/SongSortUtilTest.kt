package com.breezy.music.util

import com.breezy.music.model.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序工具单元测试
 *
 * 重点覆盖本次改造新增的能力：自然排序（数字按数值）、多字段排序、升降序、
 * 未分析 BPM 的排位，以及「完全相等时按 path 稳定」这一保证列表顺序可复现的前提。
 */
class SongSortUtilTest {

    private fun song(
        path: String,
        title: String,
        artist: String = "A",
        album: String = "AL",
        duration: Long = 0L,
        bpm: Float = 0f
    ) = SongEntity(
        path = path,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        folderPath = "root",
        bpm = bpm
    )

    @Test
    fun `natural sort treats numbers by value`() {
        val input = listOf("Track 10", "Track 2", "Track 1")
        val sorted = input.sortedWith(SongSortUtil.naturalComparator)
        assertEquals(listOf("Track 1", "Track 2", "Track 10"), sorted)
    }

    @Test
    fun `natural sort handles leading zeros and mixed text`() {
        val input = listOf("CD1 - 03", "CD1 - 2", "CD1 - 10", "CD2 - 01")
        val sorted = input.sortedWith(SongSortUtil.naturalComparator)
        assertEquals(listOf("CD1 - 2", "CD1 - 03", "CD1 - 10", "CD2 - 01"), sorted)
    }

    @Test
    fun `chinese titles are ordered by pinyin collator`() {
        val input = listOf("周杰伦", "陈奕迅", "阿杜")
        val sorted = input.sortedWith(SongSortUtil.naturalComparator)
        // 阿(a) < 陈(c) < 周(z)
        assertEquals(listOf("阿杜", "陈奕迅", "周杰伦"), sorted)
    }

    @Test
    fun `sort by title descending`() {
        val songs = listOf(song("/a", "B"), song("/b", "C"), song("/c", "A"))
        val sorted = SongSortUtil.sort(songs, SongSortUtil.SortKey.TITLE, ascending = false)
        assertEquals(listOf("C", "B", "A"), sorted.map { it.title })
    }

    @Test
    fun `multi criteria sorts by artist then title`() {
        val songs = listOf(
            song("/1", "Zeta", artist = "Bob"),
            song("/2", "Alpha", artist = "Bob"),
            song("/3", "Mid", artist = "Alice")
        )
        val sorted = SongSortUtil.sort(
            songs,
            listOf(
                SongSortUtil.SortCriterion(SongSortUtil.SortKey.ARTIST),
                SongSortUtil.SortCriterion(SongSortUtil.SortKey.TITLE)
            )
        )
        assertEquals(listOf("Mid", "Alpha", "Zeta"), sorted.map { it.title })
    }

    @Test
    fun `unanalyzed bpm sorts last in ascending order`() {
        val songs = listOf(
            song("/unknown", "X", bpm = 0f),
            song("/fast", "Y", bpm = 180f),
            song("/slow", "Z", bpm = 100f)
        )
        val asc = SongSortUtil.sort(songs, SongSortUtil.SortKey.BPM)
        assertEquals(listOf("/slow", "/fast", "/unknown"), asc.map { it.path })
    }

    @Test
    fun `equal keys fall back to path for stable ordering`() {
        val songs = listOf(
            song("/c", "Same"),
            song("/a", "Same"),
            song("/b", "Same")
        )
        val sorted = SongSortUtil.sort(songs, SongSortUtil.SortKey.TITLE)
        assertEquals(listOf("/a", "/b", "/c"), sorted.map { it.path })
    }

    @Test
    fun `bucket label returns hash for digits and letters otherwise`() {
        assertEquals("#", SongSortUtil.getBucketLabel("123 Song"))
        assertEquals("#", SongSortUtil.getBucketLabel(""))
        assertTrue(SongSortUtil.getBucketLabel("Hello").first() in 'A'..'Z')
        assertTrue(SongSortUtil.getBucketLabel("晴天").first() in 'A'..'Z')
    }
}
