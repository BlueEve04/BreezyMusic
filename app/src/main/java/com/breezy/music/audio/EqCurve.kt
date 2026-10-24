package com.breezy.music.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 频响曲线计算（纯函数，供 UI 绘图与单元测试使用）。
 *
 * 图示均衡：把各频段增益按**对数频率**线性插值到任意查询频率，
 * 这与 DSP 侧“中心频率对数分布 + 峰值滤波器交叠”的实际响应在视觉上足够接近，
 * 且计算量极小（每帧上百个点也毫无压力）。
 *
 * 参数均衡：对每个频段按其类型（峰值 / 低架 / 高架）用双二阶滤波器的
 * 传递函数幅频响应解析式求和，结果与听感一致。
 */
object EqCurve {

    /** 曲线可视化的采样点数（对数分布）。288 个点在 1080px 宽上约 4px 一个，
     *  再配合绘制时的二次贝塞尔，肉眼看不到折线角。 */
    const val POINTS = 288
    const val MIN_HZ = 20f
    const val MAX_HZ = 20000f

    /** 生成对数分布的频率采样点 */
    fun logFrequencies(points: Int = POINTS, minHz: Float = MIN_HZ, maxHz: Float = MAX_HZ): FloatArray {
        val n = points.coerceAtLeast(2)
        val lo = ln(minHz.toDouble())
        val hi = ln(maxHz.toDouble())
        return FloatArray(n) { i -> kotlin.math.exp(lo + (hi - lo) * i / (n - 1)).toFloat() }
    }

    /** 图示均衡在查询频率上的增益（dB） */
    fun graphicDb(freqs: FloatArray, bandFreqs: FloatArray, bandGains: FloatArray): FloatArray {
        if (bandFreqs.isEmpty()) return FloatArray(freqs.size)
        val last = bandFreqs.size - 1
        return FloatArray(freqs.size) { i ->
            val f = freqs[i]
            when {
                f <= bandFreqs.first() -> bandGains.firstOrNull() ?: 0f
                f >= bandFreqs.last() -> bandGains.getOrElse(last) { 0f }
                else -> {
                    var idx = 0
                    while (idx < last - 1 && bandFreqs[idx + 1] < f) idx++
                    val f0 = bandFreqs[idx]
                    val f1 = bandFreqs[idx + 1]
                    val t = (ln(f / f0) / ln(f1 / f0)).toFloat()
                    val a = bandGains.getOrElse(idx) { 0f }
                    val b = bandGains.getOrElse(idx + 1) { 0f }
                    a + (b - a) * t
                }
            }
        }
    }

    /**
     * 图示均衡的**平滑**曲线（单调三次插值 / PCHIP）。
     *
     * 线性插值会在每个频段中心留下一个折点，段数一多整条曲线就像锯齿。
     * 这里用 PCHIP：它保证不过冲（不会因为插值冒出一个比所有频段都高的峰值），
     * 也保证单调段保持单调，是"看起来顺、但不骗人"的插值方式。
     */
    fun graphicDbSmooth(freqs: FloatArray, bandFreqs: FloatArray, bandGains: FloatArray): FloatArray {
        val n = bandFreqs.size
        if (n < 3) return graphicDb(freqs, bandFreqs, bandGains)
        val xs = DoubleArray(n) { ln(bandFreqs[it].toDouble()) }
        val ys = DoubleArray(n) { bandGains.getOrElse(it) { 0f }.toDouble() }
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val delta = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = DoubleArray(n)
        m[0] = delta[0]
        m[n - 1] = delta[n - 2]
        for (i in 1 until n - 1) {
            if (delta[i - 1] * delta[i] <= 0.0) {
                m[i] = 0.0
            } else {
                val w1 = 2 * h[i] + h[i - 1]
                val w2 = h[i] + 2 * h[i - 1]
                m[i] = (w1 + w2) / (w1 / delta[i - 1] + w2 / delta[i])
            }
        }
        return FloatArray(freqs.size) { idx ->
            val x = ln(freqs[idx].toDouble())
            when {
                x <= xs.first() -> ys.first().toFloat()
                x >= xs.last() -> ys.last().toFloat()
                else -> {
                    var i = 0
                    while (i < n - 2 && xs[i + 1] < x) i++
                    val t = (x - xs[i]) / h[i]
                    val t2 = t * t
                    val t3 = t2 * t
                    val h00 = 2 * t3 - 3 * t2 + 1
                    val h10 = t3 - 2 * t2 + t
                    val h01 = -2 * t3 + 3 * t2
                    val h11 = t3 - t2
                    (h00 * ys[i] + h10 * h[i] * m[i] + h01 * ys[i + 1] + h11 * h[i] * m[i + 1]).toFloat()
                }
            }
        }
    }

