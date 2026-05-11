package com.example.localmusicplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TrackAdapter(
    private val context: Context,
    private val tracks: List<Track>
) : BaseAdapter() {

    var currentIndex: Int = -1

    // trackId -> список обложек плейлистов (пути к файлам или null для дефолтной)
    private val trackCovers = mutableMapOf<Long, List<String?>>()
    // trackId -> текущий индекс обложки
    private val coverIndex = mutableMapOf<Long, Int>()

    private val rotationHandler = Handler(Looper.getMainLooper())
    private var rotationRunnable: Runnable? = null

    override fun getCount(): Int = tracks.size
    override fun getItem(position: Int): Any = tracks[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_track, parent, false)

        val track = tracks[position]
        val txtTitle = view.findViewById<TextView>(R.id.txtTitle)
        val txtDuration = view.findViewById<TextView>(R.id.txtDuration)
        val rootTrackItem = view.findViewById<LinearLayout>(R.id.rootTrackItem)
        val imgAlbumArt = view.findViewById<ImageView>(R.id.imgAlbumArt)

        txtTitle.text = track.title
        txtDuration.text = formatDuration(track.duration)

        if (position == currentIndex) {
            rootTrackItem.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        } else {
            rootTrackItem.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        loadCoverForTrack(imgAlbumArt, track)

        return view
    }

    private fun loadCoverForTrack(imageView: ImageView, track: Track) {
        val covers = trackCovers[track.id]

        if (covers.isNullOrEmpty()) {
            // Нет плейлистов — грузим обложку альбома как раньше
            loadAlbumArt(imageView, track.albumId)
            return
        }

        val idx = coverIndex[track.id] ?: 0
        val coverPath = covers[idx % covers.size]

        if (coverPath != null) {
            imageView.load(File(coverPath)) {
                crossfade(300)
                placeholder(R.drawable.default_cover)
                error(R.drawable.default_cover)
            }
        } else {
            imageView.setImageResource(R.drawable.ic_notification)
        }
    }

    private fun loadAlbumArt(imageView: ImageView, albumId: Long) {
        val albumUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                albumId
            )
        } else {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        }

        imageView.load(albumUri) {
            crossfade(200)
            placeholder(R.drawable.default_cover)
            error(R.drawable.default_cover)
            size(128)
        }
    }

    // Загружает обложки плейлистов для всех треков из БД
    fun refreshCovers(dao: PlaylistDao) {
        CoroutineScope(Dispatchers.IO).launch {
            val newCovers = mutableMapOf<Long, List<String?>>()

            for (track in tracks) {
                val playlists = dao.getPlaylistsForTrack(track.id)
                if (playlists.isNotEmpty()) {
                    newCovers[track.id] = playlists.map { it.coverPath }
                }
            }

            withContext(Dispatchers.Main) {
                trackCovers.clear()
                trackCovers.putAll(newCovers)
                notifyDataSetChanged()
                startCoverRotation()
            }
        }
    }

    // Запускает ротацию обложек раз в минуту
    private fun startCoverRotation() {
        rotationRunnable?.let { rotationHandler.removeCallbacks(it) }

        rotationRunnable = object : Runnable {
            override fun run() {
                var changed = false
                for ((trackId, covers) in trackCovers) {
                    if (covers.size > 1) {
                        coverIndex[trackId] = ((coverIndex[trackId] ?: 0) + 1) % covers.size
                        changed = true
                    }
                }
                if (changed) notifyDataSetChanged()
                rotationHandler.postDelayed(this, 60_000) // раз в минуту
            }
        }
        rotationHandler.postDelayed(rotationRunnable!!, 60_000)
    }

    fun stopRotation() {
        rotationRunnable?.let { rotationHandler.removeCallbacks(it) }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}