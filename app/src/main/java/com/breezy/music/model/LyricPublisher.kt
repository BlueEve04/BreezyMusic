package com.breezy.music.model

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.hchen.superlyricapi.Publisher
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric

/**
 * 歌词外发（Super Lyric 适配层，API 4.0）
 *
 * 协议（来自 SuperLyricApi 4.0 官方文档与源码）：
 *
 *  - **没有注册调用**。`Publisher` 的注释写得很直白：“首次 publish 即完成登记，无单独的注册调用。”
 *    所以这里不再有 3.x 的 `SuperLyricHelper.registerPublisher()`。
 *  - **一次 publish 一整首**，切歌时再调一次；播放过程中不推进度——进度由接收端结合
 *    本应用 MediaSession 的 position/speed 自行推算（我们每秒会更新一次 playback state）。
 *  - **切歌不需要清理旧数据**，系统检测到曲目变化会自行处理；`clear()` 只在需要立即清除时用
 *    （这里用于「关闭歌词外发」与「清空播放」两个显式动作）。
 *  - 数据校验在构造器里：`lyricId` 非空白、`duration >= 0`、`lines` 非空且按 start 升序；
 *    `title` 由 `publish()` 校验，**空白标题会抛 IllegalArgumentException**，所以要先用文件名兜底。
 *  - 单帧上限 512 KB（`Frame.MAX_SIZE`），超了返回 `PAYLOAD_TOO_LARGE`；真超了就降级重发。
 *
 * 歌词来源：与音频同目录、同名的 `.lrc`（[LyricParser] 解析，支持原文/翻译/罗马音三轨）。
 */
object LyricPublisher {

    private const val TAG = "LyricPublisher"

    /** 稳态下单行歌词的兜底时长：没有下一行也没有总时长时用它把行撑开 */
    private const val TRANSLATION_TOLERANCE_MS = 800L

    private const val FALLBACK_LINE_MS = 4000L

    /** 上一次已发布的歌曲路径，避免同一首歌重复发全量包 */
    private var lastPublishedPath: String? = null

    /** 上一次发布会话的 lyricId，用于日志与去重 */
    private var lastPublishedId: String? = null

    /** 最近一次发布的结果，供设置页展示（null 表示还没发过） */
    var lastResult: Publisher.PublishResult? = null
        private set

    /**
     * 接收端能力快照。
     *
     * 4.0 的库内部会先探 `super_lyric.v4`（二进制帧协议），探不到再回退
     * `super_lyric`（3.7 的 Parcelable 协议）。所以**客户端只需要发一次 publish**，
     * 走 v4 还是 legacy 由库自己决定；这里把结果读出来只为可观测。
     */
    data class Status(
        /** 系统版本是否满足库要求（API 26+） */
        val supported: Boolean,
        /** 是否探测到任意版本的接收端 */
        val available: Boolean,
        /** 对端 API 版本：40 = v4 帧协议，37 = 3.7 兼容模式，0 = 无 */
        val serverApi: Int,
        /** 给人看的模式描述 */
        val mode: String,
    )

    fun status(): Status {
        if (!supported) {
            return Status(supported = false, available = false, serverApi = 0, mode = "系统版本过低")
        }
        val api = serverApiVersion()
        val available = api > 0 || isAvailable()
        val mode = when {
            !available -> "未检测到接收端"
            api >= 40 -> "v4 帧协议（API $api）"
            api in 1..39 -> "旧版协议（API $api）"
            // legacy 通道没有版本号概念：getApiVersion 在 3.7 的服务里不存在，
            // 探到 Binder 但读不到版本，就是走 SuperLyricData 的兼容路径。
            else -> "3.x 兼容模式（legacy 通道）"
        }
        return Status(supported = true, available = available, serverApi = api, mode = mode)
    }

    /** Super Lyric 的库要求 API 26+，低版本直接停用 */
    private val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** 系统版本是否满足要求（设置页用来区分「系统太旧」与「没装接收端」） */
    fun isSupported(): Boolean = supported

    /** 当前设备是否装着可用的歌词接收端（Super Lyric） */
    fun isAvailable(): Boolean =
        supported && runCatching { SuperLyric.isAvailable() }.getOrDefault(false)

    /** 接收端实现的 API 版本；3.x 或不可用时为 0 */
    fun serverApiVersion(): Int =
        if (!supported) 0 else runCatching { SuperLyric.serverApiVersion() }.getOrDefault(0)

