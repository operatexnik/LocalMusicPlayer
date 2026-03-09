package com.example.localmusicplayer

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long
)