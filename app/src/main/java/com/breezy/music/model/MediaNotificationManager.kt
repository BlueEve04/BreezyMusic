package com.breezy.music.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.breezy.music.MainActivity
import com.breezy.music.model.AudioEngine

/**
 * 系统媒体通知管理器（MediaStyle）
 *
 * 使用 MediaSession + MediaStyle 通知模板，提供锁屏/通知栏播放控制。
 */
object MediaNotificationManager {

    private const val CHANNEL_ID = "breezy_music_playback"
    private const val CHANNEL_NAME = "音乐播放"
    private const val NOTIFICATION_ID = 1

    private var notificationManager: NotificationManager? = null
    private var mediaSession: MediaSessionCompat? = null

    /** 初始化通知渠道 + MediaSession（仅需调用一次） */
    fun init(context: Context) {
        notificationManager = context.getSystemService(NotificationManager::class.java)

        // 通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示当前播放的歌曲信息与播放控制"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        // MediaSession
        mediaSession = MediaSessionCompat(context, "BreezyMusic").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!PlaybackState.isPlaying) {
                        if (AudioEngine.isStopped()) {
                            PlaybackState.currentSong?.let { AudioEngine.play(context, it.path) }
                        } else {
                            AudioEngine.resume()
                        }
                        PlaybackState.notifyUpdatePublic()
                    }
                }
                override fun onPause() {
                    if (PlaybackState.isPlaying) {
                        AudioEngine.pause()
                        PlaybackState.notifyUpdatePublic()
                    }
                }
                override fun onSkipToNext() { PlaybackState.playNext() }
                override fun onSkipToPrevious() { PlaybackState.playPrevious() }
                override fun onStop() {
                    if (PlaybackState.isPlaying) {
                        AudioEngine.pause()
                        PlaybackState.notifyUpdatePublic()
                    }
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .build()
            )
            isActive = true
        }
    }

    /** 创建 action PendingIntent */
    private fun actionIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 显示/更新 MediaStyle 播放通知 */
    fun showPlaying(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        albumArt: android.graphics.Bitmap? = null,
        position: Long = 0L,
        duration: Long = 0L
    ) {
        try {
            val nm = notificationManager ?: return
            val session = mediaSession
            if (session == null) {
                android.util.Log.w("MediaNotif", "mediaSession is null, re-initializing")
                init(context)
                return
            }
            session.isActive = true

        // 更新 MediaSession 播放状态（使用真实位置）
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, position, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )

        session.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .apply { if (duration > 0) putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, duration) }
                .apply { if (albumArt != null) putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt) }
                .build()
        )

        // 打开应用 Intent
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setSmallIcon(com.breezy.music.R.drawable.breezy_ico)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "上一首", actionIntent(context, "prev"))
            .addAction(playPauseIcon, if (isPlaying) "暂停" else "播放", actionIntent(context, "playpause"))
            .addAction(android.R.drawable.ic_media_next, "下一首", actionIntent(context, "next"))

        // 专辑封面
        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }
        // 进度条（有时长就显示，播放/暂停均显示当前位置）
        if (duration > 0) {
            builder.setProgress(duration.toInt(), position.toInt(), false)
        } else {
            builder.setProgress(0, 0, false)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.w("MediaNotif", "showPlaying failed", e)
        }
    }

    /** 取消通知 */
    fun cancel() {
        notificationManager?.cancel(NOTIFICATION_ID)
        mediaSession?.isActive = false
    }
}
