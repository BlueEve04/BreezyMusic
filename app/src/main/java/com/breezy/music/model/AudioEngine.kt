package com.breezy.music.model

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.breezy.music.audio.BreezyDspProcessor
import java.io.File

/**
 * 音频引擎（基于 Media3 ExoPlayer + 自研 DSP 音频处理链）
 *
 * 选用 ExoPlayer 的原因（相比 MediaPlayer）：
 *  1. 提供 [androidx.media3.common.audio.AudioProcessor] 扩展点，可在解码后的 PCM 阶段
 *     插入自研均衡器/混响，做到「所有音源统一音效」，而不依赖设备厂商的系统音效实现；
 *  2. 支持无间隙播放、更精确的 seek 与缓冲控制，切歌时底噪与卡顿明显少于 MediaPlayer；
 *  3. 与系统 MediaSession 的交互更规范，便于后续接入 superlyric 的播放状态广播。
 *
 * 音效处理链在 [BreezyDspProcessor] 中实现，[dsp] 暴露给 UI 层调节。
 */
object AudioEngine {

    private const val TAG = "AudioEngine"
    private const val PROGRESS_INTERVAL_MS = 250L

    private var player: ExoPlayer? = null
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var currentUri: String? = null

    /** 全局唯一 DSP 处理器：ExoPlayer 内部按需创建 AudioSink，但处理器实例必须复用才能保留参数 */
    val dsp: BreezyDspProcessor = BreezyDspProcessor()

    // ── 当前音轨的技术参数（供音效底栏展示）──
    var audioSampleRate by mutableStateOf(0)
        private set
    var audioBitrate by mutableStateOf(0)
        private set
    var audioChannelCount by mutableStateOf(0)
        private set
    var audioCodec by mutableStateOf("")
        private set

