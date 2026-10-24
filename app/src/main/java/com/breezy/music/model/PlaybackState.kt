package com.breezy.music.model

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局播放状态（单例，同 ThemeState 模式）
 *
 * 后续会接入音频引擎，目前先维护 UI 状态。
 */
object PlaybackState {

    /** 当前播放的歌曲 */
    var currentSong by mutableStateOf<SongEntity?>(null)

    /** 是否正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 当前播放列表 */
    var playlist by mutableStateOf<List<SongEntity>>(emptyList())

    /** 播放列表当前索引 */
    var currentIndex by mutableIntStateOf(-1)

    /** 播放进度 0f..1f */
    var progress by mutableStateOf(0f)

    /** 播放模式：0=顺序 1=随机 2=单曲循环 */
    var playMode by mutableIntStateOf(0)

    /** 应用 Context（用于通知） */
    private var appContext: Context? = null

    /** 通知进度更新定时器 */
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var cachedAlbumArt: android.graphics.Bitmap? = null
    private var cachedAlbumArtPath: String? = null  // 跟踪封面对应的歌曲路径

    fun init(context: Context) {
        appContext = context.applicationContext
        MediaNotificationManager.init(context.applicationContext)
        // 歌词外发（Super Lyric）发布者注册
        LyricPublisher.init(context.applicationContext)

        // 恢复上次播放列表（使用有序 JSON，保留排序）
        val savedPaths = Preferences.getPlaylistOrdered(context)
        if (savedPaths.isNotEmpty()) {
            GlobalScope.launch(Dispatchers.IO) {
                val dao = MusicDatabase.getInstance(context).musicDao()
                val allSongs = dao.getAllSongs()
                // ★ 按 savedPaths 顺序重建列表，不能用 filter（会丢失顺序）
                val saved = savedPaths.mapNotNull { path -> allSongs.find { it.path == path } }
                Handler(Looper.getMainLooper()).post {
                    if (saved.isNotEmpty()) {
                        playlist = saved
                        val idx = Preferences.getCurrentSongIndex(context)
                            .coerceIn(0, saved.lastIndex)
                        currentIndex = idx
                        currentSong = saved[idx]
                    }
                }
            }
        }
    }

    /** 保存播放列表到持久存储 */
    fun savePlaylist() {
        val ctx = appContext ?: return
        // 使用 JSON 数组保存有序列表，避免 Set 丢失顺序
        val pathsJson = org.json.JSONArray(playlist.map { it.path }).toString()
        Preferences.setPlaylistOrdered(ctx, pathsJson)
        Preferences.setCurrentSongIndex(ctx, currentIndex)
        Preferences.setCurrentSongPath(ctx, currentSong?.path)
    }

    /** 更新媒体通知（供 AudioEngine 回调及外部使用） */
    fun notifyUpdatePublic() = notifyUpdate()

