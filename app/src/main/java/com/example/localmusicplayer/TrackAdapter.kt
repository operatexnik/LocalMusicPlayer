package com.example.localmusicplayer

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton

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

        val txtIndicator = view.findViewById<TextView>(R.id.txtIndicator)
        val txtTitle = view.findViewById<TextView>(R.id.txtTitle)
        val rootTrackItem = view.findViewById<LinearLayout>(R.id.rootTrackItem)
        val txtDuration = view.findViewById<TextView>(R.id.txtDuration)
        val layoutControls = view.findViewById<LinearLayout>(R.id.layoutControls)
        val btnPrevMini = view.findViewById<ImageButton>(R.id.btnPrevMini)
        val btnPauseMini = view.findViewById<ImageButton>(R.id.btnPauseMini)
        val btnNextMini = view.findViewById<ImageButton>(R.id.btnNextMini)

        val track = tracks[position]
        txtTitle.text = track.title
        txtDuration.text = formatDuration(track.duration)

        if (position == currentIndex) {
            txtIndicator.text = "▶"
            layoutControls.visibility = View.VISIBLE
            rootTrackItem.setBackgroundColor(0x22FFFFFF)
        } else {
            txtIndicator.text = ""
            layoutControls.visibility = View.GONE
            rootTrackItem.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        btnPrevMini.setOnClickListener {
            context.startService(
                Intent(context, MusicService::class.java)
                    .setAction(MusicService.ACTION_PREV)
            )
        }

        btnPauseMini.setOnClickListener {
            context.startService(
                Intent(context, MusicService::class.java)
                    .setAction(MusicService.ACTION_TOGGLE)
            )
        }

        btnNextMini.setOnClickListener {
            context.startService(
                Intent(context, MusicService::class.java)
                    .setAction(MusicService.ACTION_NEXT)
            )
        }

        return view
    }
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}