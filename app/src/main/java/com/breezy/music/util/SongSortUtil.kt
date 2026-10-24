package com.breezy.music.util

import android.icu.text.Transliterator
import com.breezy.music.model.SongEntity
import java.text.Collator
import java.util.Locale

/**
 * 多语言歌曲排序工具
 *
 * 排序规则：
 *  1. 文本段使用 [Collator]（中文按拼音、日韩按本地化顺序），忽略大小写与重音；
 *  2. 文本中的数字串按“自然排序”比较（`Track 2` 排在 `Track 10` 之前），修复旧版纯字典序问题；
 *  3. 多字段排序（标题/歌手/专辑/时长/路径），字段间可分别指定升降序；
 *  4. 始终以 `path` 作为最后一级 tie-break，保证排序结果稳定（同一份数据多次排序顺序一致）。
 *
 * 该实现替代了旧版只比较 `title` 且没有稳定 tie-break 的版本。
 */
object SongSortUtil {

    /** 中文拼音 Collator（PRC 简体，能识别繁体），PRIMARY 忽略大小写与重音 */
    private val collator: Collator = Collator.getInstance(Locale.CHINESE).apply {
        strength = Collator.PRIMARY
    }

    /** 排序字段 */
    enum class SortKey(val label: String) {
        TITLE("标题"),
        ARTIST("歌手"),
        ALBUM("专辑"),
        DURATION("时长"),
        PATH("路径"),
        BPM("BPM")
    }

    /** 单个排序条件：字段 + 方向 */
    data class SortCriterion(val key: SortKey, val ascending: Boolean = true)

    // ─────────────────────────────────────────────
    // 基础比较器
    // ─────────────────────────────────────────────

    /**
     * 自然排序比较器：把字符串切成「数字段 / 非数字段」交替比较。
     * 数字段按数值大小比较，非数字段按 Collator 比较。
     */
    val naturalComparator: Comparator<String> = Comparator { a, b ->
        naturalCompare(a, b)
    }

