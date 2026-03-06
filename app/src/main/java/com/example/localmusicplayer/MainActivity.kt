package com.example.localmusicplayer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

data class Track(val title: String, val uri: String)

class MainActivity : AppCompatActivity() {

    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var adapter: TrackAdapter

    private val tracks = mutableListOf<Track>()

    private val trackChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val index = intent?.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1) ?: -1
            currentTrackIndex = index
            adapter.currentIndex = index
            adapter.notifyDataSetChanged()
        }
    }
    private var currentTrackIndex = -1
    private val prefs by lazy { getSharedPreferences("player_prefs", MODE_PRIVATE) }

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

        // Уведомления нужны для шторки на Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(
                    this,
                    perm
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPerm.launch(perm)
            }
        }

        val btnScan = findViewById<Button>(R.id.btnScan)
        val list = findViewById<ListView>(R.id.listTracks)

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
                Intent(
                    this,
                    MusicService::class.java
                ).setAction(MusicService.ACTION_TOGGLE)
            )
        }

        btnStop.setOnClickListener {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_STOP))
        }

        btnPrev.setOnClickListener {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_PREV))
        }

        btnNext.setOnClickListener {
            startService(Intent(this, MusicService::class.java).setAction(MusicService.ACTION_NEXT))
        }

        btnScan.setOnClickListener {
            ensureStoragePermissionThenScan()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val t = tracks[pos]
            currentTrackIndex = pos
            adapter.currentIndex = pos
            adapter.notifyDataSetChanged()

            val uris = ArrayList(tracks.map { it.uri })
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                trackChangedReceiver,
                android.content.IntentFilter(MusicService.ACTION_TRACK_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            ContextCompat.registerReceiver(
                this,
                trackChangedReceiver,
                android.content.IntentFilter(MusicService.ACTION_TRACK_CHANGED),
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
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_MUSIC
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val uri = ContentUris.withAppendedId(collection, id).toString()

                tracks.add(Track(name, uri))
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

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}