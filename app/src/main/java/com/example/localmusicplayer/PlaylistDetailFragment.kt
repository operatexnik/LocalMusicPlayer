package com.example.localmusicplayer

import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailFragment : Fragment() {

    companion object {
        fun newInstance(playlistId: Long, playlistName: String): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong("playlist_id", playlistId)
                    putString("playlist_name", playlistName)
                }
            }
        }
    }

    private val playlistId by lazy { arguments?.getLong("playlist_id") ?: 0L }
    private val playlistName by lazy { arguments?.getString("playlist_name") ?: "Плейлист" }

    private val tracks = mutableListOf<Track>()
    private lateinit var adapter: TrackAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.txtPlaylistTitle).text = playlistName

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val list = view.findViewById<ListView>(R.id.listPlaylistTracks)
        adapter = TrackAdapter(requireContext(), tracks)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, pos, _ ->
            playPlaylistFrom(pos)
        }

        list.setOnItemLongClickListener { v, _, pos, _ ->
            val track = tracks[pos]
            val popup = android.widget.PopupMenu(requireContext(), v)
            popup.menu.add("Удалить из плейлиста")
            popup.setOnMenuItemClickListener {
                lifecycleScope.launch {
                    AppDatabase.get(requireContext()).playlistDao()
                        .removeTrackFromPlaylist(playlistId, track.id)
                    loadTracks()
                    withContext(Dispatchers.Main) { toast("Удалено из плейлиста") }
                }
                true
            }
            popup.show()
            true
        }

        loadTracks()
    }

    private fun loadTracks() {
        lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val trackIds = db.playlistDao().getTrackIdsForPlaylist(playlistId)

            if (trackIds.isEmpty()) {
                withContext(Dispatchers.Main) {
                    tracks.clear()
                    adapter.notifyDataSetChanged()
                }
                return@launch
            }

            // Загружаем треки из MediaStore по ID
            val foundTracks = mutableListOf<Track>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )

            val idSet = trackIds.toHashSet()

            requireContext().contentResolver.query(
                collection, projection, null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (id in idSet) {
                        foundTracks.add(Track(
                            id,
                            cursor.getString(nameCol).substringBeforeLast("."),
                            cursor.getString(artistCol) ?: "Unknown",
                            ContentUris.withAppendedId(collection, id),
                            cursor.getLong(durCol),
                            cursor.getLong(albCol)
                        ))
                    }
                }
            }

            // Сортируем в том же порядке что добавляли
            val sorted = trackIds.mapNotNull { id -> foundTracks.find { it.id == id } }

            withContext(Dispatchers.Main) {
                tracks.clear()
                tracks.addAll(sorted)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun playPlaylistFrom(pos: Int) {
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

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}