package com.example.localmusicplayer

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    // Для выбора обложки при создании/редактировании
    private var pendingPlaylistId: Long? = null
    private var pendingPlaylistName: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val name = pendingPlaylistName ?: return@registerForActivityResult

        lifecycleScope.launch {
            val savedPath = saveImageLocally(uri)
            val db = AppDatabase.get(requireContext())

            val pid = pendingPlaylistId
            if (pid == null) {
                // Создаём новый плейлист с обложкой
                db.playlistDao().insertPlaylist(Playlist(name = name, coverPath = savedPath))
            } else {
                // Обновляем обложку существующего
                val existing = playlists.find { it.id == pid } ?: return@launch
                db.playlistDao().updatePlaylist(existing.copy(coverPath = savedPath))
            }

            pendingPlaylistId = null
            pendingPlaylistName = null
            loadPlaylists()
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
        val input = EditText(requireContext()).apply {
            hint = "Название плейлиста"
            setPadding(48, 24, 48, 24)
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Новый плейлист")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Введи название"); return@setPositiveButton }

                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Обложка плейлиста")
                    .setMessage("Выбрать из галереи?")
                    .setPositiveButton("Выбрать") { _, _ ->
                        pendingPlaylistName = name
                        pendingPlaylistId = null
                        pickImage.launch("image/*")
                    }
                    .setNegativeButton("Пропустить") { _, _ ->
                        lifecycleScope.launch {
                            AppDatabase.get(requireContext()).playlistDao()
                                .insertPlaylist(Playlist(name = name))
                            loadPlaylists()
                        }
                    }
                    .show()
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
                        pendingPlaylistId = playlist.id
                        pendingPlaylistName = playlist.name
                        pickImage.launch("image/*")
                    }
                    2 -> showDeleteDialog(playlist)
                }
            }
            .show()
    }

    private fun showRenameDialog(playlist: Playlist) {
        val input = EditText(requireContext()).apply {
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
            .setMessage("Треки из плейлиста не удалятся с устройства")
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

// --- Адаптер плейлистов ---
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

        // Обложка
        if (playlist.coverPath != null) {
            holder.img.load(File(playlist.coverPath)) {
                placeholder(R.drawable.ic_notification)
                error(R.drawable.ic_notification)
            }
        } else {
            holder.img.setImageResource(R.drawable.ic_notification)
        }

        // Количество треков
        val ctx = holder.itemView.context
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope.launch {
            val count = AppDatabase.get(ctx).playlistDao().getTrackCount(playlist.id)
            withContext(Dispatchers.Main) {
                holder.count.text = "$count треков"
            }
        }

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.itemView.setOnLongClickListener { onLongClick(playlist); true }
    }

    override fun getItemCount() = items.size
}