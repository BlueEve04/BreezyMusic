package com.breezy.music.model

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 专辑封面内存缓存（LRU，不落盘）
 *
 * 从媒体文件 URI 即时提取嵌入式封面，缓存在内存中。
 * 大库（50k+）不增加存储开销。
 */
object AlbumArtCache {

    private const val TAG = "AlbumArtCache"
    private const val DEFAULT_MAX_SIZE = 64

    private var cache = LruCache<String, ImageBitmap>(DEFAULT_MAX_SIZE)

    /** 根据设置更新缓存大小 */
    fun updateMaxSize(context: Context) {
        val sizeMB = Preferences.getCoverCacheSizeMB(context)
        // -1=无限 → 4倍默认条目；否则按 MB/4 估算条目数
        val entries = if (sizeMB == -1) DEFAULT_MAX_SIZE * 4
        else (sizeMB / 4).coerceIn(DEFAULT_MAX_SIZE, 1024)
        if (cache.maxSize() != entries) {
            cache = LruCache(entries)
        }
    }

    /** 从 content URI 即时提取封面（带内存缓存） */
    fun get(context: Context, uriString: String): ImageBitmap? {
        cache.get(uriString)?.let {
            Log.d(TAG, "cache hit: $uriString")
            return it
        }

        return try {
            val uri = Uri.parse(uriString)
            Log.d(TAG, "extracting art from: $uriString")
            val mmr = MediaMetadataRetriever()
            // SAF content URI → FileDescriptor
            if (uri.scheme == "content") {
                val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                if (pfd != null) {
                    mmr.setDataSource(pfd.fileDescriptor, pfd.startOffset, pfd.length)
                    pfd.close()
                } else {
                    Log.w(TAG, "openAssetFileDescriptor null for $uriString")
                    mmr.release()
                    return null
                }
            } else {
                mmr.setDataSource(context, uri)
            }
            val picture = mmr.embeddedPicture
            mmr.release()

            if (picture != null && picture.isNotEmpty()) {
                Log.d(TAG, "got embedded picture: ${picture.size} bytes")
                val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
                val image = bitmap.asImageBitmap()
                cache.put(uriString, image)
                image
            } else {
                Log.d(TAG, "no embedded picture in: $uriString")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract art: $uriString — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun clear() = cache.evictAll()
}
