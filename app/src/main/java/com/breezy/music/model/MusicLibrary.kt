package com.breezy.music.model

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "BreezyMusic"

/**
 * 音乐仓库 —— Room 持久化 + 多线程批量解析
 *
 * 设计：
 * - 全部数据存储在 Room SQLite 数据库中（支持 5w+ 歌曲）
 * - DAO 按需查询，不常驻内存
 * - 多线程批量解析（线程池大小 = CPU 核心数）
 * - 批量事务写入，避免逐条 insert 开销
 */
object MusicRepository {

    private val parseDispatcher = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    ).asCoroutineDispatcher()

    /**
     * 扫描文件夹并解析所有音乐元数据（多线程 + 持久化）
     *
     * @param onProgress 进度回调 (current, total)
     * @return 新增歌曲数量
     */
    suspend fun scanFolder(
        context: Context,
        folderUri: Uri,
        folderPath: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int {
        val dao = MusicDatabase.getInstance(context).musicDao()

        Log.i(TAG, "scanFolder: path=$folderPath, uri=$folderUri")

        // ① 收集所有音乐文件 + M3U8 文件
        val musicUris = mutableListOf<Uri>()
        val m3u8Uris = mutableListOf<Uri>()
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
        if (rootDoc != null) {
            collectMusicUris(context, rootDoc, musicUris, m3u8Uris) { count ->
                onProgress(count, -1)
            }
            Log.i(TAG, "scanFolder: SAF found ${musicUris.size} music + ${m3u8Uris.size} M3U8")
        } else {
            Log.w(TAG, "scanFolder: DocumentFile null, trying File API")
            val rootDir = File(folderPath)
            if (rootDir.exists() && rootDir.canRead()) {
                val files = mutableListOf<File>()
                collectMusicFiles(rootDir, files)
                files.forEach { musicUris.add(Uri.fromFile(it)) }
                Log.i(TAG, "scanFolder: File API fallback found ${files.size} files")
            }
        }

        // 将发现的 M3U8 缓存到 SharedPreferences（同时清除旧解析缓存）
        if (m3u8Uris.isNotEmpty()) {
            Preferences.clearPlaylistCache(context)
            val existing = Preferences.getM3u8Uris(context).toMutableSet()
            m3u8Uris.forEach { existing.add(it.toString()) }
            Preferences.setM3u8Uris(context, existing)
            Log.i(TAG, "scanFolder: cached ${existing.size} total M3U8 URIs")
        }

        val total = musicUris.size
        if (total == 0) {
            Log.w(TAG, "scanFolder: found 0 music files, aborting")
            return 0
        }

        // ② 多线程并行解析元数据（SAF URI → SongEntity）
        val songs = withContext(parseDispatcher) {
            val results = mutableListOf<SongEntity>()

            musicUris.chunked(50).flatMap { batch ->
                batch.map { uri ->
                    async(parseDispatcher) {
                        parseMetadataFromUri(context, uri, folderUri.toString())
                    }
                }.awaitAll().filterNotNull().also { parsed ->
                    synchronized(results) { results.addAll(parsed) }
                    onProgress(results.size, total)
                }
            }
        }

        Log.i(TAG, "scanFolder: parsed ${songs.size} songs, writing to DB")

        // ③ 事务写入 Room（保留已分析过的 BPM，避免重扫后运动音乐需重新分析）
        val cachedBpm = runCatching { dao.getBpmForFolder(folderUri.toString()) }
            .getOrDefault(emptyList())
            .associate { it.path to it.bpm }
        val songsWithBpm = songs.toList().map { song ->
            val cached = cachedBpm[song.path]
            if (cached != null && cached != 0f) song.copy(bpm = cached) else song
        }
        dao.replaceFolderSongs(folderUri.toString(), songsWithBpm)
        dao.insertFolder(FolderEntity(path = folderUri.toString()))

        // ④ 预解析 M3U8 歌单（在导入时就完成匹配，避免后续打开歌单页时等待）
        if (m3u8Uris.isNotEmpty()) {
            Log.i(TAG, "scanFolder: pre-parsing ${m3u8Uris.size} M3U8 files...")
            val allSongs = songs.toList()
            val songMap = mutableMapOf<String, SongEntity>()
            for (s in allSongs) songMap[s.path] = s
            // 也加入之前已导入的歌曲
            val existing = dao.getAllSongs()
            for (s in existing) if (s.path !in songMap) songMap[s.path] = s

            for (m3u8Uri in m3u8Uris) {
                try {
                    val pl = com.breezy.music.util.M3u8Parser.parse(context, m3u8Uri, allSongs, songMap)
                    if (pl.songs.isNotEmpty()) {
                        Preferences.setPlaylistSongsCache(context, m3u8Uri.toString(),
                            pl.songs.map { it.path })
                        Log.d(TAG, "  M3U8 pre-parsed: ${pl.name} → ${pl.songs.size} songs")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  M3U8 parse failed: $m3u8Uri", e)
                }
            }
        }

        return songs.size
    }

    /** 移除指定文件夹的所有歌曲 */
    suspend fun removeFolder(context: Context, folderPath: String) {
        val dao = MusicDatabase.getInstance(context).musicDao()
        dao.deleteByFolder(folderPath)
        dao.deleteFolder(FolderEntity(path = folderPath))
    }

    /** 获取所有已管理文件夹 */
    suspend fun getFolders(context: Context): List<String> {
        return MusicDatabase.getInstance(context).musicDao()
            .getAllFolders().map { it.path }
    }

    /** 重新扫描所有文件夹 */
    suspend fun rescanAll(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): Int {
        val dao = MusicDatabase.getInstance(context).musicDao()
        val folders = dao.getAllFolders()
        var totalSongs = 0

        folders.forEachIndexed { idx, folder ->
            onProgress("重新解析 ${idx + 1}/${folders.size} 个文件夹...")
            val folderUri = Uri.parse(folder.path) // 现在存的是 SAF URI
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
            if (rootDoc == null) {
                Log.w(TAG, "rescanAll: cannot resolve $folderUri")
                return@forEachIndexed
            }

            val musicUris = mutableListOf<Uri>()
            collectMusicUris(context, rootDoc, musicUris)

            val songs = withContext(parseDispatcher) {
                musicUris.map { uri ->
                    async(parseDispatcher) { parseMetadataFromUri(context, uri, folder.path) }
                }.awaitAll().filterNotNull()
            }

            val bpmCache = runCatching { dao.getBpmForFolder(folder.path) }.getOrDefault(emptyList())
                .associate { it.path to it.bpm }
            val withBpm = songs.map { song ->
                val cached = bpmCache[song.path]
                if (cached != null && cached != 0f) song.copy(bpm = cached) else song
            }
            dao.replaceFolderSongs(folder.path, withBpm)
            totalSongs += songs.size
        }

        return totalSongs
    }

    // ═══════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════

    private val musicExtensions = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma")
    private val playlistExtensions = setOf("m3u8", "m3u")

    /** 检查文件名是否为支持的音乐格式 */
    fun isMusicFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in musicExtensions
    }

    // ── 传统 File API 遍历（Android ≤10 回退） ──

    private fun collectMusicFiles(dir: File, result: MutableList<File>) {
        val files = try { dir.listFiles() } catch (e: Exception) {
            Log.w(TAG, "collectMusicFiles: error ${dir.absolutePath}", e); null
        }
        if (files == null) return
        Log.d(TAG, "collectMusicFiles: ${dir.absolutePath} → ${files.size} children")
        files.forEach { file ->
            if (file.isDirectory) collectMusicFiles(file, result)
            else if (file.extension.lowercase() in musicExtensions) result.add(file)
        }
    }

    // ── SAF DocumentFile 遍历（Android 11+ 主路径） ──

    private fun collectMusicUris(
        context: Context,
        doc: DocumentFile,
        result: MutableList<Uri>,
        m3u8Result: MutableList<Uri> = mutableListOf(),
        onFound: ((Int) -> Unit)? = null
    ) {
        val children = try { doc.listFiles() } catch (e: Exception) {
            Log.w(TAG, "collectMusicUris: error listing ${doc.uri}", e); emptyArray()
        }
        children.forEach { child ->
            if (child.isDirectory) {
                collectMusicUris(context, child, result, m3u8Result, onFound)
            } else {
                val name = child.name ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (name.isNotEmpty() && ext in musicExtensions) {
                    result.add(child.uri)
                    if (onFound != null && result.size % 10 == 0) {
                        onFound(result.size)
                    }
                } else if (ext in playlistExtensions) {
                    m3u8Result.add(child.uri)
                }
            }
        }
        onFound?.invoke(result.size)
    }

    // ── 元数据解析（SAF URI 版本） ──

    private fun parseMetadataFromUri(context: Context, uri: Uri, folderUriStr: String): SongEntity? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)

            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.ifBlank {
                    // 元数据无标题 → 从 SAF DocumentFile 获取真实文件名
                    try {
                        DocumentFile.fromSingleUri(context, uri)?.name
                            ?.substringBeforeLast('.') ?: "未知歌曲"
                    } catch (e: Exception) {
                        "未知歌曲"
                    }
                } ?: "未知歌曲"

            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim() ?: "未知艺术家"

            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim() ?: "未知专辑"

            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            mmr.release()

            SongEntity(
                path = uri.toString(),        // 存 SAF content URI
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                folderPath = folderUriStr     // 存 SAF tree URI
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseMetadataFromUri: failed for $uri", e)
            null
        }
    }
}

// 全局 DAO 快捷访问
suspend fun musicDao(context: Context) = MusicDatabase.getInstance(context).musicDao()