    /** 应用启动时调用一次：这里只做可用性探测与日志，真正的登记发生在第一次 publish */
    fun init(context: Context) {
        if (!supported || !isEnabled(context)) return
        val s = status()
        // 一次说清楚这条链当下能走哪条协议：v4 帧协议 / 3.7 兼容 / 没有接收端
        Log.i(TAG, "SuperLyric 状态：${s.mode}（available=${s.available}, serverApi=${s.serverApi}）")
    }

    fun isEnabled(context: Context): Boolean =
        supported && Preferences.isSuperLyricEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        Preferences.setSuperLyricEnabled(context, enabled)
        if (!enabled) {
            clear()
            lastPublishedPath = null
            lastPublishedId = null
        } else {
            // 重新打开时把当前歌曲补发一次，不等下一次切歌
            lastPublishedPath = null
        }
    }

    /**
     * 歌曲变化时调用：发布全量歌词包。
     * 内部按歌曲路径去重，可以安全地在每次播放状态刷新时调用。
     */
    fun publishIfChanged(context: Context, song: SongEntity?) {
        if (!isEnabled(context)) return
        if (song == null) {
            clear()
            return
        }
        if (song.path == lastPublishedPath) return
        publish(context, song)
    }

    /**
     * 立即发布指定歌曲。
     *
     * @return 协议返回的发布结果；前置条件不满足（未启用、无歌词、无接收端）时返回 null
     */
    fun publish(context: Context, song: SongEntity): Publisher.PublishResult? {
        if (!isEnabled(context)) return null
        if (!isAvailable()) return null

        val lyric = buildLyric(context, song) ?: run {
            Log.i(TAG, "「${song.title}」没有可用的 LRC，跳过外发")
            lastPublishedPath = song.path
            return null
        }

        return runCatching { SuperLyric.publisher().publish(lyric) }
            .onSuccess { result ->
                lastResult = result
                lastPublishedPath = song.path
                lastPublishedId = lyric.id
                when (result) {
                    Publisher.PublishResult.OK ->
                        Log.i(TAG, "已外发「${song.title}」${lyric.lineCount} 行 (id=${lyric.id})")

                    Publisher.PublishResult.PAYLOAD_TOO_LARGE -> {
                        // 超过 512 KB：去掉翻译/注音再发一次，仍失败就放弃这一首
                        Log.w(TAG, "歌词帧过大，降级为纯原文重发")
                        retryWithoutDecoration(context, song)
                    }

                    Publisher.PublishResult.SERVICE_UNAVAILABLE -> {
                        // 接收端没起来/刚被杀：清掉去重键，下一次播放状态刷新（约 1s 后）自动重试，
                        // 这样用户装好 Super Lyric 或它重启后不需要切歌也能自己接上。
                        lastPublishedPath = null
                        Log.i(TAG, "接收端不可用，将在下一次刷新时重试")
                    }

                    else -> {
                        // REJECTED_NOT_REGISTERED 等多见于对端重启后会话失效，
                        // 库内部已 reset 传输，这里同样放开去重键让下一轮重发。
                        lastPublishedPath = null
                        Log.w(TAG, "外发被拒绝：$result")
                    }
                }
            }
            .onFailure { Log.w(TAG, "publish 异常", it) }
            .getOrNull()
    }

    /** 停止播放 / 清空队列时调用，立即清掉外部歌词 */
    fun clear() {
        lastPublishedPath = null
        lastPublishedId = null
        if (!supported) return
        if (!isAvailable()) return
        runCatching { SuperLyric.publisher().clear() }
            .onFailure { Log.w(TAG, "clear 异常", it) }
    }

    // ═══════════════════════════════════════
    // 数据组装
    // ═══════════════════════════════════════

    /** 把一首歌 + 同目录 LRC 组装成 4.0 的 [Lyric] 文档；没有歌词时返回 null */
    fun buildLyric(context: Context, song: SongEntity): Lyric? {
        val lines = loadLines(context, song.path, decorate = true)
        if (lines.isEmpty()) return null
        return runCatching {
            Lyric(
                /* id = */ lyricId(song),
                /* title = */ displayTitle(song),
                /* artist = */ song.artist.orEmpty(),
                /* album = */ song.album.orEmpty(),
                /* duration = */ if (song.duration > 0L) song.duration else 0L,
                /* lines = */ lines,
            )
        }.onFailure { Log.w(TAG, "组装 Lyric 失败：${it.message}") }.getOrNull()
    }

    private fun retryWithoutDecoration(context: Context, song: SongEntity) {
        val plain = loadLines(context, song.path, decorate = false)
        if (plain.isEmpty()) return
        val lyric = runCatching {
            Lyric(lyricId(song), displayTitle(song), song.artist.orEmpty(), song.album.orEmpty(),
                if (song.duration > 0L) song.duration else 0L, plain)
        }.getOrNull() ?: return
        val result = runCatching { SuperLyric.publisher().publish(lyric) }.getOrNull()
        Log.i(TAG, "降级重发结果：$result（${plain.size} 行，无翻译/注音）")
    }

    /**
     * lyricId：接收端按它区分缓存，必须「同一首歌稳定、不同歌不同」。
     * 用绝对路径 + 时长做指纹；路径里可能含 SAF 的 content:// 前缀，先归一化。
     */
    fun lyricId(song: SongEntity): String {
        val key = runCatching { Uri.parse(song.path).toString() }.getOrDefault(song.path)
        return "breezy:${key.hashCode().toUInt().toString(16)}:${song.duration}"
    }

    /** 标题空白时用文件名兜底——publish() 会拒收空白标题 */
    private fun displayTitle(song: SongEntity): String {
        if (song.title.isNotBlank()) return song.title
        val name = song.path.substringAfterLast('/').substringBeforeLast('.')
        return name.ifBlank { "未知歌曲" }
    }

    /**
     * 读取并转换为协议行。同一时间戳的多轨歌词合并：
     * track0 → 正文，track1 → translation，track2+ → phonetic。
     *
     * @param decorate false 时丢弃翻译与注音，用于帧过大的降级重发
     */
    fun loadLines(context: Context, audioPath: String, decorate: Boolean): List<Line> {
        val lrcPath = lrcPathFor(audioPath) ?: return emptyList()
        val parsed = runCatching { LyricParser.parse(context, lrcPath) }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return emptyList()
        return toProtocolLines(parsed, durationMs = 0L, decorate = decorate)
    }

    /**
     * 把 [LyricParser] 的多轨结果压成协议行数组。
     *
     * 约束：`lines` 必须按 start 升序（构造器要求）；行结束时间取下一行开始时间，
     * 最后一行用总时长兜底，总时长未知则 `start + 4s`。
     */
    fun toProtocolLines(
        lines: List<LyricParser.LyricLine>,
        durationMs: Long = 0L,
        decorate: Boolean = true,
    ): List<Line> {
        if (lines.isEmpty()) return emptyList()

        // 骨架取第 0 轨（原文）。旧实现用「时间戳完全相等」分组，
        // 只要翻译行的时间差几毫秒，就归不到原文那一行上，翻译于是凭空消失。
        // 这里改成「按时间就近吸附」：非原文行挂到距离最近的原文行上，
        // 但只允许落在 ±TRANSLATION_TOLERANCE_MS 之内，避免把下一句的翻译串行。
        val sorted = lines.sortedWith(compareBy({ it.timeMs }, { it.track }))
        val anchors = sorted.filter { it.track == 0 }.ifEmpty { sorted.filter { it.track <= 0 } }
            .ifEmpty { sorted }
        val anchorTimes = LongArray(anchors.size) { anchors[it].timeMs }

        val translations = HashMap<Int, String>(anchors.size)
        val phonetics = HashMap<Int, String>(anchors.size)
        if (decorate) {
            for (line in sorted) {
                if (line.track <= 0 || line.text.isBlank()) continue
                val idx = nearestIndex(anchorTimes, line.timeMs)
                if (idx < 0) continue
                if (kotlin.math.abs(anchorTimes[idx] - line.timeMs) > TRANSLATION_TOLERANCE_MS) continue
                val bucket = if (line.track == 1) translations else phonetics
                bucket.putIfAbsent(idx, line.text)
            }
        }

        val out = ArrayList<Line>(anchors.size)
        anchors.forEachIndexed { index, anchor ->
            val text = anchor.text
            if (text.isBlank()) return@forEachIndexed

            val translation = translations[index]
            val phonetic = phonetics[index]

            val end = when {
                index + 1 < anchors.size -> anchors[index + 1].timeMs
                durationMs > anchor.timeMs -> durationMs
                else -> anchor.timeMs + FALLBACK_LINE_MS
            }.coerceAtLeast(anchor.timeMs)

            out.add(Line(text, anchor.timeMs, end, null, translation, phonetic))
        }
        return out
    }

    /** 二分找最接近的时间下标；`times` 必须升序 */
    private fun nearestIndex(times: LongArray, value: Long): Int {
        if (times.isEmpty()) return -1
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < value) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(times[lo - 1] - value) <= kotlin.math.abs(times[lo] - value)) {
            return lo - 1
        }
        return lo
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
}