    /** 更新媒体通知 */
    private fun notifyUpdate() {
        try {
            val ctx = appContext ?: return
            val song = currentSong
            // 切歌时向外发布全量歌词包（内部按 path 去重，秒级刷新不会重复发送）
            LyricPublisher.publishIfChanged(ctx, song)
            if (song != null) {
                // 歌曲切换时清除旧封面缓存
                if (cachedAlbumArtPath != song.path) {
                    cachedAlbumArt = null
                    cachedAlbumArtPath = null
                }
                // 异步提取封面（仅首次缓存）
                if (cachedAlbumArt == null) {
                    try {
                        val uri = android.net.Uri.parse(song.path)
                        val mmr = android.media.MediaMetadataRetriever()
                        if (uri.scheme == "content") {
                            val pfd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
                            if (pfd != null) {
                                mmr.setDataSource(pfd.fileDescriptor, pfd.startOffset, pfd.length)
                                pfd.close()
                            }
                        } else {
                            mmr.setDataSource(ctx, uri)
                        }
                        val picture = mmr.embeddedPicture
                        mmr.release()
                        if (picture != null && picture.isNotEmpty()) {
                            cachedAlbumArt = android.graphics.BitmapFactory.decodeByteArray(picture, 0, picture.size)
                            cachedAlbumArtPath = song.path
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
                val pos = (progress * song.duration).toLong()
                MediaNotificationManager.showPlaying(ctx, song.title, song.artist, isPlaying, cachedAlbumArt, pos, song.duration)

                // 播放中定时更新进度条（每秒）
                progressUpdater?.let { progressHandler.removeCallbacks(it) }
                if (isPlaying) {
                    val updater = object : Runnable {
                        override fun run() {
                            notifyUpdate()
                        }
                    }
                    progressUpdater = updater
                    progressHandler.postDelayed(updater, 1000)
                }
            } else {
                MediaNotificationManager.cancel()
                cachedAlbumArt = null
                progressUpdater?.let { progressHandler.removeCallbacks(it) }
                progressUpdater = null
            }
        } catch (e: Exception) {
            android.util.Log.w("BreezyMusic", "notifyUpdate failed", e)
        }
    }

    // ── 操作 ──

    fun play(song: SongEntity) {
        val ctx = appContext ?: return
        // 添加到播放列表末尾（不清除已有列表）
        if (playlist.none { it.path == song.path }) {
            playlist = playlist + song
            currentIndex = playlist.lastIndex
        } else {
            // 已在列表中，切到该位置
            currentIndex = playlist.indexOfFirst { it.path == song.path }
        }
        currentSong = song
        progress = 0f
        AudioEngine.play(ctx, song.path)
        savePlaylist()
        notifyUpdate()
    }

    /** 仅重排播放列表（不中断当前播放，同曲保持进度） */
    fun reorderPlaylist(songs: List<SongEntity>, newIndex: Int) {
        if (songs.isEmpty()) return
        val wasPath = currentSong?.path
        playlist = songs
        currentIndex = newIndex.coerceIn(0, songs.lastIndex)
        val newSong = songs[currentIndex]
        // 同曲不重置
        if (newSong.path != wasPath) {
            currentSong = newSong
            progress = 0f
            appContext?.let { AudioEngine.play(it, newSong.path) }
        }
        savePlaylist()
        notifyUpdate()
    }

    fun playList(songs: List<SongEntity>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val ctx = appContext
        if (ctx != null && playlist.isNotEmpty()) {
            PlaylistHistory.save(ctx, playlist, currentIndex)
        }
        playlist = songs
        currentIndex = startIndex.coerceIn(0, songs.lastIndex)
        val song = songs[currentIndex]
        currentSong = song
        progress = 0f
        if (ctx != null) AudioEngine.play(ctx, song.path)
        savePlaylist()
        notifyUpdate()
    }

    /** 清空播放列表（先保存到历史记录，并停止播放） */
    fun clear() {
        val ctx = appContext
        if (ctx != null && playlist.isNotEmpty()) {
            PlaylistHistory.save(ctx, playlist, currentIndex)
        }
        AudioEngine.stop()
        LyricPublisher.stopIfNeeded()
        playlist = emptyList()
        currentIndex = -1
        currentSong = null
        progress = 0f
        isPlaying = false
        notifyUpdate()
    }

    /** 追加歌曲到列表尾部（不改变当前播放状态） */
    fun appendSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        playlist = playlist + songs
        if (currentIndex < 0) {
            currentIndex = 0
            currentSong = songs.first()
        }
    }

    fun togglePlayPause() {
        if (isPlaying) {
            AudioEngine.pause()
        } else if (AudioEngine.isStopped()) {
            val song = currentSong ?: return
            val ctx = appContext ?: return
            AudioEngine.play(ctx, song.path)
        } else {
            AudioEngine.resume()
        }
        notifyUpdate()
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        val song = playlist[currentIndex]
        currentSong = song
        progress = 0f
        val ctx = appContext ?: return
        AudioEngine.play(ctx, song.path)
        notifyUpdate()
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.lastIndex else currentIndex - 1
        val song = playlist[currentIndex]
        currentSong = song
        progress = 0f
        val ctx = appContext ?: return
        AudioEngine.play(ctx, song.path)
        notifyUpdate()
    }

    // ── Seek ──

    fun seekTo(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        AudioEngine.seekTo(fraction)
    }
}
