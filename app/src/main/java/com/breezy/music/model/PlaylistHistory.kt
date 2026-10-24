package com.breezy.music.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放列表历史记录（持久化到 SharedPreferences）
 *
 * 每次清空/替换播放列表时自动保存当前列表到历史。
 * 最多保留 20 条历史记录。
 */
object PlaylistHistory {

    private const val PREFS_NAME = "breezy_playlist_history"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_ENTRIES = 20

    data class Entry(
        val timestamp: Long,
        val songs: List<HistorySong>,
        val currentIndex: Int = 0
    )

    data class HistorySong(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long
    )

    /** 保存当前播放列表到历史记录 */
    fun save(context: Context, songs: List<SongEntity>, currentIndex: Int = -1) {
        if (songs.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null)
        val arr = if (json != null) try { JSONArray(json) } catch (e: Exception) { JSONArray() } else JSONArray()

        // 构造新条目
        val entry = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("currentIndex", currentIndex)
            put("songs", JSONArray().apply {
                songs.take(50).forEach { s ->  // 最多存 50 首
                    put(JSONObject().apply {
                        put("path", s.path)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album)
                        put("duration", s.duration)
                    })
                }
            })
        }
        // 插入最前
        val newArr = JSONArray().apply { put(entry) }
        for (i in 0 until arr.length()) {
            newArr.put(arr.getJSONObject(i))
            if (newArr.length() >= MAX_ENTRIES) break
        }
        prefs.edit().putString(KEY_HISTORY, newArr.toString()).apply()
    }

    /** 读取所有历史记录 */
    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val songsArr = obj.getJSONArray("songs")
                val songs = (0 until songsArr.length()).map { j ->
                    val s = songsArr.getJSONObject(j)
                    HistorySong(
                        path = s.getString("path"),
                        title = s.getString("title"),
                        artist = s.getString("artist"),
                        album = s.getString("album"),
                        duration = s.getLong("duration")
                    )
                }
                Entry(
                    timestamp = obj.getLong("timestamp"),
                    currentIndex = obj.optInt("currentIndex", 0),
                    songs = songs
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 删除指定历史记录 */
    fun delete(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val arr = JSONArray(json)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getLong("timestamp") != timestamp) {
                    newArr.put(obj)
                }
            }
            prefs.edit().putString(KEY_HISTORY, newArr.toString()).apply()
        } catch (e: Exception) { }
    }

    /** 清空所有历史 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}
