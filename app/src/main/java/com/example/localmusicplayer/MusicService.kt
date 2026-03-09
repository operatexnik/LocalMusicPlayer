package com.example.localmusicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import android.os.Handler
import android.os.Looper

class MusicService : Service() {

    companion object {

        const val ACTION_SEEK = "SEEK"
        const val EXTRA_SEEK_TO = "seek_to"
        const val ACTION_PROGRESS = "com.example.localmusicplayer.PROGRESS"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val ACTION_TRACK_CHANGED = "com.example.localmusicplayer.TRACK_CHANGED"
        const val EXTRA_CURRENT_INDEX = "current_index"
        const val CHANNEL_ID = "music_channel"
        const val NOTIF_ID = 1

        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"

        const val ACTION_PLAY_LIST = "PLAY_LIST"
        const val ACTION_TOGGLE = "TOGGLE"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_PREV = "PREV"
        const val ACTION_STOP = "STOP"
    }

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private var titles: List<String> = emptyList()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                broadcastProgress()
                progressHandler.postDelayed(this, 1000)
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build()
        session = MediaSessionCompat(this, "LocalMusicPlayer")
        session.isActive = true

        mediaSessionConnector = MediaSessionConnector(session)
        mediaSessionConnector.setPlayer(player)
        player.repeatMode = Player.REPEAT_MODE_ALL

        createChannel()

        player.addListener(object : Player.Listener{
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                progressHandler.post(progressRunnable)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                broadcastCurrentIndex()
                updateNotification()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                val items = uris.map { MediaItem.fromUri(it) }
                player.setMediaItems(items, index, 0L)
                player.prepare()
                player.play()
                broadcastCurrentIndex()
                saveCurrentIndex()

                startForeground(NOTIF_ID, buildNotification())
                updateNotification()
            }

            ACTION_TOGGLE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateNotification()
            }

            ACTION_SEEK -> {
                val seekTo = intent.getLongExtra(EXTRA_SEEK_TO, 0L)
                player.seekTo(seekTo)
                broadcastProgress()
                updateNotification()
            }

            ACTION_NEXT -> {
                if (player.hasNextMediaItem()) player.seekToNext() else player.seekTo(0, 0L)
                player.play()
                broadcastCurrentIndex()
                saveCurrentIndex()
                updateNotification()
            }

            ACTION_PREV -> {
                if (player.hasPreviousMediaItem()) player.seekToPrevious()
                else {
                    val last = (player.mediaItemCount - 1).coerceAtLeast(0)
                    player.seekTo(last, 0L)
                }
                player.play()
                broadcastCurrentIndex()
                saveCurrentIndex()
                updateNotification()
            }

            ACTION_STOP -> {
                player.stop()
                player.clearMediaItems()

                val intent = Intent(ACTION_TRACK_CHANGED).apply {
                    putExtra(EXTRA_CURRENT_INDEX, -1)
                    putExtra(EXTRA_TITLE, "Nothing playing")
                    putExtra(EXTRA_POSITION, 0L)
                    putExtra(EXTRA_DURATION, 0L)
                }
                sendBroadcast(intent)

                clearCurrentIndex()
                stopForeground(true)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        session.release()
        player.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MusicService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val isPlaying = player.isPlaying
        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Playing"
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(openAppIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_previous, "Prev", prevIntent))
            .addAction(
                NotificationCompat.Action(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    toggleIntent
                )
            )
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", nextIntent))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Music",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }
    private fun saveCurrentIndex() {
        prefs.edit()
            .putInt("current_index", player.currentMediaItemIndex)
            .apply()
    }

    private fun clearCurrentIndex() {
        prefs.edit()
            .putInt("current_index", -1)
            .apply()
    }
    private fun broadcastCurrentIndex() {
        if (player.mediaItemCount == 0 || player.currentMediaItemIndex == -1) {
            val intent = Intent(ACTION_TRACK_CHANGED).apply {
                putExtra(EXTRA_CURRENT_INDEX, -1)
                putExtra(EXTRA_TITLE, "Nothing playing")
                putExtra(EXTRA_POSITION, 0L)
                putExtra(EXTRA_DURATION, 0L)
            }
            sendBroadcast(intent)
            return
        }

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Nothing playing"
        val duration = if (player.duration > 0) player.duration else 0L
        val position = player.currentPosition.coerceAtLeast(0L)

        val intent = Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
        }
        sendBroadcast(intent)
    }
    private fun broadcastProgress() {
        if (player.mediaItemCount == 0 || player.currentMediaItemIndex == -1) {
            val intent = Intent(ACTION_PROGRESS).apply {
                putExtra(EXTRA_CURRENT_INDEX, -1)
                putExtra(EXTRA_TITLE, "Nothing playing")
                putExtra(EXTRA_POSITION, 0L)
                putExtra(EXTRA_DURATION, 0L)
            }
            sendBroadcast(intent)
            return
        }

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Nothing playing"
        val duration = if (player.duration > 0) player.duration else 0L
        val position = player.currentPosition.coerceAtLeast(0L)

        val intent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
        }
        sendBroadcast(intent)
    }
    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}