    private fun naturalCompare(a: String?, b: String?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1

        var i = 0
        var j = 0
        val n = a.length
        val m = b.length
        while (i < n && j < m) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // 提取完整数字串
                var i2 = i
                while (i2 < n && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < m && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                val byLen = na.length.compareTo(nb.length)
                if (byLen != 0) return byLen
                val byVal = na.compareTo(nb)
                if (byVal != 0) return byVal
                // 数值相等时前导零更少的在前
                val byRawLen = (i2 - i).compareTo(j2 - j)
                if (byRawLen != 0) return byRawLen
                i = i2
                j = j2
            } else {
                // 累积一段非数字字符，交给 Collator
                var i2 = i
                while (i2 < n && !a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < m && !b[j2].isDigit()) j2++
                val sa = a.substring(i, i2)
                val sb = b.substring(j, j2)
                val r = collator.compare(sa, sb)
                if (r != 0) return r
                i = i2
                j = j2
            }
        }
        return (n - i).compareTo(m - j)
    }

    /** 按标题排序的比较器（自然排序 + 路径 tie-break） */
    val byTitle: Comparator<SongEntity> = comparatorFor(SortKey.TITLE)

    /** 通用字符串比较器（多语言 + 自然数字） */
    val stringComparator: Comparator<String> = naturalComparator

    /** 按标题排序的 List 扩展 */
    fun List<SongEntity>.sortedByTitle(): List<SongEntity> = sortedWith(byTitle)

    /**
     * 生成单个字段的比较器（字段内部已包含 path tie-break）
     */
    fun comparatorFor(key: SortKey, ascending: Boolean = true): Comparator<SongEntity> =
        comparatorFor(listOf(SortCriterion(key, ascending)))

    /**
     * 生成多字段比较器。字段按顺序依次比较，全部相等时按 path 稳定排序。
     */
    fun comparatorFor(criteria: List<SortCriterion>): Comparator<SongEntity> {
        val effective = if (criteria.isEmpty()) listOf(SortCriterion(SortKey.TITLE)) else criteria
        return Comparator { a, b ->
            for (c in effective) {
                val r = compareByKey(a, b, c.key)
                if (r != 0) return@Comparator if (c.ascending) r else -r
            }
            // 稳定 tie-break：路径升序
            naturalCompare(a.path, b.path)
        }
    }

    private fun compareByKey(a: SongEntity, b: SongEntity, key: SortKey): Int = when (key) {
        SortKey.TITLE -> naturalCompare(a.title, b.title)
        SortKey.ARTIST -> naturalCompare(a.artist, b.artist)
        SortKey.ALBUM -> naturalCompare(a.album, b.album)
        SortKey.PATH -> naturalCompare(a.path, b.path)
        SortKey.DURATION -> a.duration.compareTo(b.duration)
        SortKey.BPM -> compareBpm(a.bpm, b.bpm)
    }

    /** 未分析（0）的 BPM 视为最大，排在已分析歌曲之后 */
    private fun compareBpm(a: Float, b: Float): Int {
        val av = if (a <= 0f) Float.MAX_VALUE else a
        val bv = if (b <= 0f) Float.MAX_VALUE else b
        return av.compareTo(bv)
    }

    /** 按条件排序（返回新列表） */
    fun sort(songs: List<SongEntity>, criteria: List<SortCriterion>): List<SongEntity> =
        songs.sortedWith(comparatorFor(criteria))

    /** 按单字段排序 */
    fun sort(songs: List<SongEntity>, key: SortKey, ascending: Boolean = true): List<SongEntity> =
        songs.sortedWith(comparatorFor(key, ascending))

    // ─────────────────────────────────────────────
    // 首字母索引（QuickScrollBar 使用）
    // ─────────────────────────────────────────────

    /**
     * 获取标题的首字母桶标签
     * @return "A"~"Z" 或 "#"
     */
    fun getBucketLabel(title: String): String {
        if (title.isEmpty()) return "#"
        val first = title.first()
        return when {
            first in 'A'..'Z' || first in 'a'..'z' -> first.uppercaseChar().toString()
            // CJK / 假名 / 谚文：使用 Collator 与 A-Z 比较来分桶
            first in '\u4E00'..'\u9FFF' ||
            first in '\u3400'..'\u4DBF' ||
            first in '\uF900'..'\uFAFF' ||
            first in '\u3040'..'\u309F' ||
            first in '\u30A0'..'\u30FF' ||
            first in '\uAC00'..'\uD7AF' -> getPinyinFirstLetter(title)
            else -> "#"
        }
    }

    /** 用 Collator 判断中日韩标题应归入哪个 A-Z 桶 */
    private fun getPinyinFirstLetter(title: String): String {
        val test = title.take(2)
        for (letter in 'A'..'Z') {
            if (collator.compare(test, letter.toString()) < 0) {
                return if (letter == 'A') "A" else (letter - 1).toString()
            }
        }
        return "Z"
    }

    /**
     * 为已排序列表构建首字母索引映射
     * @return Map<BucketLetter, FirstIndex>
     */
    fun buildBucketIndex(songs: List<SongEntity>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        songs.forEachIndexed { index, song ->
            val bucket = getBucketLabel(song.title)
            if (bucket !in result) result[bucket] = index
        }
        return result
    }

    /** 为已排序字符串列表构建首字母索引映射 */
    @JvmName("buildBucketIndexFromStrings")
    fun buildBucketIndex(strings: List<String>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        strings.forEachIndexed { index, s ->
            val bucket = getBucketLabel(s)
            if (bucket !in result) result[bucket] = index
        }
        return result
    }

    /** 所有可能的桶标签（A-Z + #） */
    val allBucketLabels: List<String> = ('A'..'Z').map { it.toString() } + "#"

    /** 汉字→拉丁转写器（Android ICU 内置，API 24+，零额外依赖） */
    private val hanToLatin: Transliterator by lazy { Transliterator.getInstance("Han-Latin") }

    /**
     * 获取标题首汉字的拼音（大写，最多 2 字母，无音调）
     * 非中文返回空字符串，用于浮标副显示
     */
    fun getPinyin(title: String): String {
        val first = title.firstOrNull() ?: return ""
        if (first !in '\u4E00'..'\u9FFF' && first !in '\u3400'..'\u4DBF' &&
            first !in '\uF900'..'\uFAFF') return ""
        val latin = hanToLatin.transliterate(first.toString())
        return java.text.Normalizer.normalize(latin, java.text.Normalizer.Form.NFKD)
            .replace(Regex("[^a-zA-Z]"), "")
            .uppercase()
            .take(2)
    }
}
