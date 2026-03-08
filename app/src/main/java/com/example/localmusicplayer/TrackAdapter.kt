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
        val layoutControls = view.findViewById<LinearLayout>(R.id.layoutControls)
        val btnPrevMini = view.findViewById<ImageButton>(R.id.btnPrevMini)
        val btnPauseMini = view.findViewById<ImageButton>(R.id.btnPauseMini)
        val btnNextMini = view.findViewById<ImageButton>(R.id.btnNextMini)

        val track = tracks[position]
        txtTitle.text = track.title

        if (position == currentIndex) {
            txtIndicator.text = "▶"
            layoutControls.visibility = View.VISIBLE
        } else {
            txtIndicator.text = ""
            layoutControls.visibility = View.GONE
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
}