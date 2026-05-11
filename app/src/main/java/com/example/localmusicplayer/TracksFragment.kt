package com.example.localmusicplayer

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TracksFragment : Fragment() {

    private lateinit var adapter: TrackAdapter
    private lateinit var txtNowPlaying: TextView
    private lateinit var txtProgress: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPauseMini: ImageButton
    private lateinit var playerPanel: LinearLayout

    val tracks = mutableListOf<Track>()
    private var currentTrackIndex = -1
    private var isUserTracking = false
    private var lastSeekTime = 0L
    private var initialStateReceived = false

    private val prefs by lazy { requireContext().getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }

    private val trackChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicService.ACTION_TRACK_CHANGED -> {
                    val index = intent.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1)
                    val title = intent.getStringExtra(MusicService.EXTRA_TITLE) ?: ""
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
                        updatePauseButton(false)
                    } else {
                        playerPanel.visibility = View.VISIBLE
                        txtNowPlaying.text = title
                        if (!isUserTracking && duration > 0) {
                            seekBar.max = duration.toInt()
                            seekBar.progress = position.toInt()
                            txtProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
                        }
                        updatePauseButton(true)
                    }
                }

                MusicService.ACTION_PROGRESS -> {
                    val index = intent.getIntExtra(MusicService.EXTRA_CURRENT_INDEX, -1)
                    val title = intent.getStringExtra(MusicService.EXTRA_TITLE) ?: ""
                    val position = intent.getLongExtra(MusicService.EXTRA_POSITION, 0L)
                    val duration = intent.getLongExtra(MusicService.EXTRA_DURATION, 0L)
                    val isPlaying = intent.getBooleanExtra("IS_PLAYING", false)

                    if (index == -1) {
                        playerPanel.visibility = View.GONE
                        txtNowPlaying.text = ""
                        txtProgress.text = ""
                        seekBar.max = 0
                        seekBar.progress = 0
                    } else {
                        playerPanel.visibility = View.VISIBLE
                        txtNowPlaying.text = title
                        if (!isUserTracking && System.currentTimeMillis() - lastSeekTime > 500 && duration > 0) {
                            seekBar.max = duration.toInt()
                            seekBar.progress = position.toInt()
                            txtProgress.text = "${formatTime(position)} / ${formatTime(duration)}"
                        }
                        if (!initialStateReceived) {
                            initialStateReceived = true
                            updatePauseButton(isPlaying)
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tracks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val list = view.findViewById<ListView>(R.id.listTracks)
        txtNowPlaying = view.findViewById(R.id.txtNowPlaying)
        txtProgress = view.findViewById(R.id.txtProgress)
        seekBar = view.findViewById(R.id.seekBar)
        playerPanel = view.findViewById(R.id.playerPanel)
        btnPauseMini = view.findViewById(R.id.btnPauseMini)

        playerPanel.setOnTouchListener { _, _ -> true }

        adapter = TrackAdapter(requireContext(), tracks)
        list.adapter = adapter

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex

        view.findViewById<ImageButton>(R.id.btnPrevMini).setOnClickListener {
            updatePauseButton(true)
            requireContext().startService(Intent(requireContext(), MusicService::class.java).setAction(MusicService.ACTION_PREV))
        }
        btnPauseMini.setOnClickListener {
            val isCurrentlyPlaying = btnPauseMini.tag as? Boolean ?: true
            updatePauseButton(!isCurrentlyPlaying)
            requireContext().startService(Intent(requireContext(), MusicService::class.java).setAction(MusicService.ACTION_TOGGLE))
        }
        view.findViewById<ImageButton>(R.id.btnNextMini).setOnClickListener {
            updatePauseButton(true)
            requireContext().startService(Intent(requireContext(), MusicService::class.java).setAction(MusicService.ACTION_NEXT))
        }

        view.findViewById<ImageButton>(R.id.btnRestore).setOnClickListener {
            prefs.edit().remove("hidden_tracks").apply()
            scanMusic()
            toast("Все треки возвращены!")
        }
        view.findViewById<ImageButton>(R.id.btnRestore).setOnLongClickListener {
            showRestoreDialog()
            true
        }

        // Обычный клик — играть
        list.setOnItemClickListener { _, _, pos, _ ->
            playTrackAt(pos)
        }

        // Долгий клик — меню
        list.setOnItemLongClickListener { v, _, pos, _ ->
            showTrackMenu(v, pos)
            true
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) txtProgress.text = "${formatTime(p.toLong())} / ${formatTime(s?.max?.toLong() ?: 0L)}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) { isUserTracking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                isUserTracking = false
                lastSeekTime = System.currentTimeMillis()
                requireContext().startService(Intent(requireContext(), MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra(MusicService.EXTRA_SEEK_TO, s?.progress?.toLong() ?: 0L)
                })
            }
        })

        scanMusic()
    }

    override fun onResume() {
        super.onResume()
        initialStateReceived = false

        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_TRACK_CHANGED)
            addAction(MusicService.ACTION_PROGRESS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(trackChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(requireContext(), trackChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
        adapter.notifyDataSetChanged()

        playerPanel.visibility = if (currentTrackIndex != -1) View.VISIBLE else View.GONE

        requireContext().startService(Intent(requireContext(), MusicService::class.java).apply {
            action = MusicService.ACTION_PROGRESS
        })
    }

    override fun onPause() {
        super.onPause()
        try { requireContext().unregisterReceiver(trackChangedReceiver) } catch (e: Exception) {}
    }

    private fun playTrackAt(pos: Int) {
        currentTrackIndex = pos
        adapter.currentIndex = pos
        adapter.notifyDataSetChanged()

        val uris = ArrayList(tracks.map { it.uri.toString() })
        val titles = ArrayList(tracks.map { it.title })
        val i = Intent(requireContext(), MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_LIST
            putStringArrayListExtra(MusicService.EXTRA_URIS, uris)
            putStringArrayListExtra(MusicService.EXTRA_TITLES, titles)
            putExtra(MusicService.EXTRA_INDEX, pos)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(i)
        else requireContext().startService(i)
        toast("▶ ${tracks[pos].title}")
    }

    private fun showTrackMenu(view: View, pos: Int) {
        val track = tracks[pos]
        val popup = android.widget.PopupMenu(requireContext(), view)
        popup.menu.add("Скрыть")
        popup.menu.add("Добавить в плейлист")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Скрыть" -> {
                    hideTrack(track.id)
                    removeTrackFromList(track.id)
                    toast("Скрыто: ${track.title}")
                }
                "Добавить в плейлист" -> {
                    AddToPlaylistBottomSheet.newInstance(track.id) {
                        // Обновляем обложки после добавления
                        lifecycleScope.launch {
                            adapter.refreshCovers(AppDatabase.get(requireContext()).playlistDao())
                        }
                    }.show(parentFragmentManager, "add_to_playlist")
                }
            }
            true
        }
        popup.show()
    }

    fun scanMusic() {
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

        requireContext().contentResolver.query(
            collection, projection, selection, arrayOf(minBytes.toString()),
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
                tracks.add(Track(
                    id,
                    cursor.getString(nameCol).substringBeforeLast("."),
                    cursor.getString(artistCol) ?: "Unknown",
                    ContentUris.withAppendedId(collection, id),
                    cursor.getLong(durationCol),
                    cursor.getLong(albumIdCol)
                ))
            }
        }

        currentTrackIndex = prefs.getInt("current_index", -1)
        adapter.currentIndex = currentTrackIndex
        adapter.notifyDataSetChanged()

        lifecycleScope.launch {
            adapter.refreshCovers(AppDatabase.get(requireContext()).playlistDao())
        }

        toast("Нашёл треков: ${tracks.size}")
    }

    fun removeTrackFromList(id: Long) {
        val removedIndex = tracks.indexOfFirst { it.id == id }
        if (removedIndex == -1) return
        val isRemovingCurrent = (removedIndex == currentTrackIndex)
        tracks.removeAt(removedIndex)
        adapter.notifyDataSetChanged()

        if (isRemovingCurrent) {
            currentTrackIndex = -1
            requireContext().startService(Intent(requireContext(), MusicService::class.java).setAction(MusicService.ACTION_STOP))
            playerPanel.visibility = View.GONE
            return
        }
        if (removedIndex < currentTrackIndex) {
            currentTrackIndex--
            adapter.currentIndex = currentTrackIndex
            adapter.notifyDataSetChanged()
        }
        refreshPlaylistKeepPosition()
    }

    private fun refreshPlaylistKeepPosition() {
        if (tracks.isEmpty() || currentTrackIndex < 0) return
        val uris = ArrayList(tracks.map { it.uri.toString() })
        val titles = ArrayList(tracks.map { it.title })
        val intent = Intent(requireContext(), MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY_LIST
            putStringArrayListExtra(MusicService.EXTRA_URIS, uris)
            putStringArrayListExtra(MusicService.EXTRA_TITLES, titles)
            putExtra(MusicService.EXTRA_INDEX, currentTrackIndex)
            putExtra("KEEP_POSITION", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
    }

    private fun getHiddenTracks(): MutableSet<String> =
        prefs.getStringSet("hidden_tracks", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

    fun hideTrack(id: Long) {
        val set = getHiddenTracks()
        set.add(id.toString())
        prefs.edit().putStringSet("hidden_tracks", set).apply()
    }

    private fun showRestoreDialog() {
        val hiddenIds = getHiddenTracks()
        if (hiddenIds.isEmpty()) { toast("Blacklist пуст"); return }

        val namesMap = mutableMapOf<String, String>()
        requireContext().contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()
                if (hiddenIds.contains(id)) {
                    namesMap[id] = cursor.getString(nameCol).substringBeforeLast(".").replace("_", " ")
                }
            }
        }

        val idsArray = namesMap.keys.toTypedArray()
        val namesArray = namesMap.values.toTypedArray()
        val selectedIds = mutableListOf<String>()

        android.app.AlertDialog.Builder(requireContext())
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

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun updatePauseButton(isPlaying: Boolean) {
        if (!::btnPauseMini.isInitialized) return
        btnPauseMini.tag = isPlaying
        btnPauseMini.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}