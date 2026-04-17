package com.example.localmusicplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder

class TrackAdapter(
    private val context: Context,
    private val tracks: List<Track>
) : BaseAdapter() {

    var currentIndex: Int = -1

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

        // Текст
        txtTitle.text = track.title
        txtDuration.text = formatDuration(track.duration)

        // Подсветка текущего трека (как было у тебя изначально)
        if (position == currentIndex) {
            rootTrackItem.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        } else {
            rootTrackItem.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Загрузка обложки через Coil
        loadAlbumArt(imgAlbumArt, track.albumId)

        // Клик
        rootTrackItem.setOnClickListener {
            (parent as? android.widget.ListView)?.performItemClick(view, position, getItemId(position))
        }

        // Долгий клик
        rootTrackItem.setOnLongClickListener {
            showPopupMenu(it, track)
            true
        }

        return view
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
            size(128)           // оптимальный размер для списка
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun showPopupMenu(view: View, track: Track) {
        val popup = android.widget.PopupMenu(context, view)
        popup.menu.add("Удалить")
        popup.setOnMenuItemClickListener {
            if (it.title == "Удалить" && context is MainActivity) {
                context.hideTrack(track.id)
                context.removeTrackFromList(track.id)
            }
            true
        }
        popup.show()
    }
}