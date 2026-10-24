package com.breezy.music.model

import android.content.Context
import android.os.Build
import android.net.Uri
import android.util.Log
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine

/**
 * 歌词外发（Super Lyric 适配层）
 *
 * 背景：状态栏歌词类应用（Super Lyric Getter）需要音乐 App 通过 Binder 把当前歌曲的
 * 完整歌词与时间轴广播出去。本类即 BreezyMusic 侧的「发布者」实现：
 *
 *  - 启动时 [SuperLyricHelper.registerPublisher] 注册为歌词发布方（否则发送会抛异常）；
 *  - 切歌时发送**全量包**：标题 / 歌手 / 专辑 / 时长 / 唯一 lyricId / 全部歌词行；
 *  - 播放过程中**不再推送进度**——外部按 [SuperLyricData.setDuration] 与首行时间自行推算，
 *    这是该协议刻意设计（避免高频 Binder 通信），也是我们适配时最容易做错的地方；
 *  - 停止播放时发送 stop 包，避免状态栏残留上一首歌词。
 *
 * 歌词来源：与音频同目录、同名的 `.lrc`（由 [LyricParser] 解析，支持多轨原文/翻译/罗马音）。
 */
object LyricPublisher {

    private const val TAG = "LyricPublisher"
    private const val PREF_KEY = "superlyric_enabled"

    /** 上一次已发布的歌曲路径，避免重复发送全量包 */
    private var lastPublishedPath: String? = null
    private var registered = false

    /** Super Lyric 需要 API 26+（库的 minSdk），低版本直接停用 */
    private val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** 当前设备是否安装了可用的歌词接收端 */
    fun isAvailable(): Boolean =
        supported && runCatching { SuperLyricHelper.isAvailable() }.getOrDefault(false)

    /** 应用启动时调用一次 */
    fun init(context: Context) {
        if (!supported || !isEnabled(context)) return
        registerIfNeeded()
    }

    fun isEnabled(context: Context): Boolean =
        supported && Preferences.isSuperLyricEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        Preferences.setSuperLyricEnabled(context, enabled)
        if (enabled) {
            registerIfNeeded()
        } else {
            unregister()
            lastPublishedPath = null
        }
    }

    private fun registerIfNeeded() {
        if (!supported || registered) return
        try {
            if (SuperLyricHelper.isAvailable()) {
                SuperLyricHelper.registerPublisher()
                registered = true
                Log.i(TAG, "SuperLyric publisher registered, api=${SuperLyricHelper.getApiVersion()}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "registerPublisher failed", e)
        }
    }

    private fun unregister() {
        if (!registered) return
        runCatching { SuperLyricHelper.unregisterPublisher() }
        registered = false
    }

    /**
     * 歌曲切换时调用：发布全量歌词包。
     * 内部做去重（同一首歌只发一次），可安全地在每次播放状态刷新时调用。
     */
    fun publishIfChanged(context: Context, song: SongEntity?) {
        if (!isEnabled(context)) return
        if (song == null) {
            stopIfNeeded()
            return
        }
        if (song.path == lastPublishedPath) return
        lastPublishedPath = song.path
        publish(context, song)
    }

    /** 立即发布指定歌曲（强制，忽略去重） */
    fun publish(context: Context, song: SongEntity) {
        if (!isEnabled(context)) return
        registerIfNeeded()
        if (!registered) return
        try {
            val lines = loadLyrics(context, song.path)
            val data = SuperLyricData()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbum(song.album)
                .setDuration(if (song.duration > 0) song.duration else 0L)
                // lyricId 必须唯一且稳定：用歌曲路径 + 时长做指纹，切歌后外部可据此判断歌词是否变化
                .setLyricId("breezy:${song.path.hashCode()}:${song.duration}")
                .setAllLyrics(lines)
                .setCurrentLyricIndex(if (lines.isEmpty()) -1 else 0)
            lines.firstOrNull()?.let { data.setLyric(it) }
            SuperLyricHelper.sendLyric(data)
            Log.i(TAG, "sendLyric: ${song.title}, ${lines.size} lines")
        } catch (e: Throwable) {
            Log.w(TAG, "sendLyric failed for ${song.path}", e)
        }
    }

    /** 停止播放时调用，清空外部歌词 */
    fun stopIfNeeded() {
        if (!registered) return
        lastPublishedPath = null
        try {
            SuperLyricHelper.sendStop(SuperLyricData())
        } catch (e: Throwable) {
            Log.w(TAG, "sendStop failed", e)
        }
    }

    // ═══════════════════════════════════════
    // 歌词读取与转换
    // ═══════════════════════════════════════

    /** 读取与音频同名的 .lrc 并转换为协议行对象 */
    private fun loadLyrics(context: Context, audioPath: String): Array<SuperLyricLine> {
        val lrcPath = lrcPathFor(audioPath) ?: return emptyArray()
        val parsed = runCatching { LyricParser.parse(context, lrcPath) }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return emptyArray()
        return toSuperLyricLines(parsed, durationMs = 0L)
    }

    /**
     * 把 [LyricParser] 的多轨解析结果压成协议要求的行数组。
     *
     * 同一时间戳的多条歌词按轨道合并：track0=原文，track1=翻译，track2=次级（罗马音），
     * 行结束时间取下一行的开始时间（最后一行若未知则用 begin+4s 兜底）。
     */
    fun toSuperLyricLines(
        lines: List<LyricParser.LyricLine>,
        durationMs: Long = 0L
    ): Array<SuperLyricLine> {
        if (lines.isEmpty()) return emptyArray()
        val grouped = LyricParser.groupByTime(lines)
        val result = ArrayList<SuperLyricLine>(grouped.size)
        grouped.forEachIndexed { index, (timeMs, group) ->
            val text = group.firstOrNull { it.track == 0 }?.text
                ?: group.first().text
            if (text.isBlank()) return@forEachIndexed
            val translation = group.firstOrNull { it.track == 1 }?.text
            val secondary = group.firstOrNull { it.track >= 2 }?.text
            val endTime = when {
                index + 1 < grouped.size -> grouped[index + 1].first
                durationMs > timeMs -> durationMs
                else -> timeMs + 4000L
            }
            result.add(
                SuperLyricLine(
                    text,
                    null,
                    translation,
                    secondary,
                    timeMs,
                    endTime
                )
            )
        }
        return result.toTypedArray()
    }

    /** 由音频 URI 推导同名 .lrc 的 URI */
    fun lrcPathFor(audioPath: String): String? {
        val dot = audioPath.lastIndexOf('.')
        val slash = audioPath.lastIndexOf('/')
        if (dot <= slash) return null
        return audioPath.substring(0, dot) + ".lrc"
    }

    /** 供设置页手动触发一次发布 */
    fun republish(context: Context, song: SongEntity?) {
        lastPublishedPath = null
        publishIfChanged(context, song)
    }

    @Suppress("unused")
    private fun previewUri(uri: String) = runCatching { Uri.parse(uri).toString() }.getOrDefault("")
}
