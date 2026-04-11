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
        requestPlayerState()

        if (currentTrackIndex != -1) {
            playerPanel.visibility = View.VISIBLE
        } else {
            playerPanel.visibility = View.GONE
        }
    }

    private fun requestPlayerState() {
        startService(
            Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PROGRESS
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Обработка открытия файла через плеер (Intent)
        intent?.data?.let { uri ->
            val name = getFileName(uri)
            val i = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY_LIST
                putStringArrayListExtra(MusicService.EXTRA_URIS, arrayListOf(uri.toString()))
                putStringArrayListExtra(MusicService.EXTRA_TITLES, arrayListOf(name ?: "Unknown"))
                putExtra(MusicService.EXTRA_INDEX, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        }

        // 2. Инициализация View
        val list = findViewById<ListView>(R.id.listTracks)
        val btnRestore = findViewById<android.widget.ImageButton>(R.id.btnRestore)
        txtNowPlaying = findViewById(R.id.txtNowPlaying)
        txtProgress = findViewById(R.id.txtProgress)
        seekBar = findViewById(R.id.seekBar)
        playerPanel = findViewById(R.id.playerPanel)

        // 3. Настройка адаптера
        adapter = TrackAdapter(this, tracks)
        list.adapter = adapter
        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex

        // 4. Логика кнопки "Восстановления" (на Toolbar)
        btnRestore.setOnClickListener {
            prefs.edit().remove("hidden_tracks").apply()
            scanMusic()
            toast("Все треки возвращены!")
        }
        btnRestore.setOnLongClickListener {
            showRestoreDialog()
            true
        }

        // 5. Обычный клик по треку (Запуск музыки)
        list.setOnItemClickListener { _, _, pos, _ ->
            val t = tracks[pos]
            currentTrackIndex = pos
            adapter.currentIndex = pos
            adapter.notifyDataSetChanged()

            val uris = ArrayList(tracks.map { it.uri.toString() })
            val titles = ArrayList(tracks.map { it.title.substringBeforeLast(".").replace("_", " ") })
            val i = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY_LIST
                putStringArrayListExtra(MusicService.EXTRA_URIS, uris)
                putStringArrayListExtra(MusicService.EXTRA_TITLES, titles)
                putExtra(MusicService.EXTRA_INDEX, pos)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)

            toast("▶ ${t.title}")
        }

        // 6. ДОЛГИЙ клик по треку (Удаление/Blacklist)
        list.setOnItemLongClickListener { view, _, pos, _ ->
            val track = tracks[pos]
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("Удалить")
            popup.setOnMenuItemClickListener {
                hideTrack(track.id)
                removeTrackFromList(track.id)
                toast("Скрыто: ${track.title}")
                true
            }
            popup.show()
            true
        }

        // 7. Настройка SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    txtProgress.text = "${formatTime(p.toLong())} / ${formatTime(s?.max?.toLong() ?: 0L)}"
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val newPos = s?.progress?.toLong() ?: 0L
                startService(Intent(this@MainActivity, MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra(MusicService.EXTRA_SEEK_TO, newPos)
                })
            }
        })

        // 8. Регистрация ресивера и проверка разрешений
        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_TRACK_CHANGED)
            addAction(MusicService.ACTION_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(this, trackChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPerm.launch(perm)
            }
        }

        ensureStoragePermissionThenScan()
    }

    private fun ensureStoragePermissionThenScan() {
        // Выбираем нужное разрешение в зависимости от версии Android
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

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
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} >= ?"
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
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            val hidden = getHiddenTracks()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (hidden.contains(id.toString())) continue

                val rawName = cursor.getString(nameCol)
                val name = rawName.substringBeforeLast(".")
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val duration = cursor.getLong(durationCol)
                val albumId = cursor.getLong(albumIdCol) // Это нам нужно для обложек
                val uri = ContentUris.withAppendedId(collection, id)

                tracks.add(Track(id, name, artist, uri, duration, albumId))
            }
        }

        // Обновляем индекс из памяти, чтобы после сканирования подсветить текущий трек
        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex

        adapter.notifyDataSetChanged()
        toast("Нашёл треков: ${tracks.size}")
    }

    override fun onDestroy() {
        unregisterReceiver(trackChangedReceiver)
        super.onDestroy()
    }
    fun removeTrackFromList(id: Long) {
        tracks.removeAll { it.id == id }
        adapter.notifyDataSetChanged()
    }
    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    private fun getHiddenTracks(): MutableSet<String> {
        return prefs.getStringSet("hidden_tracks", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    fun hideTrack(id: Long) {
        val set = getHiddenTracks()
        set.add(id.toString())
        prefs.edit().putStringSet("hidden_tracks", set).apply()
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var name: String? = null

        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (it.moveToFirst() && index >= 0) {
                    name = it.getString(index)
                }
            }
        }

        if (name == null) {
            name = uri.lastPathSegment
        }

        return name
    }
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
    private fun showRestoreDialog() {
        val hiddenIds = getHiddenTracks()
        if (hiddenIds.isEmpty()) {
            toast("Blacklist пуст")
            return
        }

        // Сопоставляем ID и Имена
        val namesMap = mutableMapOf<String, String>()

        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()

                if (hiddenIds.contains(id)) {
                    val rawName = cursor.getString(nameCol)
                    // Обрезаем расширение и убираем подчеркивания (бонус)
                    val cleanName = rawName.substringBeforeLast(".").replace("_", " ")
                    namesMap[id] = cleanName
                }
            }
        }

        val idsArray = namesMap.keys.toTypedArray()
        val namesArray = namesMap.values.toTypedArray()
        val selectedIds = mutableListOf<String>()

        android.app.AlertDialog.Builder(this)
            .setTitle("Восстановить треки")
            .setMultiChoiceItems(namesArray, null) { _, which, isChecked ->
                if (isChecked) selectedIds.add(idsArray[which])
                else selectedIds.remove(idsArray[which])
            }
            .setPositiveButton("Восстановить") { _, _ ->
                val currentHidden = getHiddenTracks()
                currentHidden.removeAll(selectedIds.toSet())
                prefs.edit().putStringSet("hidden_tracks", currentHidden).apply()
                scanMusic()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}