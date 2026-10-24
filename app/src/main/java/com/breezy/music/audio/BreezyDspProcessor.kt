package com.breezy.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 播放链路 DSP：多段参数均衡器（EQ）+ 前级增益 + 混响
 *
 * 作用位置：ExoPlayer 的 [AudioProcessor] 链（解码之后的 PCM 阶段），
 * 因此对**所有**音源（本地文件、SAF content URI）统一生效，与系统 EQ 无关，也不依赖厂商实现。
 *
 * 均衡器为「峰值型（peaking EQ）」二阶滤波器的级联：
 *  - 每个频段的中心频率按对数均匀分布（31 Hz – 16 kHz）；
 *  - 增益范围 ±12 dB，Q 由 [setBandCount] 自动计算，保证相邻频段交叠平滑；
 *  - 系数按 RBJ Audio EQ Cookbook 公式计算，切换预设时只重算系数，不打断播放。
 *
 * 混响为经典 Schroeder 结构（4 梳状 + 2 全通），只做“轻度空间感”，
 * 因此延迟线长度按 44.1 kHz 设计并在其他采样率下等比缩放。
 */
class BreezyDspProcessor : BaseAudioProcessor() {

    @Volatile private var bandCount = MAX_BANDS
    @Volatile private var gains = FloatArray(MAX_BANDS)
    /** true = 参数均衡模式（频段频率/Q/类型可自由定义），false = 图示均衡模式（等分频段） */
    @Volatile private var paramMode = false

    // ── 限压器（峰值限幅）──
    // 放在整条链路最后（EQ → 混响 → 限压），避免前面各段叠加后把输出推过 0 dBFS。
    // 只做峰值限幅，不做响度压缩：超过阈值就往下压，松开后按释放时间缓慢恢复。
    @Volatile private var limiterEnabled = false

    /** 当前增益衰减量 0~1，供 UI 做触发闪烁（1 表示压得很狠） */
    @Volatile private var limiterReduction = 0f
    private var limiterEnv = 1f
    private var limiterAttack = 0.05f
    private var limiterRelease = 0.0005f

    /**
     * 阈值默认 −1 dBFS：再高会在部分设备的 DAC 上削波，再低会明显损失响度。
     * 允许用户改（长按限压按钮），范围 −12…0 dB，值越小压得越狠。
     */
    @Volatile private var limiterThreshold = 0.891f
    @Volatile private var paramBands: Array<ParamBand> = DEFAULT_PARAM_BANDS.copyOf()
    @Volatile private var preampDb = 0f
    @Volatile private var eqEnabled = true
    @Volatile private var reverbEnabled = false
    @Volatile private var reverbMix = 0.25f
    @Volatile private var reverbRoom = 0.72f

    // 运行期状态（只在音频线程访问）
    private var channels = 2
    private var sampleRate = 44100
    private var dirty = true

    // 双二阶系数（每个频段 b0,b1,b2,a1,a2）
    private var coeffs = FloatArray(MAX_BANDS * 5)
    // 每个通道每个频段 4 个状态（x1,x2,y1,y2）
    private var state = FloatArray(0)
    private var preampLinear = 1f

    // 混响延迟线
    private var combs: Array<FloatArray> = emptyArray()
    private var combIdx = IntArray(0)
    private var combFilterStore = FloatArray(0)
    private var allpasses: Array<FloatArray> = emptyArray()
    private var allpassIdx = IntArray(0)
    private var leftover = ByteArray(0)

    // ─────────────────────────────────────────────
    // 对外参数设置（可在主线程调用）
    // ─────────────────────────────────────────────

    fun setLimiterEnabled(enabled: Boolean) {
        limiterEnabled = enabled
        if (!enabled) {
            limiterEnv = 1f
            limiterReduction = 0f
        }
    }

    fun isLimiterEnabled(): Boolean = limiterEnabled

    /** 设置阈值（dBFS，-12…0）：越小越早开始压，听感上"强度"越高 */
    fun setLimiterThresholdDb(db: Float) {
        val v = db.coerceIn(-12f, 0f)
        limiterThreshold = 10f.pow(v / 20f)
    }

    /** 0~1 的实时增益衰减量；UI 每帧读一次即可，不需要回调 */
    fun limiterGainReduction(): Float = limiterReduction

