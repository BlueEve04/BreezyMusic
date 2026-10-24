package com.breezy.music.model

import android.content.Context

/**
 * SharedPreferences 持久化工具
 */
object Preferences {
    private const val NAME = "breezy_music_prefs"

    fun init(context: Context) {} // no-op, init on first access

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ── 主题模式 ──
    fun getThemeMode(context: Context): String =
        prefs(context).getString("theme_mode", "FollowSystem") ?: "FollowSystem"

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString("theme_mode", mode).apply()
    }

    // ── 主题色（兼容旧版 Long → Int 迁移）──
    fun getAccentColor(context: Context): Int {
        val p = prefs(context)
        return try {
            p.getInt("accent_color", 0xFF4F46E5.toInt())
        } catch (e: ClassCastException) {
            // 旧版存储为 Long，迁移到 Int
            val old = p.getLong("accent_color", 0xFF4F46E5)
            val argb = old.toInt()
            p.edit().putInt("accent_color", argb).apply()
            argb
        }
    }

    fun setAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt("accent_color", color).apply()
    }

    // ── 文件夹 ──
    fun getFolderPaths(context: Context): Set<String> =
        prefs(context).getStringSet("folder_paths", emptySet()) ?: emptySet()

    fun setFolderPaths(context: Context, paths: Set<String>) {
        prefs(context).edit().putStringSet("folder_paths", paths).apply()
    }

    // ── 播放列表持久化 ──
    fun getPlaylist(context: Context): Set<String> =
        prefs(context).getStringSet("playlist", emptySet()) ?: emptySet()

    fun setPlaylist(context: Context, paths: Set<String>) {
        prefs(context).edit().putStringSet("playlist", paths).apply()
    }

    // ── 有序播放列表（JSON 数组，保留顺序） ──
    fun getPlaylistOrdered(context: Context): List<String> {
        val json = prefs(context).getString("playlist_ordered", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun setPlaylistOrdered(context: Context, json: String) {
        prefs(context).edit().putString("playlist_ordered", json).apply()
    }

    fun getCurrentSongIndex(context: Context): Int =
        prefs(context).getInt("playlist_index", -1)

    fun setCurrentSongIndex(context: Context, index: Int) {
        prefs(context).edit().putInt("playlist_index", index).apply()
    }

    fun getCurrentSongPath(context: Context): String? =
        prefs(context).getString("current_song_path", null)

    fun setCurrentSongPath(context: Context, path: String?) {
        prefs(context).edit().putString("current_song_path", path ?: "").apply()
    }

    // ── 封面裁切形状 ──
    fun getCoverShape(context: Context): String =
        prefs(context).getString("cover_shape", "RoundedRect") ?: "RoundedRect"

    fun setCoverShape(context: Context, shape: String) {
        prefs(context).edit().putString("cover_shape", shape).apply()
    }

    // ── 通知权限不再提醒 ──
    fun getNeverAskNotification(context: Context): Boolean =
        prefs(context).getBoolean("never_ask_notification", false)

    fun setNeverAskNotification(context: Context, never: Boolean) {
        prefs(context).edit().putBoolean("never_ask_notification", never).apply()
    }

    // ── 文件夹播放策略 ──
    // "single" = 点击单曲仅加入该曲；"folder" = 点击单曲加入同目录所有歌曲
    fun getFolderPlayStrategy(context: Context): String =
        prefs(context).getString("folder_play_strategy", "single") ?: "single"

    fun setFolderPlayStrategy(context: Context, strategy: String) {
        prefs(context).edit().putString("folder_play_strategy", strategy).apply()
    }

    // 是否替换播放列表（仅在 strategy = "folder" 时生效）
    fun getFolderPlayReplace(context: Context): Boolean =
        prefs(context).getBoolean("folder_play_replace", false)

    fun setFolderPlayReplace(context: Context, replace: Boolean) {
        prefs(context).edit().putBoolean("folder_play_replace", replace).apply()
    }

    // ── 封面缓存大小（MB，-1 = 无限制） ──
    fun getCoverCacheSizeMB(context: Context): Int =
        prefs(context).getInt("cover_cache_size_mb", -1)

    fun setCoverCacheSizeMB(context: Context, sizeMB: Int) {
        prefs(context).edit().putInt("cover_cache_size_mb", sizeMB).apply()
    }

    // ── 返回键行为 ──
    // "smart" = 智能返回（多选→收起→返回上级）；"simple" = 一律退到后台
    fun getBackBehavior(context: Context): String =
        prefs(context).getString("back_behavior", "smart") ?: "smart"

    /**
     * 歌词页手动滑动后，多久自动回正（秒）。
     * 3~30 秒，默认 5 秒：太短来不及看，太长又失去"跟着唱"的跟随感。
     */
    fun getLyricsResumeSeconds(context: Context): Int =
        prefs(context).getInt("lyrics_resume_seconds", 5).coerceIn(3, 30)

    fun setLyricsResumeSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt("lyrics_resume_seconds", seconds.coerceIn(3, 30)).apply()
    }

    fun setBackBehavior(context: Context, mode: String) {
        prefs(context).edit().putString("back_behavior", mode).apply()
    }

    // ── 网格大小：small/medium/large/xlarge（专辑/歌手各自独立）──
    fun getAlbumGridSize(context: Context): String =
        prefs(context).getString("album_grid_size", "medium") ?: "medium"

    fun setAlbumGridSize(context: Context, size: String) {
        prefs(context).edit().putString("album_grid_size", size).apply()
    }

    fun getArtistGridSize(context: Context): String =
        prefs(context).getString("artist_grid_size", "medium") ?: "medium"

    fun setArtistGridSize(context: Context, size: String) {
        prefs(context).edit().putString("artist_grid_size", size).apply()
    }

    // ── 兼容旧的共享 key ──
    fun getGridSize(context: Context): String =
        prefs(context).getString("grid_size", "medium") ?: "medium"

    fun setGridSize(context: Context, size: String) {
        prefs(context).edit().putString("grid_size", size).apply()
    }

    // ── 歌手自定义封面路径 ──
    fun getArtistCover(context: Context, artistName: String): String? =
        prefs(context).getString("artist_cover_$artistName", null)

    fun setArtistCover(context: Context, artistName: String, path: String?) {
        prefs(context).edit().putString("artist_cover_$artistName", path ?: "").apply()
    }

    // ── M3U8 歌单 URI 缓存（扫描时发现，歌单页直接使用）──
    fun getM3u8Uris(context: Context): Set<String> =
        prefs(context).getStringSet("m3u8_uris", emptySet()) ?: emptySet()

    fun setM3u8Uris(context: Context, uris: Set<String>) {
        prefs(context).edit().putStringSet("m3u8_uris", uris).apply()
    }

    // ── M3U8 歌单解析结果缓存（有序 JSON，避免 Set 丢失顺序；兼容旧版 Set 格式）──
    fun getPlaylistSongsCache(context: Context, m3u8Uri: String): List<String> {
        val key = "pl_cache_${m3u8Uri.hashCode()}"
        val p = prefs(context)
        return try {
            val json = p.getString(key, null) ?: return emptyList()
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: ClassCastException) {
            // 旧版使用 putStringSet 存储的 HashSet，自动迁移到 JSON 数组
            val oldSet = p.getStringSet(key, emptySet()) ?: emptySet()
            val list = oldSet.toList()
            if (list.isNotEmpty()) {
                setPlaylistSongsCache(context, m3u8Uri, list)
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    fun setPlaylistSongsCache(context: Context, m3u8Uri: String, songPaths: List<String>) {
        val json = org.json.JSONArray(songPaths).toString()
        prefs(context).edit().putString("pl_cache_${m3u8Uri.hashCode()}", json).apply()
    }

    /** 扫描后清除所有歌单解析缓存，强制下次重新解析 */
    fun clearPlaylistCache(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("pl_cache_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    // ── 歌单播放时是否替换现有队列（并计入历史）──
    fun getPlaylistPlayReplace(context: Context): Boolean =
        prefs(context).getBoolean("playlist_play_replace", true)

    fun setPlaylistPlayReplace(context: Context, replace: Boolean) {
        prefs(context).edit().putBoolean("playlist_play_replace", replace).apply()
    }

    // ── 首次启动引导 ──
    fun isFirstLaunch(context: Context): Boolean =
        prefs(context).getBoolean("first_launch", true)

    fun setFirstLaunchDone(context: Context) {
        prefs(context).edit().putBoolean("first_launch", false).apply()
    }

    // ── 音效：按输出设备分别记忆状态（JSON）──

    fun getAudioProfile(context: Context, device: String): String? =
        prefs(context).getString("audio_profile_$device", null)

    fun setAudioProfile(context: Context, device: String, json: String) {
        prefs(context).edit().putString("audio_profile_$device", json).apply()
    }

    /** 是否向 Super Lyric 广播歌词（状态栏歌词等外部显示） */
    fun isSuperLyricEnabled(context: Context): Boolean =
        prefs(context).getBoolean("superlyric_enabled", true)

    fun setSuperLyricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("superlyric_enabled", enabled).apply()
    }

    /** 用户自定义预设（JSON 数组） */
    fun getCustomPresets(context: Context): String? =
        prefs(context).getString("audio_custom_presets", null)

    fun setCustomPresets(context: Context, json: String) {
        prefs(context).edit().putString("audio_custom_presets", json).apply()
    }

    /** 均衡器段数（5 / 10 / 16，全局） */
    /** 曲线是否平滑显示（用户可关掉看折线） */
    fun isCurveSmooth(context: Context): Boolean =
        prefs(context).getBoolean("eq_curve_smooth", true)

    fun setCurveSmooth(context: Context, smooth: Boolean) {
        prefs(context).edit().putBoolean("eq_curve_smooth", smooth).apply()
    }

    fun getEqBandCount(context: Context): Int =
        prefs(context).getInt("audio_band_count", 16).coerceIn(3, 20)

    fun setEqBandCount(context: Context, count: Int) {
        prefs(context).edit().putInt("audio_band_count", count.coerceIn(3, 20)).apply()
    }

    /**
     * 段数是「每个均衡器自己的属性」，不是全局开关：
     * 把摇滚调到 16 段不该影响平直的段数，所以按预设 id 存一张表。
     */
    fun getPresetBandCounts(context: Context): Map<String, Int> {
        val raw = prefs(context).getString("preset_band_counts", null) ?: return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val out = HashMap<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setPresetBandCounts(context: Context, counts: Map<String, Int>) {
        val obj = org.json.JSONObject()
        counts.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit().putString("preset_band_counts", obj.toString()).apply()
    }
    // ── 运动音乐 ──

    fun getRunningTargetSpm(context: Context): Int =
        prefs(context).getInt("running_target_spm", 170)

    fun setRunningTargetSpm(context: Context, spm: Int) {
        prefs(context).edit().putInt("running_target_spm", spm.coerceIn(100, 220)).apply()
    }

    fun getRunningTolerance(context: Context): Int =
        prefs(context).getInt("running_tolerance", 8)

    fun setRunningTolerance(context: Context, bpm: Int) {
        prefs(context).edit().putInt("running_tolerance", bpm.coerceIn(2, 40)).apply()
    }

    fun getRunningDuration(context: Context): Int =
        prefs(context).getInt("running_duration", 30)

    fun setRunningDuration(context: Context, minutes: Int) {
        prefs(context).edit().putInt("running_duration", minutes.coerceIn(5, 180)).apply()
    }

    fun getRunningSource(context: Context): String =
        prefs(context).getString("running_source", "ALL") ?: "ALL"

    fun setRunningSource(context: Context, source: String) {
        prefs(context).edit().putString("running_source", source).apply()
    }

    fun getRunningUseCadence(context: Context): Boolean =
        prefs(context).getBoolean("running_use_cadence", true)

    fun setRunningUseCadence(context: Context, use: Boolean) {
        prefs(context).edit().putBoolean("running_use_cadence", use).apply()
    }
}
