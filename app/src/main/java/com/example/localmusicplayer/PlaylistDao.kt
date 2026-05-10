package com.example.localmusicplayer

import androidx.room.*

@Dao
interface PlaylistDao {

    // --- Плейлисты ---

    @Query("SELECT * FROM playlists ORDER BY id DESC")
    suspend fun getAllPlaylists(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    // --- Треки в плейлисте ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(link: PlaylistTrack)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun getTrackIdsForPlaylist(playlistId: Long): List<Long>

    @Query("SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun getPlaylistIdsForTrack(trackId: Long): List<Long>

    @Query("SELECT * FROM playlists WHERE id IN (SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId)")
    suspend fun getPlaylistsForTrack(trackId: Long): List<Playlist>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackCount(playlistId: Long): Int
}