package com.example.localmusicplayer

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.error
import coil3.request.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PlaylistsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PlaylistAdapter
    private val playlists = mutableListOf<Playlist>()

    private var pendingPlaylistId: Long? = null
    private var pendingCoverCallback: ((String) -> Unit)? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val savedPath = saveImageLocally(uri)
            pendingCoverCallback?.invoke(savedPath)
            pendingCoverCallback = null
            pendingPlaylistId = null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.recyclerPlaylists)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PlaylistAdapter(playlists,
            onClick = { playlist -> openPlaylist(playlist) },
            onLongClick = { playlist -> showPlaylistMenu(playlist) }
        )
        recycler.adapter = adapter

        view.findViewById<View>(R.id.btnAddPlaylist).setOnClickListener {
            showCreatePlaylistDialog()
        }

        loadPlaylists()
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists()
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            val db = AppDatabase.get(requireContext())
            val list = db.playlistDao().getAllPlaylists()
            withContext(Dispatchers.Main) {
                playlists.clear()
                playlists.addAll(list)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_playlist, null)
        val coverView = dialogView.findViewById<ImageView>(R.id.imgNewPlaylistCover)
        val nameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPlaylistName)

        var selectedCoverPath: String? = null

        coverView.setOnClickListener {
            pendingCoverCallback = { path ->
                selectedCoverPath = path
                coverView.load(File(path)) {
                    placeholder(R.drawable.ic_notification)
                }
                // Убираем паддинг когда есть обложка
                coverView.setPadding(0, 0, 0, 0)
            }
            pickImage.launch("image/*")
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Новый плейлист")
            .setView(dialogView)
            .setPositiveButton("Создать") { _, _ ->
                val name = nameInput?.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        AppDatabase.get(requireContext()).playlistDao()
                            .insertPlaylist(Playlist(name = name, coverPath = selectedCoverPath))
                        loadPlaylists()
                    }
                } else {
                    toast("Введи название")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showPlaylistMenu(playlist: Playlist) {
        val options = arrayOf("Переименовать", "Сменить обложку", "Удалить")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(playlist)
                    1 -> {
                        pendingCoverCallback = { path ->
                            lifecycleScope.launch {
                                AppDatabase.get(requireContext()).playlistDao()
                                    .updatePlaylist(playlist.copy(coverPath = path))
                                loadPlaylists()
                            }
                        }
                        pickImage.launch("image/*")
                    }
                    2 -> showDeleteDialog(playlist)
                }
            }
            .show()
    }

    private fun showRenameDialog(playlist: Playlist) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(playlist.name)
            setPadding(48, 24, 48, 24)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Переименовать")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    AppDatabase.get(requireContext()).playlistDao()
                        .updatePlaylist(playlist.copy(name = newName))
                    loadPlaylists()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteDialog(playlist: Playlist) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Удалить «${playlist.name}»?")
            .setMessage("Треки с устройства не удалятся")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(requireContext()).playlistDao().deletePlaylist(playlist)
                    loadPlaylists()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openPlaylist(playlist: Playlist) {
        val fragment = PlaylistDetailFragment.newInstance(playlist.id, playlist.name)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private suspend fun saveImageLocally(uri: Uri): String {
        return withContext(Dispatchers.IO) {
            val dir = File(requireContext().filesDir, "covers")
            dir.mkdirs()
            val file = File(dir, "playlist_${System.currentTimeMillis()}.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}

class PlaylistAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgPlaylistCover)
        val name: TextView = view.findViewById(R.id.txtPlaylistName)
        val count: TextView = view.findViewById(R.id.txtTrackCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
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
            holder.img.setPadding(0, 0, 0, 0)
        } else {
            holder.img.setImageResource(R.drawable.ic_notification)
            val p = holder.img.context.resources.getDimensionPixelSize(
                android.R.dimen.app_icon_size
            ) / 4
            holder.img.setPadding(p, p, p, p)
        }

        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope.launch {
            val count = AppDatabase.get(holder.itemView.context).playlistDao().getTrackCount(playlist.id)
            withContext(Dispatchers.Main) {
                holder.count.text = "$count треков"
            }
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener { onLongClick(playlist); true }
    }

    override fun getItemCount() = items.size
}