    /** 参数均衡在查询频率上的合成响应（dB），各段响应相加 */
    fun parametricDb(freqs: FloatArray, bands: List<ParamBand>, sampleRate: Int = 44100): FloatArray {
        val out = FloatArray(freqs.size)
        bands.forEach { band ->
            val c = coefficients(band, sampleRate) ?: return@forEach
            for (i in freqs.indices) {
                out[i] += magnitudeDb(c, freqs[i].toDouble(), sampleRate.toDouble())
            }
        }
        return out
    }

    /** 五个归一化系数（b0,b1,b2,a1,a2），与 DSP 侧保持同一套公式 */
    fun coefficients(band: ParamBand, sampleRate: Int = 44100): FloatArray? {
        val sr = sampleRate.toDouble()
        val fc = band.freqHz.coerceIn(20f, (sr * 0.45).toFloat()).toDouble()
        val w0 = 2.0 * PI * fc / sr
        val cosw = cos(w0)
        val sinw = sin(w0)
        val a = 10.0.pow(band.gainDb / 40.0)
        return when (band.type) {
            BreezyDspProcessor.TYPE_LOW_SHELF -> {
                val alpha = sinw / 2.0 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2)
                val twoSqrt = 2 * sqrt(a) * alpha
                val b0 = a * ((a + 1) - (a - 1) * cosw + twoSqrt)
                val b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
                val b2 = a * ((a + 1) - (a - 1) * cosw - twoSqrt)
                val a0 = (a + 1) + (a - 1) * cosw + twoSqrt
                val a1 = -2 * ((a - 1) + (a + 1) * cosw)
                val a2 = (a + 1) + (a - 1) * cosw - twoSqrt
                normalize(b0, b1, b2, a0, a1, a2)
            }

            BreezyDspProcessor.TYPE_HIGH_SHELF -> {
                val alpha = sinw / 2.0 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2)
                val twoSqrt = 2 * sqrt(a) * alpha
                val b0 = a * ((a + 1) + (a - 1) * cosw + twoSqrt)
                val b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
                val b2 = a * ((a + 1) + (a - 1) * cosw - twoSqrt)
                val a0 = (a + 1) - (a - 1) * cosw + twoSqrt
                val a1 = 2 * ((a - 1) - (a + 1) * cosw)
                val a2 = (a + 1) - (a - 1) * cosw - twoSqrt
                normalize(b0, b1, b2, a0, a1, a2)
            }

            else -> {
                if (band.gainDb == 0f) return floatArrayOf(1f, 0f, 0f, 0f, 0f)
                val alpha = sinw / (2.0 * band.q)
                val b0 = 1 + alpha * a
                val b1 = -2 * cosw
                val b2 = 1 - alpha * a
                val a0 = 1 + alpha / a
                val a1 = -2 * cosw
                val a2 = 1 - alpha / a
                normalize(b0, b1, b2, a0, a1, a2)
            }
        }
    }

    private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        floatArrayOf((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

    /** 双二阶在角频率 w 处的幅频响应（dB） */
    fun magnitudeDb(c: FloatArray, freqHz: Double, sampleRate: Double): Float {
        val w = 2.0 * PI * freqHz / sampleRate
        val cosw = cos(w)
        val cos2w = cos(2 * w)
        val sinw = sin(w)
        val sin2w = sin(2 * w)
        val numRe = c[0] + c[1] * cosw + c[2] * cos2w
        val numIm = -(c[1] * sinw + c[2] * sin2w)
        val denRe = 1.0 + c[3] * cosw + c[4] * cos2w
        val denIm = -(c[3] * sinw + c[4] * sin2w)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        val mag = if (den < 1e-12) 1.0 else num / den
        return (20.0 * log10(mag.coerceAtLeast(1e-6))).toFloat()
    }
}
