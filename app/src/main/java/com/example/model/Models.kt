package com.example.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val duration: Long, // in ms
    val uriString: String,
    val albumArtUri: String? = null
)

@JsonClass(generateAdapter = true)
data class BluetoothCommand(
    val command: String, // "PLAY_INDEX", "PAUSE", "RESUME", "NEXT", "PREV", "SEEK", "SKIP_TO_QUEUE_ITEM", "TOGGLE_PLAY", "SET_VOLUME", "TOGGLE_SHUFFLE", "TOGGLE_REPEAT"
    val index: Int? = null,
    val id: String? = null,
    val seekPosition: Long? = null,
    val volume: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class BluetoothStateUpdate(
    val status: String, // "IDLE", "PLAYING", "PAUSED", "STOPPED", "BUFFERING"
    val currentIndex: Int,
    val elapsedTime: Long,
    val duration: Long,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentAlbum: String? = null,
    val currentGenre: String? = null,
    val currentAlbumArt: String? = null,
    val songs: List<Song>? = null,
    val maxVolume: Int? = null,
    val currentVolume: Int? = null,
    val shuffleActive: Boolean? = null,
    val repeatActive: String? = null, // "OFF", "ALL", "ONE"
    // When true, `songs` is one page of the host's full library (requested via
    // REQUEST_LIBRARY_PAGE as the client scrolls) and should be appended to what the client
    // already has, rather than replacing the regular "up next" preview.
    val isLibraryPage: Boolean = false
)
