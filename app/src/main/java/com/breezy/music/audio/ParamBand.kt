package com.breezy.music.audio

/**
 * 参数均衡的一个频段。
 *
 * [type] 取值见 [BreezyDspProcessor.TYPE_PEAK] / [TYPE_LOW_SHELF] / [TYPE_HIGH_SHELF]；
 * 架式滤波器只用 [freqHz] 与 [gainDb]，[q] 仅对峰值段有效。
 */
data class ParamBand(
    val type: Int = BreezyDspProcessor.TYPE_PEAK,
    val freqHz: Float = 1000f,
    val q: Float = 1.0f,
    val gainDb: Float = 0f
) {
    fun sanitized(): ParamBand = ParamBand(
        type = when (type) {
            BreezyDspProcessor.TYPE_LOW_SHELF, BreezyDspProcessor.TYPE_HIGH_SHELF -> type
            else -> BreezyDspProcessor.TYPE_PEAK
        },
        freqHz = freqHz.coerceIn(20f, 20000f),
        q = q.coerceIn(0.3f, 10f),
        gainDb = gainDb.coerceIn(-12f, 12f)
    )

    val typeLabel: String
        get() = when (type) {
            BreezyDspProcessor.TYPE_LOW_SHELF -> "低架"
            BreezyDspProcessor.TYPE_HIGH_SHELF -> "高架"
            else -> "峰值"
        }
}
