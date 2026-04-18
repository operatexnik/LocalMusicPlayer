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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

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
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var session: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private var titles: List<String> = emptyList()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                broadcastProgress()
                // Обновляем каждые 200мс для плавности SeekBar
                progressHandler.postDelayed(this, 200)
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        player = ExoPlayer.Builder(this).build()
        session = MediaSessionCompat(this, "LocalMusicPlayer")
        session.isActive = true

        mediaSessionConnector = MediaSessionConnector(session)
        mediaSessionConnector.setPlayer(player)
        player.repeatMode = Player.REPEAT_MODE_ALL

        createChannel()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                if (isPlaying) {
                    progressHandler.post(progressRunnable)
                } else {
                    progressHandler.removeCallbacks(progressRunnable)
                    broadcastProgress() // Отправить финальный статус при паузе
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                broadcastCurrentIndex()
                updateNotification()
                saveCurrentIndex()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                val keepPosition = intent.getBooleanExtra("KEEP_POSITION", false)

                val items = uris.map { MediaItem.fromUri(it) }

                if (keepPosition && player.currentMediaItemIndex == index) {
                    player.setMediaItems(items, index, player.currentPosition)
                } else {
                    player.setMediaItems(items, index, 0L)
                }

                if (requestAudioFocus()) {
                    player.prepare()
                    // Возвращаем твоё условие: играть только если не keepPosition
                    if (!keepPosition) player.play()
                }
                startForeground(NOTIF_ID, buildNotification())
            }

            ACTION_TOGGLE -> {
                if (player.isPlaying) player.pause() else player.play()
            }

            ACTION_SEEK -> {
                val seekTo = intent.getLongExtra(EXTRA_SEEK_TO, 0L)
                player.seekTo(seekTo)
                player.play() // ФИКС: Играем сразу после перемотки
                broadcastProgress()
            }

            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()

            ACTION_STOP -> {
                player.stop()
                player.clearMediaItems()
                clearCurrentIndex()
                stopForeground(true)
                stopSelf()
            }

            ACTION_PROGRESS -> broadcastProgress()
        }
        return START_STICKY
    }

    private fun playPrevious() {
        if (player.currentPosition > 5000) {
            player.seekTo(0L)
        } else {
            if (player.hasPreviousMediaItem()) player.seekToPrevious()
            else player.seekTo(player.mediaItemCount - 1, 0L)
        }
        player.play()
    }

    private fun playNext() {
        if (player.hasNextMediaItem()) player.seekToNext()
        else player.seekTo(0, 0L)
        player.play()
    }

    private fun broadcastCurrentIndex() = broadcastProgress(ACTION_TRACK_CHANGED)
    private fun broadcastProgress(action: String = ACTION_PROGRESS) {
        if (!::player.isInitialized) return

        val idx = player.currentMediaItemIndex
        val intent = Intent(action).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, if (idx in titles.indices) titles[idx] else "Unknown")
            putExtra(EXTRA_POSITION, player.currentPosition)
            putExtra(EXTRA_DURATION, player.duration.coerceAtLeast(0L))
            putExtra("IS_PLAYING", player.isPlaying)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val pToggle = PendingIntent.getService(this, 1,
            Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

        val pNext = PendingIntent.getService(this, 2,
            Intent(this, MusicService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

        val pPrev = PendingIntent.getService(this, 3,
            Intent(this, MusicService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

        val pStop = PendingIntent.getService(this, 4,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

        val pOpen = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Music Player"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play) // СТРОКА-СПАСИТЕЛЬНИЦА (теперь не вылетит)
            .setContentIntent(pOpen)
            .setContentTitle(title)
            .setOngoing(player.isPlaying)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_previous, "Prev", pPrev))
            .addAction(NotificationCompat.Action(
                if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Toggle", pToggle))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", pNext))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { focus ->
                when (focus) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> player.play()
                }
            }.build()
        return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun saveCurrentIndex() = prefs.edit().putInt("current_index", player.currentMediaItemIndex).apply()
    private fun clearCurrentIndex() = prefs.edit().putInt("current_index", -1).apply()
    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        player.release()
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}