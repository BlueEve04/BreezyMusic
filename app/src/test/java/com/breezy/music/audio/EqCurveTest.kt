package com.breezy.music.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频响曲线计算的单元测试。
 *
 * 这些断言保护的是「曲线可视化与 DSP 实际响应一致」这一约定：
 * 如果哪天有人改了滤波器公式却忘了改 UI，频率响应会在这里先炸。
 */
class EqCurveTest {

    private val iso = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    @Test
    fun `全零增益时图示曲线恒为 0 dB`() {
        val freqs = EqCurve.logFrequencies()
        val gains = FloatArray(10)
        val curve = EqCurve.graphicDb(freqs, iso, gains)
        assertEquals(freqs.size, curve.size)
        curve.forEach { assertEquals(0f, it, 1e-4f) }
    }

    @Test
    fun `图示曲线在频段中心等于该段增益`() {
        val freqs = EqCurve.logFrequencies()
        val gains = FloatArray(10) { 0f }
        gains[4] = 6f // 500 Hz
        val curve = EqCurve.graphicDb(freqs, iso, gains)
        val idx = freqs.indexOfFirst { it >= 500f }
        assertTrue("应找到 500Hz 附近的采样点", idx > 0)
        assertEquals(6f, curve[idx], 0.6f)
        // 最左端应回落到 0 dB
        assertEquals(0f, curve.first(), 0.05f)
    }

    @Test
    fun `低架滤波器在低频提升而在高频不提升`() {
        val band = ParamBand(type = BreezyDspProcessor.TYPE_LOW_SHELF, freqHz = 200f, q = 0.7f, gainDb = 9f)
        val c = EqCurve.coefficients(band, 44100)!!
        val low = EqCurve.magnitudeDb(c, 40.0, 44100.0)
        val high = EqCurve.magnitudeDb(c, 8000.0, 44100.0)
        assertTrue("40Hz 应提升，实际 $low", low > 6f)
        assertTrue("8kHz 应基本不变，实际 $high", kotlin.math.abs(high) < 0.6f)
    }

    @Test
    fun `峰值滤波器在中心频率达到设定增益`() {
        val band = ParamBand(type = BreezyDspProcessor.TYPE_PEAK, freqHz = 1000f, q = 2f, gainDb = -6f)
        val c = EqCurve.coefficients(band, 44100)!!
        val atCenter = EqCurve.magnitudeDb(c, 1000.0, 44100.0)
        assertEquals(-6f, atCenter, 0.4f)
        val far = EqCurve.magnitudeDb(c, 100.0, 44100.0)
        assertTrue("远离中心应接近 0 dB，实际 $far", kotlin.math.abs(far) < 0.5f)
    }

    @Test
    fun `参数曲线是多段响应的叠加`() {
        val freqs = EqCurve.logFrequencies()
        val bands = listOf(
            ParamBand(BreezyDspProcessor.TYPE_LOW_SHELF, 120f, 0.7f, 6f),
            ParamBand(BreezyDspProcessor.TYPE_PEAK, 1000f, 1.4f, -5f)
        )
        val combined = EqCurve.parametricDb(freqs, bands)
        val onlyFirst = EqCurve.parametricDb(freqs, listOf(bands[0]))
        val onlySecond = EqCurve.parametricDb(freqs, listOf(bands[1]))
        for (i in freqs.indices) {
            assertEquals(combined[i], onlyFirst[i] + onlySecond[i], 0.01f)
        }
    }

    @Test
    fun `零增益的参数段不改变曲线`() {
        val freqs = EqCurve.logFrequencies()
        val flat = ParamBand(BreezyDspProcessor.TYPE_PEAK, 1000f, 1f, 0f)
        val curve = EqCurve.parametricDb(freqs, listOf(flat))
        curve.forEach { assertEquals(0f, it, 1e-3f) }
    }

    @Test
    fun `对数频率采样点单调递增且覆盖 20Hz 到 20kHz`() {
        val freqs = EqCurve.logFrequencies()
        assertEquals(EqCurve.POINTS, freqs.size)
        assertEquals(20f, freqs.first(), 0.01f)
        assertEquals(20000f, freqs.last(), 1f)
        for (i in 1 until freqs.size) assertTrue(freqs[i] > freqs[i - 1])
    }
}
