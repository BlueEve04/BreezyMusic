package com.breezy.music.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 曲库 BPM 批量分析调度器（全局单例）
 *
 * - 通过有限并发（默认 2，按 CPU 核数 2–4）解码分析，避免多路硬件解码器抢占造成卡顿；
 * - 每分析完一首立即写回 Room（`songs.bpm`），因此**支持断点续跑**：中途退出/被杀，
 *   下次只分析 `bpm = 0` 的歌曲；
 * - 通过 Compose 状态暴露进度，UI 直接订阅即可显示进度条与当前处理歌曲。
 */
object BpmRepository {

    /** 是否正在分析 */
    var isAnalyzing by mutableStateOf(false)
        private set

    /** 本批次总数 */
    var total by mutableIntStateOf(0)
        private set

    /** 本批次已完成数 */
    var done by mutableIntStateOf(0)
        private set

    /** 当前正在分析的歌曲标题 */
    var currentTitle by mutableStateOf("")
        private set

    /** 最近一次运行错误信息（null 表示无错误） */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** 曲库统计：已分析 / 总数 */
    var analyzedCount by mutableIntStateOf(0)
        private set
    var libraryCount by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 刷新统计（进入页面时调用） */
    fun refreshStats(context: Context) {
        scope.launch {
            val dao = MusicDatabase.getInstance(context).musicDao()
            val analyzed = dao.getAnalyzedCount()
            val all = dao.getSongCount()
            withContext(Dispatchers.Main) {
                analyzedCount = analyzed
                libraryCount = all
            }
        }
    }

    /**
     * 分析整库中尚未分析 BPM 的歌曲
     * @param onlySongs 可选：只分析指定的歌曲集合（用于「从文件夹/歌单取源」场景）
     */
    fun analyzeAll(context: Context, onlySongs: List<SongEntity>? = null, force: Boolean = false): Job? {
        if (isAnalyzing) return null
        val appContext = context.applicationContext
        job = scope.launch {
            val dao = MusicDatabase.getInstance(appContext).musicDao()
            val targets = (onlySongs ?: dao.getAllSongs())
                .filter { force || it.bpm == 0f }
                .sortedBy { it.path }
            if (targets.isEmpty()) {
                refreshStats(appContext)
                return@launch
            }
            withContext(Dispatchers.Main) {
                isAnalyzing = true
                total = targets.size
                done = 0
                currentTitle = ""
                lastError = null
            }
            val counter = AtomicInteger(0)
            val concurrency = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val channel = Channel<SongEntity>(capacity = concurrency)
            val producer = launch {
                targets.forEach { channel.send(it) }
                channel.close()
            }
            repeat(concurrency) {
                launch {
                    for (song in channel) {
                        val bpm = BpmAnalyzer.analyze(appContext, song.path)
                        if (bpm > 0f) {
                            runCatching { dao.updateBpm(song.path, bpm) }
                        } else {
                            // 标记为“已尝试”，用 -1 记录避免每次重跑；UI 显示为未识别
                            runCatching { dao.updateBpm(song.path, -1f) }
                        }
                        val n = counter.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            done = n
                            currentTitle = song.title
                        }
                    }
                }
            }
            producer.join()
            withContext(Dispatchers.Main) {
                isAnalyzing = false
                currentTitle = ""
            }
            refreshStats(appContext)
        }
        return job
    }

    fun cancel() {
        job?.cancel()
        job = null
        isAnalyzing = false
    }

    /** 重置所有 BPM（用于重新分析） */
    fun resetAll(context: Context) {
        scope.launch {
            MusicDatabase.getInstance(context).musicDao().clearAllBpm()
            refreshStats(context)
        }
    }
}
