package com.breezy.music.model

import kotlin.math.abs

/**
 * 运动音乐播放列表生成器
 *
 * 需求：分析曲库 BPM → 读取当前步频 → 从「指定文件夹 / 指定歌单 / 全部歌曲」中
 * 挑选 BPM 相近的歌曲，按设定长度生成一份**定长**播放列表。
 *
 * 实现要点：
 *  - **相近程度** 用「允许的最大 BPM 偏差」表达（[RunningMusicConfig.toleranceBpm]），
 *    先做硬过滤，再做软排序（偏差越小越靠前）；
 *  - **定长** 用总时长表达（分钟），按每首实际时长累加，最后一首允许超出但不超过 90 秒，
 *    避免列表末尾出现半首歌；
 *  - **节拍连续性**：默认启用 [RunningMusicConfig.smoothBpm]，用「最近邻贪心」把候选歌曲
 *    串成相邻 BPM 跳变尽量小的链，跑动中不会出现 90 → 178 的突兀变速；
 *  - **半拍映射**：跑步步频常在 160–180 之间，而多数歌曲 BPM 落在 80–90，
 *    因此支持把目标 BPM 取步频的一半（[RunningMusicConfig.halfTime]）。
 */
object RunningMusicGenerator {

    /** 歌曲来源 */
    enum class Source(val label: String) {
        ALL("全部歌曲"),
        FOLDER("文件夹"),
        PLAYLIST("歌单")
    }

    data class RunningMusicConfig(
        val targetSpm: Int = 170,
        val toleranceBpm: Int = 8,
        val durationMinutes: Int = 30,
        val smoothBpm: Boolean = true,
        val halfTime: Boolean = false,
        val source: Source = Source.ALL,
        /** 来源标识：文件夹为 folderPath，歌单为 m3u8 uri */
        val sourceKey: String? = null
    ) {
        /** 实际用于匹配的目标 BPM */
        val targetBpm: Int get() = if (halfTime) (targetSpm / 2).coerceAtLeast(40) else targetSpm
    }

    /** 生成结果中的单曲（附带与目标的偏差） */
    data class Match(val song: SongEntity, val diff: Float)

    data class Result(
        val matches: List<Match>,
        val targetBpm: Int,
        val averageBpm: Float,
        val totalDurationMs: Long,
        /** 候选池大小（已成功分析 BPM 且属于所选来源的歌曲数） */
        val poolSize: Int,
        /** 满足相近程度条件的歌曲数 */
        val inToleranceCount: Int
    ) {
        val isEmpty: Boolean get() = matches.isEmpty()
    }

    /**
     * 从 [pool] 生成运动播放列表
     * @param pool 候选歌曲（应已包含 bpm 数据，来自全部/文件夹/歌单）
     */
    fun generate(pool: List<SongEntity>, config: RunningMusicConfig): Result {
        val target = config.targetBpm
        val analyzed = pool.filter { it.bpm > 0f }
        val inTolerance = analyzed
            .map { Match(it, abs(it.bpm - target)) }
            .filter { it.diff <= config.toleranceBpm }
            .sortedWith(compareBy<Match> { it.diff }.thenBy { it.song.title })
        val targetMs = config.durationMinutes * 60_000L

        if (inTolerance.isEmpty()) {
            return Result(
                matches = emptyList(),
                targetBpm = target,
                averageBpm = 0f,
                totalDurationMs = 0L,
                poolSize = analyzed.size,
                inToleranceCount = 0
            )
        }

        val chain = if (config.smoothBpm) buildSmoothChain(inTolerance) else inTolerance

        val picked = ArrayList<Match>(chain.size)
        var sum = 0L
        for (m in chain) {
            picked.add(m)
            sum += m.song.duration
            if (sum >= targetMs - 45_000L) break
        }
        val avg = if (picked.isEmpty()) 0f
        else picked.sumOf { it.song.bpm.toDouble() }.toFloat() / picked.size

        return Result(
            matches = picked,
            targetBpm = target,
            averageBpm = avg,
            totalDurationMs = picked.sumOf { it.song.duration },
            poolSize = analyzed.size,
            inToleranceCount = inTolerance.size
        )
    }

    /**
     * 最近邻贪心：从最接近目标的歌开始，每次挑选「与上一首 BPM 差最小」的候选。
     * 这样相邻曲目不会有剧烈变速，同时整体仍围绕目标 BPM。
     */
    private fun buildSmoothChain(sorted: List<Match>): List<Match> {
        if (sorted.size <= 2) return sorted
        val remaining = sorted.toMutableList()
        val result = ArrayList<Match>(sorted.size)
        var current = remaining.removeAt(0)
        result.add(current)
        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestScore = Float.MAX_VALUE
            remaining.forEachIndexed { idx, cand ->
                val jump = abs(cand.song.bpm - current.song.bpm)
                // 主目标：相邻跳变小；次目标：整体仍靠近目标 BPM
                val score = jump * 1.0f + cand.diff * 0.25f
                if (score < bestScore) {
                    bestScore = score
                    bestIdx = idx
                }
            }
            current = remaining.removeAt(bestIdx)
            result.add(current)
        }
        return result
    }

    /** 把 SPM 映射为 1:1 或半拍目标 BPM（供 UI 展示） */
    fun targetBpmFor(spm: Int, halfTime: Boolean): Int =
        if (halfTime) (spm / 2).coerceAtLeast(40) else spm
}
