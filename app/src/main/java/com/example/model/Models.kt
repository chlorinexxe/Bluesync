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

/**
 * Control messages on the speaker channel (see SpeakerSyncEngine) - a completely separate
 * RFCOMM connection from the main control channel, so speaker mode can't destabilize normal
 * remote-control use. Framed with a 4-byte length prefix (not line-based JSON like
 * BluetoothCommand/BluetoothStateUpdate) because this channel also carries raw audio bytes,
 * which could otherwise be misread as line terminators.
 */
@JsonClass(generateAdapter = true)
data class SpeakerMessage(
    val type: String, // "TRACK_HEADER", "SYNC", "WIFI_DIRECT_INFO"
    val songId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val totalBytes: Long? = null,
    val positionMs: Long? = null,
    val isPlaying: Boolean? = null,
    // WIFI_DIRECT_INFO only: an ad-hoc WiFi group the host just stood up, sent as the very
    // first message on a freshly-accepted Bluetooth speaker connection so the client can
    // silently upgrade to a much faster transport with no shared network required. wifiHostIp
    // is the host's actual address on that group's interface - not assumed/hardcoded, since the
    // conventional "192.168.49.1" group-owner address isn't guaranteed, especially when the
    // host is concurrently also connected to a regular WiFi network.
    val wifiSsid: String? = null,
    val wifiPassphrase: String? = null,
    val wifiHostIp: String? = null
)
