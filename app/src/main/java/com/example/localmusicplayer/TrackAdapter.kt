package com.example.localmusicplayer

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.util.Size
import android.os.Build
import android.content.ContentUris

class TrackAdapter(
    private val context: Context,
    private val tracks: List<Track>
) : BaseAdapter() {

    var currentIndex: Int = -1

    override fun getCount(): Int = tracks.size
    override fun getItem(position: Int): Any = tracks[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_track, parent, false)
        val track = tracks[position]

        val txtTitle = view.findViewById<TextView>(R.id.txtTitle)
        val txtDuration = view.findViewById<TextView>(R.id.txtDuration)
        val layoutControls = view.findViewById<LinearLayout>(R.id.layoutControls)
        val rootTrackItem = view.findViewById<LinearLayout>(R.id.rootTrackItem)
        val imgAlbumArt = view.findViewById<ImageView>(R.id.imgAlbumArt)

        // Внутри getView() оставь только это (остальное удали):

        txtTitle.text = track.title
        txtDuration.text = formatDuration(track.duration)

// Загрузка обложки
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val albumUri = ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    track.albumId
                )
                val bitmap = context.contentResolver.loadThumbnail(albumUri, Size(64, 64), null)
                imgAlbumArt.setImageBitmap(bitmap)
            } else {
                imgAlbumArt.setImageResource(R.drawable.default_cover)
            }
        } catch (e: Exception) {
            imgAlbumArt.setImageResource(R.drawable.default_cover)
        }

// Состояние текущего трека
        if (position == currentIndex) {
            rootTrackItem.setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        } else {
            rootTrackItem.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

// Обычный клик — играть
        rootTrackItem.setOnClickListener {
            (parent as? android.widget.ListView)?.performItemClick(view, position, getItemId(position))
        }

// Долгий клик — удалить
        rootTrackItem.setOnLongClickListener {
            showPopupMenu(it, track)
            true
        }

        return view
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
            if (it.title == "Удалить") {
                if (context is MainActivity) {
                    context.hideTrack(track.id)
                    context.removeTrackFromList(track.id)
                }
            }
            true
        }
        popup.show()
    }
}