    fun setEnabled(enabled: Boolean) {
        eqEnabled = enabled
        dirty = true
    }

    fun isEnabled(): Boolean = eqEnabled

    fun setPreamp(db: Float) {
        preampDb = db.coerceIn(-12f, 12f)
        dirty = true
    }

    fun setBandCount(count: Int) {
        val c = count.coerceIn(3, 16)
        if (c != bandCount) {
            bandCount = c
            gains = FloatArray(MAX_BANDS) { gains.getOrElse(it) { 0f } }
            dirty = true
        }
    }

    fun getBandCount(): Int = bandCount

    fun setAllGains(values: FloatArray) {
        val copy = FloatArray(MAX_BANDS)
        for (i in 0 until minOf(values.size, MAX_BANDS)) copy[i] = values[i].coerceIn(-12f, 12f)
        gains = copy
        dirty = true
    }

    fun getGains(): FloatArray = gains.copyOf(bandCount)

    fun setBandGain(index: Int, db: Float) {
        if (index !in 0 until bandCount) return
        val copy = gains.copyOf()
        copy[index] = db.coerceIn(-12f, 12f)
        gains = copy
        dirty = true
    }

    fun setReverb(enabled: Boolean, mix: Float = reverbMix, room: Float = reverbRoom) {
        reverbEnabled = enabled
        reverbMix = mix.coerceIn(0f, 1f)
        reverbRoom = room.coerceIn(0.5f, 0.92f)
        dirty = true
    }

    fun setMode(parametric: Boolean) {
        if (paramMode != parametric) {
            paramMode = parametric
            dirty = true
        }
    }

    fun isParametric(): Boolean = paramMode

    fun setParamBands(bands: List<ParamBand>) {
        val copy = Array(MAX_PARAM_BANDS) { i ->
            bands.getOrElse(i) { DEFAULT_PARAM_BANDS[i] }.sanitized()
        }
        paramBands = copy
        dirty = true
    }

    fun getParamBands(): List<ParamBand> = paramBands.toList()

    fun isReverbEnabled(): Boolean = reverbEnabled
    fun getReverbMix(): Float = reverbMix
    fun getReverbRoom(): Float = reverbRoom
    fun getPreamp(): Float = preampDb

