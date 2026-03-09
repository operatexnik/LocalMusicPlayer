package com.example.localmusicplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var adapter: TrackAdapter
    private lateinit var txtNowPlaying: TextView
    private lateinit var txtProgress: TextView
    private lateinit var seekBar: SeekBar

    private lateinit var playerPanel: LinearLayout

    private val tracks = mutableListOf<Track>()
    private var currentTrackIndex = -1

    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

    private val trackChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicService.ACTION_TRACK_CHANGED -> {
                    val index = intent.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1)
                    val title = intent.getStringExtra(MusicService.EXTRA_TITLE) ?: "Nothing playing"
                    val position = intent.getLongExtra(MusicService.EXTRA_POSITION, 0L)
                    val duration = intent.getLongExtra(MusicService.EXTRA_DURATION, 0L)

                    currentTrackIndex = index
                    adapter.currentIndex = index
                    adapter.notifyDataSetChanged()

                    if (index == -1) {
                        playerPanel.visibility = View.GONE
                        txtNowPlaying.text = ""
                        txtProgress.text = ""
                        seekBar.max = 0
                        seekBar.progress = 0
                    } else {
                        playerPanel.visibility = View.VISIBLE
                        txtNowPlaying.text = title
                        txtProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
                        seekBar.max = duration.toInt()
                        seekBar.progress = position.toInt()
                    }
                }

                MusicService.ACTION_PROGRESS -> {
                    val index = intent.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1)
                    val title = intent.getStringExtra(MusicService.EXTRA_TITLE) ?: "Nothing playing"
                    val position = intent.getLongExtra(MusicService.EXTRA_POSITION, 0L)
                    val duration = intent.getLongExtra(MusicService.EXTRA_DURATION, 0L)

                    if (index == -1 || duration <= 0L) {
                        txtNowPlaying.text = ""
                        txtProgress.text = ""
                        seekBar.max = 0
                        seekBar.progress = 0
                    } else {
                        txtNowPlaying.text = title
                        txtProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
                        seekBar.max = duration.toInt()
                        seekBar.progress = position.toInt()
                    }
                }
            }
        }
    }

    private val requestStoragePerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanMusic()
        else toast("Нужен доступ к музыке 🙃")
    }

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Без уведомлений шторки не будет 😅")
    }

    override fun onResume() {
        super.onResume()
        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPerm.launch(perm)
            }
        }

        val btnScan = findViewById<Button>(R.id.btnScan)
        val list = findViewById<ListView>(R.id.listTracks)
        txtNowPlaying = findViewById(R.id.txtNowPlaying)
        txtProgress = findViewById(R.id.txtProgress)
        seekBar = findViewById(R.id.seekBar)
        playerPanel = findViewById(R.id.playerPanel)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtProgress.text = "${formatTime(progress.toLong())} / ${formatTime(seekBar?.max?.toLong() ?: 0L)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val newPosition = seekBar?.progress?.toLong() ?: 0L

                startService(
                    Intent(this@MainActivity, MusicService::class.java).apply {
                        action = MusicService.ACTION_SEEK
                        putExtra(MusicService.EXTRA_SEEK_TO, newPosition)
                    }
                )
            }
        })

        adapter = TrackAdapter(this, tracks)
        list.adapter = adapter

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex

        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        val btnPlayPause = findViewById<Button>(R.id.btnPlayPause)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnPrev.isEnabled = false
        btnNext.isEnabled = false

        btnPlayPause.setOnClickListener {
            startService(
                Intent(this, MusicService::class.java)
                    .setAction(MusicService.ACTION_TOGGLE)
            )
        }

        btnStop.setOnClickListener {
            startService(
                Intent(this, MusicService::class.java)
                    .setAction(MusicService.ACTION_STOP)
            )
        }

        btnPrev.setOnClickListener {
            startService(
                Intent(this, MusicService::class.java)
                    .setAction(MusicService.ACTION_PREV)
            )
        }

        btnNext.setOnClickListener {
            startService(
                Intent(this, MusicService::class.java)
                    .setAction(MusicService.ACTION_NEXT)
            )
        }

        btnScan.setOnClickListener {
            ensureStoragePermissionThenScan()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val t = tracks[pos]
            currentTrackIndex = pos
            adapter.currentIndex = pos
            adapter.notifyDataSetChanged()

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

            toast("▶ ${t.title}")
        }

        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_TRACK_CHANGED)
            addAction(MusicService.ACTION_PROGRESS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                trackChangedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        ensureStoragePermissionThenScan()
    }

    private fun ensureStoragePermissionThenScan() {
        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            scanMusic()
        } else {
            requestStoragePerm.launch(perm)
        }
    }

    private fun scanMusic() {
        val minBytes = 1_000_000L
        tracks.clear()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC}=1 AND ${MediaStore.Audio.Media.SIZE}>=?"
        val selectionArgs = arrayOf(minBytes.toString())

        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val duration = cursor.getLong(durationCol)
                val uri = ContentUris.withAppendedId(collection, id)

                tracks.add(
                    Track(
                        id = id,
                        title = name,
                        artist = artist,
                        uri = uri,
                        duration = duration
                    )
                )
            }
        }

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
        adapter.notifyDataSetChanged()

        val enabled = tracks.isNotEmpty()
        btnPrev.isEnabled = enabled
        btnNext.isEnabled = enabled

        toast("Нашёл: ${adapter.count}")
    }

    override fun onDestroy() {
        unregisterReceiver(trackChangedReceiver)
        super.onDestroy()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}