    /**
     * 创建 ExoPlayer：通过重写 [DefaultRenderersFactory.buildAudioSink]
     * 把 [dsp] 注入音频渲染链。
     */
    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // 16bit 整数输出即可：DSP 内部以 float 运算，输出前再量化回 16bit
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(dsp))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val exo = ExoPlayer.Builder(appContext, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackState.isPlaying = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                PlaybackState.notifyUpdatePublic()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    PlaybackState.isPlaying = false
                    stopProgressUpdates()
                    PlaybackState.progress = 1f
                    PlaybackState.notifyUpdatePublic()
                    PlaybackState.playNext()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 取第一条音频轨的 Format：采样率、码率、声道数与编码名。
                // 注意这只是「补充」：本地 WAV/FLAC 的 Format 里常常没有 bitrate，
                // 光靠它会出现「换歌了但码率一直不变」。真正的兜底是 refreshAudioInfo()。
                //
                // 另外两条血泪规则：
                // 1) 只填空缺。切歌时 Format 可能晚于 refreshAudioInfo 到达，
                //    无条件覆盖会把已经读对的值冲掉。
                // 2) 忽略 audio/raw。解码器链路上报的是 PCM，不是容器里的真实编码，
                //    之前就是它把编码名改成 RAW，只有第一首（Format 还没换）看着正常。
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.let { group ->
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        val format = group.getTrackFormat(i)
                        if (audioSampleRate <= 0 && format.sampleRate > 0) audioSampleRate = format.sampleRate
                        if (audioBitrate <= 0 && format.bitrate > 0) audioBitrate = format.bitrate
                        if (audioChannelCount <= 0 && format.channelCount > 0) audioChannelCount = format.channelCount
                        val mime = format.sampleMimeType?.substringAfterLast("/")?.uppercase()
                        if (audioCodec.isEmpty() && !mime.isNullOrEmpty() && mime != "RAW") {
                            audioCodec = mime
                        }
                        break
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} ${error.message}", error)
                PlaybackState.isPlaying = false
                stopProgressUpdates()
                PlaybackState.notifyUpdatePublic()
            }
        })

        player = exo
        return exo
    }

    /**
     * 用 MediaExtractor 直接把当前音源的真实参数读出来。
     *
     * 为什么不能只依赖 ExoPlayer 的 Format：本地文件（尤其 WAV、部分 FLAC）的 Format 里
     * bitrate 常常是未设置，切换歌曲时如果只在「大于零」时才写状态，界面就会一直显示上一首的数字。
     * 所以这里先按文件读一遍，读不到再退回「文件大小 × 8 ÷ 时长」估算。
     */
    fun refreshAudioInfo(context: Context, uriString: String) {
        audioSampleRate = 0
        audioBitrate = 0
        audioChannelCount = 0
        audioCodec = ""
        val uri = Uri.parse(uriString)
        var durationMs = 0L
        try {
            val extractor = MediaExtractor()
            try {
                if (uri.scheme == "content") {
                    extractor.setDataSource(context, uri, null)
                } else {
                    val path = uri.path ?: return
                    extractor.setDataSource(path)
                }
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        audioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        audioChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    }
                    audioCodec = prettyCodec(mime, uriString)
                    break
                }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshAudioInfo: extractor failed for $uriString", e)
        }

        if (audioBitrate <= 0) {
            // 估算：文件字节数 × 8 ÷ 秒数
            val bytes = runCatching {
                if (uri.scheme == "content") {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                } else {
                    File(uri.path ?: return).length()
                }
            }.getOrNull() ?: 0L
            val seconds = when {
                durationMs > 0 -> durationMs / 1000.0
                else -> runCatching {
                    MediaMetadataRetriever().use { r ->
                        if (uri.scheme == "content") r.setDataSource(context, uri)
                        else r.setDataSource(uri.path)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.div(1000.0)
                    }
                }.getOrNull() ?: 0.0
            }
            if (bytes > 0 && seconds > 0) {
                audioBitrate = (bytes * 8 / seconds).toInt()
            }
        }
    }

    /**
     * 把 MIME 换成界面上好看的短名。
     *
     * 这里优先看扩展名，原因有两个：
     * 1) 系统 extractor 对某些容器（尤其 MIUI 上的 FLAC）会回 audio/raw，照 mime 显示就变成 "PCM"；
     * 2) 同一首歌在「暂停恢复」「重新扫描」两条路径上拿到的 mime 不一致，界面会来回跳。
     * 扩展名来自用户自己的文件，稳定得多；只有识别不出来时才退回 mime。
     */
    private fun prettyCodec(mime: String, uriString: String): String {
        val ext = runCatching {
            Uri.parse(uriString).lastPathSegment?.substringAfterLast('.', "")?.uppercase()
        }.getOrNull().orEmpty()
        val fromExt = when (ext) {
            "FLAC" -> "FLAC"
            "MP3" -> "MP3"
            "M4A", "AAC", "MP4" -> "AAC"
            "WAV", "WAVE" -> "WAV"
            "OGG", "OGA" -> "OGG"
            "OPUS" -> "OPUS"
            "APE" -> "APE"
            "DSF", "DFF" -> "DSD"
            "AIFF", "AIF" -> "AIFF"
            "WMA" -> "WMA"
            else -> ""
        }
        if (fromExt.isNotEmpty()) return fromExt
        val short = mime.substringAfterLast("/").uppercase()
        return if (short == "RAW") "PCM" else short
    }

    /** 开始播放指定 URI（content:// 或 file://） */
    fun play(context: Context, uriString: String) {
        val exo = ensurePlayer(context)
        refreshAudioInfo(context, uriString)

        if (currentUri == uriString) {
            // 同一首：暂停态则恢复，播放完毕后重播
            if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0)
            }
            exo.play()
            return
        }

        try {
            exo.setMediaItem(MediaItem.fromUri(uriString))
            exo.prepare()
            exo.playWhenReady = true
            currentUri = uriString
            PlaybackState.isPlaying = true
            PlaybackState.progress = 0f
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "play failed: $uriString", e)
            PlaybackState.isPlaying = false
        }
    }

    /** 暂停 */
    fun pause() {
        player?.pause()
        PlaybackState.isPlaying = false
        stopProgressUpdates()
    }

    /** 恢复 */
    fun resume() {
        player?.play()
        PlaybackState.isPlaying = true
        startProgressUpdates()
    }

    /** 停止并释放播放器（同时清空 DSP 延迟线，避免残留尾音） */
    fun stop() {
        stopProgressUpdates()
        player?.release()
        player = null
        currentUri = null
        PlaybackState.isPlaying = false
    }

    /** 是否已释放（无活跃 player） */
    fun isStopped(): Boolean = player == null

    /** Seek 到指定位置（0f..1f） */
    fun seekTo(fraction: Float) {
        val exo = player ?: return
        val duration = exo.duration
        if (duration > 0 && duration != C.TIME_UNSET) {
            val target = (duration * fraction).toLong().coerceIn(0L, duration)
            exo.seekTo(target)
            PlaybackState.progress = fraction.coerceIn(0f, 1f)
        }
    }

    /** 当前播放位置（毫秒），用于运动音乐等场景 */
    fun currentPositionMs(): Long = player?.currentPosition ?: 0L

    /** 当前曲目总时长（毫秒） */
    fun durationMs(): Long {
        val d = player?.duration ?: 0L
        return if (d == C.TIME_UNSET) 0L else d
    }

    // ── 进度更新 ──

    private fun startProgressUpdates() {
        val handler = progressHandler ?: Handler(Looper.getMainLooper()).also { progressHandler = it }
        stopProgressUpdates()
        val runnable = object : Runnable {
            override fun run() {
                val exo = player
                if (exo != null && exo.isPlaying) {
                    val duration = exo.duration
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        PlaybackState.progress =
                            (exo.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                    }
                    handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
    }
}