    // ─────────────────────────────────────────────
    // AudioProcessor 生命周期
    // ─────────────────────────────────────────────

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = inputAudioFormat.channelCount.coerceIn(1, 2)
        sampleRate = inputAudioFormat.sampleRate
        state = FloatArray(channels * MAX_BANDS * 4)
        dirty = true
        rebuildReverb()
        updateLimiterCoefficients(sampleRate)
        limiterEnv = 1f
        return inputAudioFormat
    }

    private fun updateLimiterCoefficients(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(8000).toFloat()
        // 攻击 0.8 ms、释放 120 ms，都用一阶低通系数：1 - e^(-1/(τ·sr))
        limiterAttack = (1f - kotlin.math.exp(-1f / (0.0008f * sr))).coerceIn(0.001f, 0.5f)
        limiterRelease = (1f - kotlin.math.exp(-1f / (0.120f * sr))).coerceIn(0.0001f, 0.5f)
    }

    override fun onFlush() {
        state.fill(0f)
        combIdx.fill(0)
        allpassIdx.fill(0)
        combFilterStore.fill(0f)
        combs.forEach { it.fill(0f) }
        allpasses.forEach { it.fill(0f) }
        leftover = ByteArray(0)
        dirty = true
    }

    override fun onReset() {
        onFlush()
    }

    // ─────────────────────────────────────────────
    // 系数计算
    // ─────────────────────────────────────────────

    private fun recompute() {
        preampLinear = 10f.pow(preampDb / 20f)
        if (paramMode) {
            val n = MAX_PARAM_BANDS
            for (b in 0 until n) {
                val band = paramBands[b]
                when (band.type) {
                    TYPE_LOW_SHELF -> biquadLowShelf(band.freqHz, band.gainDb, b)
                    TYPE_HIGH_SHELF -> biquadHighShelf(band.freqHz, band.gainDb, b)
                    else -> {
                        // 0 dB 的峰值段用直通系数，避免白算
                        if (band.gainDb == 0f) biquadIdentity(b)
                        else biquadPeaking(band.freqHz, band.q, band.gainDb, b)
                    }
                }
            }
        } else {
            val n = bandCount
            // 频率按对数分布：31Hz – 16kHz（不超过 Nyquist 的 0.45）
            val fLow = 31.0
            val fHigh = minOf(16000.0, sampleRate * 0.45)
            val q = 1.1 * (n / 16.0).coerceAtLeast(0.6) + 0.4
            for (b in 0 until n) {
                val t = if (n == 1) 0.5 else b.toDouble() / (n - 1)
                val fc = fLow * (fHigh / fLow).pow(t)
                biquadPeaking(fc.toFloat(), q.toFloat(), gains[b], b)
            }
        }
        dirty = false
    }

    private fun biquadIdentity(index: Int) {
        coeffs[index * 5] = 1f
        coeffs[index * 5 + 1] = 0f
        coeffs[index * 5 + 2] = 0f
        coeffs[index * 5 + 3] = 0f
        coeffs[index * 5 + 4] = 0f
    }

    /** 峰值型（peaking）二阶段，RBJ Cookbook */
    private fun biquadPeaking(freqHz: Float, q: Float, gainDb: Float, index: Int) {
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f).toDouble()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val alpha = sin(w0) / (2.0 * q.toDouble())
        val cosw = cos(w0)
        val b0 = 1 + alpha * a
        val b1 = -2 * cosw
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosw
        val a2 = 1 - alpha / a
        writeCoeffs(index, b0, b1, b2, a0, a1, a2)
    }

    /** 低架（low shelf），斜率固定 1.0，用于整体抬升低频 */
    private fun biquadLowShelf(freqHz: Float, gainDb: Float, index: Int) {
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f).toDouble()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosw = cos(w0)
        val sinw = sin(w0)
        val s = 1.0
        val alpha = sinw / 2.0 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) - (a - 1) * cosw + twoSqrtAAlpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
        val b2 = a * ((a + 1) - (a - 1) * cosw - twoSqrtAAlpha)
        val a0 = (a + 1) + (a - 1) * cosw + twoSqrtAAlpha
        val a1 = -2 * ((a - 1) + (a + 1) * cosw)
        val a2 = (a + 1) + (a - 1) * cosw - twoSqrtAAlpha
        writeCoeffs(index, b0, b1, b2, a0, a1, a2)
    }

    /** 高架（high shelf），斜率固定 1.0 */
    private fun biquadHighShelf(freqHz: Float, gainDb: Float, index: Int) {
        val fc = freqHz.coerceIn(20f, sampleRate * 0.45f).toDouble()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / sampleRate
        val cosw = cos(w0)
        val sinw = sin(w0)
        val s = 1.0
        val alpha = sinw / 2.0 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        val b0 = a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
        val b2 = a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha)
        val a0 = (a + 1) - (a - 1) * cosw + twoSqrtAAlpha
        val a1 = 2 * ((a - 1) - (a + 1) * cosw)
        val a2 = (a + 1) - (a - 1) * cosw - twoSqrtAAlpha
        writeCoeffs(index, b0, b1, b2, a0, a1, a2)
    }

    private fun writeCoeffs(index: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        coeffs[index * 5] = (b0 / a0).toFloat()
        coeffs[index * 5 + 1] = (b1 / a0).toFloat()
        coeffs[index * 5 + 2] = (b2 / a0).toFloat()
        coeffs[index * 5 + 3] = (a1 / a0).toFloat()
        coeffs[index * 5 + 4] = (a2 / a0).toFloat()
    }

    private fun rebuildReverb() {
        val scale = sampleRate / 44100.0
        val combDelays = intArrayOf(1116, 1188, 1277, 1356).map { (it * scale).toInt().coerceAtLeast(64) }
        combs = Array(combDelays.size) { FloatArray(combDelays[it]) }
        combIdx = IntArray(combDelays.size)
        combFilterStore = FloatArray(combDelays.size)
        val apDelays = intArrayOf(556, 441).map { (it * scale).toInt().coerceAtLeast(32) }
        allpasses = Array(apDelays.size) { FloatArray(apDelays[it]) }
        allpassIdx = IntArray(apDelays.size)
    }

    // ─────────────────────────────────────────────
    // 音频处理
    // ─────────────────────────────────────────────

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (dirty) recompute()
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // 关键：解码器输出可能是 16bit 整数，也可能是 float（高解析度音源、24bit FLAC 等）。
        // 早期版本一律按 16bit 解析，遇到 float 缓冲就会把 IEEE-754 的位模式当成两个 16bit 样本，
        // 听到的就是白噪音。这里按 inputAudioFormat 的编码分别处理，输出保持同一种编码。
        val floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bps = if (floatIn) 4 else 2

        val bytes = ByteArray(remaining)
        inputBuffer.get(bytes)

        val full: ByteArray = if (leftover.isNotEmpty()) {
            val merged = ByteArray(leftover.size + remaining)
            System.arraycopy(leftover, 0, merged, 0, leftover.size)
            System.arraycopy(bytes, 0, merged, leftover.size, remaining)
            leftover = ByteArray(0)
            merged
        } else {
            bytes
        }

        val sampleCount = full.size / bps
        if (sampleCount == 0) {
            leftover = full
            return
        }
        val tail = sampleCount * bps
        leftover = if (tail < full.size) full.copyOfRange(tail, full.size) else ByteArray(0)

        val out = replaceOutputBuffer(sampleCount * bps)
        val n = if (paramMode) MAX_PARAM_BANDS else bandCount
        val ch = channels
        val localState = state
        val localCoeffs = coeffs
        val useEq = eqEnabled
        val useReverb = reverbEnabled
        val useLimiter = limiterEnabled
        var peakReduction = 0f

        for (s in 0 until sampleCount) {
            val off = s * bps
            var sample = if (floatIn) {
                val bits = (full[off].toInt() and 0xFF) or
                    ((full[off + 1].toInt() and 0xFF) shl 8) or
                    ((full[off + 2].toInt() and 0xFF) shl 16) or
                    ((full[off + 3].toInt() and 0xFF) shl 24)
                Float.fromBits(bits)
            } else {
                val lo = full[off].toInt() and 0xFF
                val hi = full[off + 1].toInt() and 0xFF
                ((hi shl 8) or lo).toShort().toFloat() / 32768f
            }

            sample *= preampLinear
            if (!sample.isFinite()) sample = 0f

            if (useEq) {
                val channel = s % ch
                val base = channel * n * 4
                for (b in 0 until n) {
                    val c = b * 5
                    val b0 = localCoeffs[c]
                    val b1 = localCoeffs[c + 1]
                    val b2 = localCoeffs[c + 2]
                    val a1 = localCoeffs[c + 3]
                    val a2 = localCoeffs[c + 4]
                    val st = base + b * 4
                    val x1 = localState[st]
                    val x2 = localState[st + 1]
                    val y1 = localState[st + 2]
                    val y2 = localState[st + 3]
                    val y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    localState[st] = sample
                    localState[st + 1] = x1
                    localState[st + 2] = y
                    localState[st + 3] = y1
                    sample = y
                }
            }

            if (useReverb) {
                var wet = 0f
                for (i in combs.indices) {
                    val line = combs[i]
                    val idx = combIdx[i]
                    val delayed = line[idx]
                    combFilterStore[i] = delayed * (1f - 0.25f) + combFilterStore[i] * 0.25f
                    line[idx] = sample + combFilterStore[i] * reverbRoom
                    combIdx[i] = (idx + 1) % line.size
                    wet += delayed
                }
                wet /= combs.size
                for (i in allpasses.indices) {
                    val line = allpasses[i]
                    val idx = allpassIdx[i]
                    val delayed = line[idx]
                    val outAp = -sample + delayed
                    line[idx] = sample + delayed * 0.5f
                    allpassIdx[i] = (idx + 1) % line.size
                    wet = outAp
                }
                sample = sample * (1f - reverbMix) + wet * reverbMix
            }

            if (useLimiter) {
                val peak = if (sample < 0f) -sample else sample
                val target = if (peak > limiterThreshold) limiterThreshold / peak else 1f
                // 没有前瞻缓冲，攻击就必须是"瞬时"的：直接用本样本算出的目标增益，
                // 这样任何样本都不可能越过阈值。恢复用释放系数缓慢回升，避免抽气感。
                // （早期版本对攻击也做平滑，结果峰值 1.15 只压下去 0.0002，等于没限。）
                limiterEnv = if (target < limiterEnv) target else limiterEnv + (target - limiterEnv) * limiterRelease
                if (limiterEnv < 0.9999f) sample *= limiterEnv
                val reduction = 1f - limiterEnv
                if (reduction > peakReduction) peakReduction = reduction
            }

            if (floatIn) {
                val bits = java.lang.Float.floatToRawIntBits(sample)
                out.put((bits and 0xFF).toByte())
                out.put(((bits shr 8) and 0xFF).toByte())
                out.put(((bits shr 16) and 0xFF).toByte())
                out.put(((bits shr 24) and 0xFF).toByte())
            } else {
                val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
            }
        }
        if (useLimiter) limiterReduction = peakReduction
        out.flip()
    }

    companion object {
        /** 段数上限：图示与参数都支持到 20 段 */
        const val MAX_BANDS = 20
        /** 参数均衡最多 20 段（与图示均衡对齐，覆盖 20 Hz – 18 kHz） */
        const val MAX_PARAM_BANDS = MAX_BANDS
        const val TYPE_PEAK = 0
        const val TYPE_LOW_SHELF = 1
        const val TYPE_HIGH_SHELF = 2

        /** 20 段默认频段：20 Hz 到 18 kHz 对数等分，首段低架、末段高架 */
        val DEFAULT_PARAM_FREQS: FloatArray = FloatArray(MAX_PARAM_BANDS) { i ->
            val t = i.toDouble() / (MAX_PARAM_BANDS - 1)
            (20.0 * Math.pow(18000.0 / 20.0, t)).toFloat()
        }

        val DEFAULT_PARAM_BANDS: Array<ParamBand> = Array(MAX_PARAM_BANDS) { i ->
            ParamBand(
                type = when (i) {
                    0 -> TYPE_LOW_SHELF
                    MAX_PARAM_BANDS - 1 -> TYPE_HIGH_SHELF
                    else -> TYPE_PEAK
                },
                freqHz = DEFAULT_PARAM_FREQS[i],
                q = 1.0f,
                gainDb = 0f
            )
        }

        /** 供 UI 展示的频段中心频率（Hz） */
        fun centerFrequencies(bandCount: Int, sampleRate: Int = 44100): FloatArray {
            val n = bandCount.coerceIn(3, MAX_BANDS)
            val fLow = 31.0
            val fHigh = minOf(16000.0, sampleRate * 0.45)
            return FloatArray(n) { b ->
                val t = if (n == 1) 0.5 else b.toDouble() / (n - 1)
                (fLow * (fHigh / fLow).pow(t)).toFloat()
            }
        }

        /** 把频率格式化为短标签（31、62、125、250、500、1k、2k…） */
        /**
         * 频段标签：>1k 一律带 k，并保留 3 位有效数字。
         * 1.06k / 2.5k / 12.6k / 18.0k 而不是 1k / 2k / 12.6k / 18k——
         * 段数一多，两个相邻频段（如 1k 与 1.06k）会被四舍五入成同一个标签。
         * 末尾的 0 只在它是"补位"时才去掉（2.00 → 2，12.60 → 12.6）。
         */
        fun formatFrequency(hz: Float): String {
            if (hz.isNaN() || hz <= 0f) return "-"
            val v = hz.coerceIn(20f, 20000f)
            if (v < 1000f) return String.format(Locale.US, "%.0f", v)
            val k = v / 1000f
            val raw = if (k >= 10f) String.format(Locale.US, "%.1fk", k)
            else String.format(Locale.US, "%.2fk", k)
            return raw
        }

        /** 便于测试：判断给定频率是否为有效频段 */
        internal fun isValidFrequency(hz: Float): Boolean = hz in 20f..20000f && !hz.isNaN()

        /** 供单测使用：dB → 线性增益 */
        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

        /** 供单测使用：线性增益 → dB */
        fun linearToDb(linear: Float): Float = (20f * kotlin.math.log10(linear.coerceAtLeast(1e-6f)))
    }
}
