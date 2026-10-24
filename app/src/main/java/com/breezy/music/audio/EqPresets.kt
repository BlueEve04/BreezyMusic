package com.breezy.music.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 均衡器预设
 *
 * 频段沿用 ISO 推荐的 10 段中心频率（31 / 62 / 125 / 250 / 500 Hz，1 / 2 / 4 / 8 / 16 kHz），
 * 这是 Winamp、AIMP、Poweramp 等主流播放器自定义均衡器预设时通用的参考频率，
 * 因此本应用的预设可以直接与这些播放器的预设互相迁移。
 *
 * 应用端支持 5 / 10 / 16 段可调，[gainsForBandCount] 会把 10 段参考增益按对数频率插值到
 * 目标段数，避免“段数一改，音色就跳变”。
 */
data class EqPreset(
    val id: String,
    val name: String,
    /** 10 段 ISO 中心频率上的增益（dB），图示均衡使用 */
    val gains: FloatArray,
    val builtin: Boolean = true,
    val reverbMix: Float = 0f,
    val reverbRoom: Float = 0.72f,
    /** 该预设属于哪种均衡形态；参数均衡预设会带上 [paramBands] */
    val mode: EqMode = EqMode.GRAPHIC,
    val paramBands: List<ParamBand> = emptyList(),
    /** 这个预设自己的段数；0 表示用定义值（图示取 gains.size，参数取 paramBands.size） */
    val bandCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqPreset) return false
        return id == other.id && name == other.name && gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int = id.hashCode() * 31 + gains.contentHashCode()
}

object EqPresets {

    /** ISO 10 段中心频率 */
    val isoFrequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    val FLAT = EqPreset(
        id = "flat", name = "平直",
        gains = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    )

    /**
     * 内置预设。数值参考主流播放器（Winamp / AIMP / Poweramp）公布的内置预设曲线，
     * 并按 ISO 10 段频率整理；括号内为典型适用场景，写进 UI 帮助用户理解。
     */
    val BUILTIN: List<EqPreset> = listOf(
        FLAT,
        EqPreset(
            id = "pop", name = "流行 Pop",
            gains = floatArrayOf(-1f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -2f)
        ),
        EqPreset(
            id = "rock", name = "摇滚 Rock",
            gains = floatArrayOf(5f, 4f, 2f, 0f, -2f, -1f, 1f, 3f, 4f, 5f)
        ),
        EqPreset(
            id = "jazz", name = "爵士 Jazz",
            gains = floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)
        ),
        EqPreset(
            id = "classical", name = "古典 Classical",
            gains = floatArrayOf(4f, 3f, 2f, 1f, 0f, 0f, 0f, 1f, 2f, 3f)
        ),
        EqPreset(
            id = "bass_boost", name = "低音增强 Bass Boost",
            gains = floatArrayOf(7f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
        ),
        EqPreset(
            id = "treble_boost", name = "高音增强 Treble Boost",
            gains = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 6f, 7f)
        ),
        EqPreset(
            id = "vocal", name = "人声 Vocal",
            gains = floatArrayOf(-2f, -1f, 0f, 2f, 5f, 5f, 4f, 2f, 0f, -1f)
        ),
        EqPreset(
            id = "electronic", name = "电子 Electronic",
            gains = floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f)
        ),
        EqPreset(
            id = "acoustic", name = "原声 Acoustic",
            gains = floatArrayOf(2f, 1f, 0f, 1f, 2f, 2f, 3f, 3f, 2f, 1f),
            reverbMix = 0.18f,
            reverbRoom = 0.62f
        ),
        EqPreset(
            id = "hiphop", name = "嘻哈 Hip-Hop",
            gains = floatArrayOf(6f, 5f, 3f, 1f, -1f, 0f, 1f, 2f, 3f, 3f)
        ),
        EqPreset(
            id = "loudness", name = "响度 Loudness",
            gains = floatArrayOf(6f, 4f, 0f, -1f, -2f, -1f, 0f, 2f, 5f, 6f)
        ),
        EqPreset(
            id = "night", name = "夜间 Night",
            gains = floatArrayOf(-3f, -2f, 0f, 2f, 3f, 3f, 2f, 1f, -1f, -3f)
        ),
        EqPreset(
            id = "running", name = "运动 Running",
            gains = floatArrayOf(4f, 3f, 1f, 0f, 1f, 3f, 4f, 3f, 2f, 1f)
        )
    )

    fun findBuiltin(id: String?): EqPreset? = BUILTIN.firstOrNull { it.id == id }

    /**
     * 把 10 段参考增益插值到任意段数。
     * 插值在对数频率轴、线性 dB 轴上做，相邻频段线性过渡。
     */
    fun gainsForBandCount(preset: EqPreset, bandCount: Int, sampleRate: Int = 44100): FloatArray {
        val freqs = BreezyDspProcessor.centerFrequencies(bandCount, sampleRate)
        return FloatArray(freqs.size) { i -> interpolate(preset.gains, freqs[i]) }
    }

    private fun interpolate(gains: FloatArray, freq: Float): Float {
        if (freq <= isoFrequencies.first()) return gains.first()
        if (freq >= isoFrequencies.last()) return gains.last()
        for (i in 0 until isoFrequencies.size - 1) {
            val f0 = isoFrequencies[i]
            val f1 = isoFrequencies[i + 1]
            if (freq in f0..f1) {
                val t = log10(freq / f0) / log10(f1 / f0)
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }

    /** 预设之间的差距（用于判断用户是否改动过预设） */
    fun distance(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var sum = 0f
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum / n.coerceAtLeast(1)
    }

    /**
     * 简易「智能均衡」：把增益归一化到目标响度附近，避免某个频段过度提升导致削波。
     * @param maxTotalBoost 允许的累计提升上限（dB），16 段一般不超过 24 dB
     */
    fun normalize(gains: FloatArray, maxTotalBoost: Float = 24f): FloatArray {
        val positive = gains.filter { it > 0f }.sum()
        if (positive <= maxTotalBoost) return gains
        val k = maxTotalBoost / positive
        return FloatArray(gains.size) { gains[it] * k }
    }

    /** dB → 线性（供 UI 画频谱增益柱） */
    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
}
