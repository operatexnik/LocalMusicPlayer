package com.example.localmusicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

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
    private lateinit var mediaSession: MediaSession
    private var titles: List<String> = emptyList()

    // Стабильное состояние — обновляется только когда плеер не в переходном состоянии
    private var lastKnownIsPlaying = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                broadcastProgress()
                // Обновляем раз в секунду, а не 5 раз в секунду
                progressHandler.postDelayed(this, 1000)
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        player = ExoPlayer.Builder(this).build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        mediaSession = MediaSession.Builder(this, player).build()
        createChannel()

        player.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Обновляем стабильное состояние только когда не грузимся
                if (!player.isLoading) {
                    lastKnownIsPlaying = isPlaying
                }
                updateNotification()
                if (isPlaying) {
                    progressHandler.post(progressRunnable)
                } else {
                    progressHandler.removeCallbacks(progressRunnable)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveCurrentIndex()
                // Шлём название сразу — duration ещё нет, но название уже знаем
                broadcastTitleOnly()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Теперь duration известен и isPlaying стабилен
                    lastKnownIsPlaying = player.isPlaying
                    broadcastCurrentIndex()
                    updateNotification()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // --- ПРАВКА 1: Гарантированный запуск уведомления, чтобы не было вылета RemoteServiceException ---
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // -------------------------------------------------------------------------------------------------

        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                val keepPosition = intent.getBooleanExtra("KEEP_POSITION", false)

                val items = uris.map { MediaItem.fromUri(it) }

                if (keepPosition && player.isPlaying && player.currentMediaItemIndex == index) {
                    player.setMediaItems(items, index, player.currentPosition)
                } else {
                    player.setMediaItems(items, index, 0L)
                }

                if (requestAudioFocus()) {
                    player.prepare()
                    if (!keepPosition) player.play()
                }

                saveCurrentIndex()
                // Заменил startForeground на updateNotification, так как мы уже вызвали его выше
                updateNotification()
            }

            ACTION_TOGGLE -> {
                if (player.isPlaying) player.pause() else player.play()
                // onIsPlayingChanged сам всё обновит
            }

            ACTION_SEEK -> {
                val seekTo = intent.getLongExtra(EXTRA_SEEK_TO, 0L)
                player.seekTo(seekTo)
                broadcastProgress()
            }

            ACTION_NEXT -> {
                playNext()
                saveCurrentIndex()
            }

            ACTION_PREV -> {
                playPrevious()
                saveCurrentIndex()
            }

            ACTION_STOP -> {
                progressHandler.removeCallbacks(progressRunnable)
                player.stop()
                player.clearMediaItems()
                lastKnownIsPlaying = false

                sendBroadcast(Intent(ACTION_TRACK_CHANGED).apply {
                    putExtra(EXTRA_CURRENT_INDEX, -1)
                    putExtra(EXTRA_TITLE, "Nothing playing")
                    putExtra(EXTRA_POSITION, 0L)
                    putExtra(EXTRA_DURATION, 0L)
                    putExtra("IS_PLAYING", false)
                })

                clearCurrentIndex()
                stopForeground(true)
                stopSelf()
            }

            ACTION_PROGRESS -> broadcastProgress()
        }

        return START_STICKY
    }

    private fun playPrevious() {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex > 0) {
            player.seekTo(currentIndex - 1, 0L)
        } else {
            player.seekTo((player.mediaItemCount - 1).coerceAtLeast(0), 0L)
        }
        player.play()
    }

    private fun playNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else {
            player.seekTo(0, 0L)
        }
        player.play()
    }

    // Шлём только название сразу при смене трека — иконку не трогаем
    private fun broadcastTitleOnly() {
        if (player.mediaItemCount == 0) return
        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Nothing playing"

        sendBroadcast(Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, 0L)
            putExtra(EXTRA_DURATION, 0L)
            putExtra("IS_PLAYING", lastKnownIsPlaying)
        })
    }

    private fun broadcastCurrentIndex() {
        if (player.mediaItemCount == 0) {
            sendBroadcast(Intent(ACTION_TRACK_CHANGED).apply {
                putExtra(EXTRA_CURRENT_INDEX, -1)
                putExtra(EXTRA_TITLE, "Nothing playing")
                putExtra(EXTRA_POSITION, 0L)
                putExtra(EXTRA_DURATION, 0L)
                putExtra("IS_PLAYING", false)
            })
            return
        }

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Nothing playing"
        val duration = if (player.duration > 0) player.duration else 0L
        val position = player.currentPosition.coerceAtLeast(0L)

        sendBroadcast(Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
            putExtra("IS_PLAYING", lastKnownIsPlaying)
        })
    }

    private fun broadcastProgress() {
        if (!::player.isInitialized || player.mediaItemCount == 0) {
            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                putExtra(EXTRA_CURRENT_INDEX, -1)
                putExtra(EXTRA_TITLE, "Nothing playing")
                putExtra(EXTRA_POSITION, 0L)
                putExtra(EXTRA_DURATION, 0L)
                putExtra("IS_PLAYING", false)
            })
            return
        }

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Nothing playing"
        val duration = if (player.duration > 0) player.duration else 0L
        val position = player.currentPosition.coerceAtLeast(0L)

        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_CURRENT_INDEX, idx)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
            putExtra("IS_PLAYING", lastKnownIsPlaying)
        })
    }

    @OptIn(UnstableApi::class)
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
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        // --- ПРАВКА 2: Безопасная загрузка картинки, чтобы метод не крашился ---
        val artworkBitmap = try {
            android.graphics.BitmapFactory.decodeResource(resources, R.drawable.default_cover)
        } catch (e: Exception) {
            null
        }
        // -----------------------------------------------------------------------

        val idx = player.currentMediaItemIndex
        val title = if (idx in titles.indices) titles[idx] else "Track"
        val artist = "Local Artist"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)      // Жирный текст (Название)
            .setContentText(artist)      // Текст ниже (Автор)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Чтобы не мигало и не пикало при каждой смене трека/паузе
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(lastKnownIsPlaying) // Если на паузе — шторку можно смахнуть
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_previous, "Prev", prevIntent))
            .addAction(
                NotificationCompat.Action(
                    if (lastKnownIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (lastKnownIsPlaying) "Pause" else "Play",
                    toggleIntent
                )
            )
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", nextIntent))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView() // Prev, Play/Pause, Next
                    // Убрали fromToken, передаем напрямую!
                    .setMediaSession(mediaSession.sessionCompatToken as MediaSessionCompat.Token)
            )
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> if (player.isPlaying) player.pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (player.isPlaying) player.pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (player.isPlaying) player.volume = 0.2f
                AudioManager.AUDIOFOCUS_GAIN -> player.volume = 1.0f
            }
        }

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(afChangeListener)
            .build()

        return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Music", NotificationManager.IMPORTANCE_DEFAULT)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun saveCurrentIndex() {
        prefs.edit().putInt("current_index", player.currentMediaItemIndex).apply()
    }

    private fun clearCurrentIndex() {
        prefs.edit().putInt("current_index", -1).apply()
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        mediaSession.release()
        player.release()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}