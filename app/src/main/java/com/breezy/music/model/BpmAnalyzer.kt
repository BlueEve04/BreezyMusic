package com.breezy.music.model

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * BPM（每分钟拍数）离线分析器
 *
 * 处理流程（纯 CPU、无第三方依赖）：
 *  1. **解码**：MediaExtractor + MediaCodec 把任意可播放音频解码为 16bit PCM，
 *     下混为单声道并线性插值重采样到 [TARGET_RATE]（22.05 kHz），显著降低后续计算量。
 *  2. **能量包络**：按 [HOP] 个采样点计算 RMS 能量，得到帧率约 43 fps 的包络。
 *  3. **起始点检测**：对包络做一阶差分并半波整流、去均值，得到 onset strength（谱通量近似）。
 *  4. **自相关**：在 60–200 BPM 对应的滞后区间内做自相关，取峰值并做抛物线插值，
 *     再把结果折叠到 70–180 BPM（解决倍频/半频歧义）。
 *
 * 该算法对流行、电子、摇滚、说唱等强节奏音乐准确度较高；对古典/纯人声可能返回 0（视为未知）。
 */
object BpmAnalyzer {

    private const val TARGET_RATE = 22050
    private const val HOP = 512                 // 包络帧移
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0
    private const val PREFERRED_LOW = 70.0
    private const val PREFERRED_HIGH = 180.0

    /** 分析一首歌，返回 BPM；失败或不可判定时返回 0f */
    suspend fun analyze(context: Context, path: String, maxSeconds: Int = 60): Float =
        withContext(Dispatchers.Default) {
            try {
                val pcm = decodeMono(context, path, maxSeconds)
                if (pcm.size < TARGET_RATE * 5) return@withContext 0f
                detectBpm(pcm)
            } catch (t: Throwable) {
                android.util.Log.w("BpmAnalyzer", "analyze failed: $path", t)
                0f
            }
        }

    // ─────────────────────────────────────────────
    // 1. 解码 + 下混 + 重采样
    // ─────────────────────────────────────────────

    private fun decodeMono(context: Context, path: String, maxSeconds: Int): FloatArray {
        val extractor = MediaExtractor()
        val uri = Uri.parse(path)
        if (uri.scheme == "content") {
            extractor.setDataSource(context, uri, null)
        } else {
            extractor.setDataSource(path)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return FloatArray(0)
        }
        extractor.selectTrack(trackIndex)

        val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val maxSamples = TARGET_RATE * maxSeconds
        // 先按源采样率收集单声道，再一次性重采样
        val monoOut = ArrayList<Float>(minOf(maxSamples * 2, 4_000_000))
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var produced = 0 // 源采样点数量（单声道计）

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 采样率/声道以 format 为准，此处忽略 */ }
                MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                else -> if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val shorts = outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / 2 / channels
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until channels) {
                                acc += shorts.get(f * channels + c).toFloat() / 32768f
                            }
                            monoOut.add(acc / channels)
                        }
                        produced += frames
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    if (produced >= maxSamples * (srcRate.toFloat() / TARGET_RATE)) sawOutputEos = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        if (monoOut.isEmpty()) return FloatArray(0)
        return resample(monoOut.toFloatArray(), srcRate, TARGET_RATE)
    }

    /** 线性插值重采样 */
    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (input.size / ratio).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    // ─────────────────────────────────────────────
    // 2-4. 包络 / onset / 自相关
    // ─────────────────────────────────────────────

    private fun detectBpm(samples: FloatArray): Float {
        val frameCount = samples.size / HOP
        if (frameCount < 64) return 0f
        val env = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            val base = f * HOP
            for (i in 0 until HOP) {
                val v = samples[base + i]
                sum += v * v
            }
            env[f] = sqrt(sum / HOP)
        }

        // 起始点强度：能量包络的正向差分，去均值后归一化
        val onset = FloatArray(frameCount)
        var mean = 0f
        for (f in 1 until frameCount) {
            val d = env[f] - env[f - 1]
            onset[f] = if (d > 0f) d else 0f
            mean += onset[f]
        }
        mean /= frameCount
        var energy = 0f
        for (f in 0 until frameCount) {
            onset[f] -= mean
            energy += onset[f] * onset[f]
        }
        if (energy < 1e-6f) return 0f

        val frameDur = HOP.toDouble() / TARGET_RATE
        val minLag = max(2, (60.0 / MAX_BPM / frameDur).toInt())
        val maxLag = (60.0 / MIN_BPM / frameDur).toInt().coerceAtMost(frameCount / 2)
        if (maxLag <= minLag) return 0f

        // 归一化自相关
        var bestLag = -1
        var bestScore = -Double.MAX_VALUE
        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var acc = 0.0
            var n = 0
            var i = 0
            while (i + lag < frameCount) {
                acc += (onset[i] * onset[i + lag]).toDouble()
                n++
                i++
            }
            if (n == 0) continue
            var score = acc / n
            // 节奏先验：偏好 70–180 BPM，抑制极值
            val bpm = 60.0 / (lag * frameDur)
            val prior = when {
                bpm in PREFERRED_LOW..PREFERRED_HIGH -> 1.0
                bpm < PREFERRED_LOW -> 0.85 + 0.15 * (bpm - MIN_BPM) / (PREFERRED_LOW - MIN_BPM)
                else -> 0.85 - 0.25 * (bpm - PREFERRED_HIGH) / (MAX_BPM - PREFERRED_HIGH)
            }
            score *= prior
            scores[lag] = score
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return 0f

        // 抛物线插值细化峰位
        var refinedLag = bestLag.toDouble()
        if (bestLag > minLag && bestLag < maxLag) {
            val y0 = scores[bestLag - 1]
            val y1 = scores[bestLag]
            val y2 = scores[bestLag + 1]
            val denom = (y0 - 2 * y1 + y2)
            if (abs(denom) > 1e-12) {
                val delta = 0.5 * (y0 - y2) / denom
                if (abs(delta) < 1.0) refinedLag += delta
            }
        }

        var bpm = 60.0 / (refinedLag * frameDur)
        // 折叠倍频/半频到 70–180
        while (bpm > PREFERRED_HIGH && bpm / 2 >= PREFERRED_LOW) bpm /= 2
        while (bpm < PREFERRED_LOW && bpm * 2 <= PREFERRED_HIGH) bpm *= 2
        if (bpm < MIN_BPM || bpm > MAX_BPM) return 0f
        return bpm.toFloat()
    }
}
