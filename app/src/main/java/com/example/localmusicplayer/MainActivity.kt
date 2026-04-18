package com.example.localmusicplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: TrackAdapter
    private lateinit var txtNowPlaying: TextView
    private lateinit var txtProgress: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPauseMini: ImageButton
    private lateinit var playerPanel: LinearLayout

    private val tracks = mutableListOf<Track>()
    private var currentTrackIndex = -1
    private var isUserTracking = false
    private var lastSeekTime = 0L

    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    private val trackChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val index = intent?.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1) ?: -1
            val title = intent?.getStringExtra(MusicService.EXTRA_TITLE) ?: "Nothing playing"
            val position = intent?.getLongExtra(MusicService.EXTRA_POSITION, 0L) ?: 0L
            val duration = intent?.getLongExtra(MusicService.EXTRA_DURATION, 0L) ?: 0L
            val isPlaying = intent?.getBooleanExtra("IS_PLAYING", false) ?: false

            if (index == -1) {
                playerPanel.visibility = View.GONE
            } else {
                playerPanel.visibility = View.VISIBLE
                txtNowPlaying.text = title
                updatePauseButton(isPlaying)

                if (currentTrackIndex != index) {
                    currentTrackIndex = index
                    adapter.currentIndex = index
                    adapter.notifyDataSetChanged()

                    // ВАЖНО: Обновляем MAX только при смене трека, чтобы не дергалась полоска
                    if (duration > 0) {
                        seekBar.max = duration.toInt()
                    }
                }

                // Фикс прыжков и "отставания" полоски
                if (!isUserTracking && System.currentTimeMillis() - lastSeekTime > 600) {
                    if (duration > 0) {
                        // Если вдруг max сбился (например, при первом запуске), подстрахуем
                        if (seekBar.max != duration.toInt()) {
                            seekBar.max = duration.toInt()
                        }

                        seekBar.progress = position.toInt()
                        txtProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
        }

        initViews()
        setupListeners()

        handleIncomingIntent()
        ensureStoragePermissionThenScan()
    }

    private fun initViews() {
        val list = findViewById<ListView>(R.id.listTracks)
        txtNowPlaying = findViewById(R.id.txtNowPlaying)
        txtProgress = findViewById(R.id.txtProgress)
        seekBar = findViewById(R.id.seekBar)
        playerPanel = findViewById(R.id.playerPanel)
        btnPauseMini = findViewById(R.id.btnPauseMini)

        // Фикс коллизии: панель поглощает касания
        playerPanel.setOnTouchListener { _, _ -> true }

        adapter = TrackAdapter(this, tracks)
        list.adapter = adapter

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btnPrevMini).setOnClickListener {
            sendServiceCommand(
                MusicService.ACTION_PREV
            )
        }
        findViewById<ImageButton>(R.id.btnNextMini).setOnClickListener {
            sendServiceCommand(
                MusicService.ACTION_NEXT
            )
        }
        btnPauseMini.setOnClickListener { sendServiceCommand(MusicService.ACTION_TOGGLE) }

        findViewById<ImageButton>(R.id.btnRestore).setOnClickListener {
            prefs.edit().remove("hidden_tracks").apply()
            scanMusic()
            toast("Все треки возвращены!")
        }

        findViewById<ListView>(R.id.listTracks).setOnItemClickListener { _, _, pos, _ ->
            playTrackAt(
                pos
            )
        }

        findViewById<ListView>(R.id.listTracks).setOnItemLongClickListener { view, _, pos, _ ->
            val track = tracks[pos]
            PopupMenu(this, view).apply {
                menu.add("Скрыть")
                setOnMenuItemClickListener {
                    hideTrack(track.id)
                    removeTrackFromList(track.id)
                    true
                }
                show()
            }
            true
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtProgress.text =
                        "${formatTime(p.toLong())} / ${formatTime(s?.max?.toLong() ?: 0L)}"
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                isUserTracking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                val newPos = s?.progress?.toLong() ?: 0L
                lastSeekTime = System.currentTimeMillis()
                startService(Intent(this@MainActivity, MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra(MusicService.EXTRA_SEEK_TO, newPos)
                })
                isUserTracking = false
            }
        })
    }

    private fun playTrackAt(pos: Int) {
        val uris = ArrayList(tracks.map { it.uri.toString() })
        val titles = ArrayList(tracks.map { it.title })
        val i = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_LIST
            putStringArrayListExtra(MusicService.EXTRA_URIS, uris)
            putStringArrayListExtra(MusicService.EXTRA_TITLES, titles)
            putExtra(MusicService.EXTRA_INDEX, pos)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
        // Здесь НИЧЕГО не меняем вручную. Ждем, когда сервис пришлет ответ через Receiver.
    }

    private fun handleIncomingIntent() {
        intent?.data?.let { uri ->
            val name = getFileName(uri) ?: "Unknown"
            val i = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY_LIST
                putStringArrayListExtra(MusicService.EXTRA_URIS, arrayListOf(uri.toString()))
                putStringArrayListExtra(MusicService.EXTRA_TITLES, arrayListOf(name))
                putExtra(MusicService.EXTRA_INDEX, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(
                i
            )
        }
    }

    private fun scanMusic() {
        tracks.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} >= 1000000"

        contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val hidden = getHiddenTracks()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (hidden.contains(id.toString())) continue
                val name = cursor.getString(nameCol).substringBeforeLast(".")
                tracks.add(
                    Track(
                        id,
                        name,
                        cursor.getString(artistCol) ?: "Unknown",
                        ContentUris.withAppendedId(collection, id),
                        cursor.getLong(durCol),
                        cursor.getLong(albCol)
                    )
                )
            }
        }
        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
        adapter.notifyDataSetChanged()
    }

    // Служебные функции
    private fun sendServiceCommand(act: String) =
        startService(Intent(this, MusicService::class.java).setAction(act))

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun getHiddenTracks() =
        prefs.getStringSet("hidden_tracks", mutableSetOf()) ?: mutableSetOf()

    fun hideTrack(id: Long) {
        val s = getHiddenTracks().toMutableSet().apply { add(id.toString()) }
        prefs.edit().putStringSet("hidden_tracks", s).apply()
    }

    fun removeTrackFromList(id: Long) {
        val idx = tracks.indexOfFirst { it.id == id }
        if (idx != -1) {
            tracks.removeAt(idx)
            adapter.notifyDataSetChanged()
            if (idx == currentTrackIndex) sendServiceCommand(MusicService.ACTION_STOP)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun updatePauseButton(playing: Boolean) {
        btnPauseMini.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(MusicService.ACTION_TRACK_CHANGED)
            addAction(MusicService.ACTION_PROGRESS)
        }

        // Используем ContextCompat — он умный, сам поймет как регистрировать на любой версии
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            trackChangedReceiver,
            f,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        sendServiceCommand(MusicService.ACTION_PROGRESS)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(trackChangedReceiver) } catch (e: Exception) {}
    }

    private fun ensureStoragePermissionThenScan() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) scanMusic()
        else registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) scanMusic() }.launch(p)
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var n: String? = null
        if (uri.scheme == "content") contentResolver.query(uri, null, null, null, null)?.use {
            val i = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && i >= 0) n = it.getString(i)
        }
        return n ?: uri.lastPathSegment
    }
}