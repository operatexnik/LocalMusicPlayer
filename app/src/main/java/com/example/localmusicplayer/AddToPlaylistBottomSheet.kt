package com.example.localmusicplayer

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AddToPlaylistBottomSheet : BottomSheetDialogFragment() {

    private var trackId: Long = -1
    private var onAdded: (() -> Unit)? = null

    companion object {
        fun newInstance(trackId: Long, onAdded: () -> Unit): AddToPlaylistBottomSheet {
            return AddToPlaylistBottomSheet().apply {
                this.trackId = trackId
                this.onAdded = onAdded
            }
        }
    }

    // Для выбора обложки при создании плейлиста
    private var pendingPlaylistName: String? = null
    private var pendingCoverPath: String? = null
    private var createDialogCoverView: ImageView? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(requireContext().filesDir, "covers")
            dir.mkdirs()
            val file = File(dir, "playlist_${System.currentTimeMillis()}.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            pendingCoverPath = file.absolutePath
            withContext(Dispatchers.Main) {
                createDialogCoverView?.load(file) {
                    placeholder(R.drawable.ic_notification)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_add_to_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view.parent as? View)?.let {
            it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPlaylistsSheet)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val btnCreate = view.findViewById<View>(R.id.btnCreatePlaylistSheet)
        btnCreate.setOnClickListener { showCreatePlaylistDialog() }

        loadPlaylists(recycler)
    }

    private fun loadPlaylists(recycler: RecyclerView) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.get(requireContext())
            val playlists = db.playlistDao().getAllPlaylists()
            withContext(Dispatchers.Main) {
                recycler.adapter = SheetPlaylistAdapter(playlists) { playlist ->
                    addTrackToPlaylist(playlist)
                }
            }
        }
    }

    private fun addTrackToPlaylist(playlist: Playlist) {
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.get(requireContext()).playlistDao().addTrackToPlaylist(
                PlaylistTrack(playlistId = playlist.id, trackId = trackId)
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Добавлено в «${playlist.name}»", Toast.LENGTH_SHORT).show()
                onAdded?.invoke()
                dismiss()
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
        val coverView = dialogView.findViewById<ImageView>(R.id.imgNewPlaylistCover)
        val nameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPlaylistName)
        createDialogCoverView = coverView
        pendingCoverPath = null

        coverView.setOnClickListener {
            pickImage.launch("image/*")
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Новый плейлист")
            .setView(dialogView)
            .setPositiveButton("Создать") { _, _ ->
                val name = nameInput?.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Введи название", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.get(requireContext())
                    val newId = db.playlistDao().insertPlaylist(
                        Playlist(name = name, coverPath = pendingCoverPath)
                    )
                    // Сразу добавляем трек в новый плейлист
                    db.playlistDao().addTrackToPlaylist(
                        PlaylistTrack(playlistId = newId, trackId = trackId)
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Создан и добавлено в «$name»", Toast.LENGTH_SHORT).show()
                        onAdded?.invoke()
                        dismiss()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}

// Адаптер для списка плейлистов в боттомшите
class SheetPlaylistAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit
) : RecyclerView.Adapter<SheetPlaylistAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgSheetPlaylistCover)
        val name: TextView = view.findViewById(R.id.txtSheetPlaylistName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_sheet, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val playlist = items[position]
        holder.name.text = playlist.name

        if (playlist.coverPath != null) {
            holder.img.load(File(playlist.coverPath)) {
                placeholder(R.drawable.ic_notification)
                error(R.drawable.ic_notification)
            }
        } else {
            holder.img.setImageResource(R.drawable.ic_notification)
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
    }

    override fun getItemCount() = items.size
}