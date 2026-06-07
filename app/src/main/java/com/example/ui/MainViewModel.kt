package com.example.ui

import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.MyNotificationListener
import com.example.PlaybackService
import com.example.bluetooth.BluetoothConnectionState
import com.example.model.BluetoothCommand
import com.example.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val TAG = "MainViewModel"

class MainViewModel : ViewModel() {

    private val _service = MutableStateFlow<PlaybackService?>(null)
    val service = _service.asStateFlow()

    private val _currentPlaybackPosition = MutableStateFlow(0L)
    val currentPlaybackPosition: StateFlow<Long> = _currentPlaybackPosition.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val activeService = _service.value
                if (activeService != null) {
                    if (activeService.isHostMode.value) {
                        if (activeService.hostUseNotificationHook.value) {
                            val controller = MyNotificationListener.getActiveController()
                            _currentPlaybackPosition.value = controller?.playbackState?.position ?: 0L
                        } else {
                            _currentPlaybackPosition.value = activeService.exoPlayer?.currentPosition ?: 0L
                        }
                    } else {
                        val clientState = activeService.clientPlaybackState.value
                        if (clientState != null) {
                            if (clientState.status == "PLAYING") {
                                val nextPos = _currentPlaybackPosition.value + 250
                                _currentPlaybackPosition.value = if (nextPos > clientState.duration) clientState.duration else nextPos
                            } else {
                                _currentPlaybackPosition.value = clientState.elapsedTime
                            }
                        } else {
                            _currentPlaybackPosition.value = 0L
                        }
                    }
                }
                delay(250)
            }
        }

        // Monitor scans
        viewModelScope.launch {
            _service.collect { currentService ->
                currentService?.let { s ->
                    launch {
                        s.bluetoothEngine.discoveredDevices.collect {
                            _discoveredDevices.value = it
                        }
                    }
                    launch {
                        s.bluetoothEngine.isScanning.collect {
                            _isScanning.value = it
                        }
                    }
                }
            }
        }
    }

    fun setService(playbackService: PlaybackService?) {
        _service.value = playbackService
        if (playbackService != null) {
            refreshPairedDevices()
        }
    }

    fun toggleAppMode(isHost: Boolean) {
        val s = _service.value ?: return
        s.toggleAppMode(isHost)
        _currentPlaybackPosition.value = 0L
    }

    fun toggleHostSource(useNotificationListener: Boolean) {
        val s = _service.value ?: return
        s.toggleHostSource(useNotificationListener)
        _currentPlaybackPosition.value = 0L
    }

    fun refreshPairedDevices() {
        val s = _service.value ?: return
        _pairedDevices.value = s.bluetoothEngine.getPairedDevices()
    }

    fun startBluetoothScan() {
        val s = _service.value ?: return
        s.bluetoothEngine.startDeviceDiscovery()
    }

    fun stopBluetoothScan() {
        val s = _service.value ?: return
        s.bluetoothEngine.stopDeviceDiscovery()
    }

    fun startBluetoothHostServer() {
        val s = _service.value ?: return
        s.bluetoothEngine.startHostServer()
    }

    fun connectToBluetoothDevice(device: BluetoothDevice) {
        val s = _service.value ?: return
        s.bluetoothEngine.connectToDevice(device)
    }

    fun disconnectBluetooth() {
        val s = _service.value ?: return
        s.bluetoothEngine.cleanup()
    }

    fun playSongWithId(songId: String, index: Int) {
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            if (s.hostUseNotificationHook.value) {
                MyNotificationListener.executeCommand("SKIP_TO_QUEUE_ITEM", itemId = songId, index = index)
            } else {
                s.exoPlayer?.seekTo(index, 0)
                s.exoPlayer?.play()
                s.broadcastHostStateToClient()
            }
        } else {
            s.bluetoothEngine.sendCommand(BluetoothCommand("SKIP_TO_QUEUE_ITEM", id = songId, index = index))
        }
    }

    fun togglePlayPause() {
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            if (s.hostUseNotificationHook.value) {
                MyNotificationListener.executeCommand("TOGGLE_PLAY")
            } else {
                val player = s.exoPlayer ?: return
                if (player.isPlaying) player.pause() else player.play()
                s.broadcastHostStateToClient()
            }
        } else {
            val currentStatus = s.clientPlaybackState.value?.status ?: "PAUSED"
            val cmdStr = if (currentStatus == "PLAYING") "PAUSE" else "RESUME"
            s.bluetoothEngine.sendCommand(BluetoothCommand(cmdStr))
        }
    }

    fun playNext() {
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            if (s.hostUseNotificationHook.value) {
                MyNotificationListener.executeCommand("NEXT")
            } else {
                s.exoPlayer?.seekToNextMediaItem()
                s.broadcastHostStateToClient()
            }
        } else {
            s.bluetoothEngine.sendCommand(BluetoothCommand("NEXT"))
        }
    }

    fun playPrevious() {
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            if (s.hostUseNotificationHook.value) {
                MyNotificationListener.executeCommand("PREV")
            } else {
                s.exoPlayer?.seekToPreviousMediaItem()
                s.broadcastHostStateToClient()
            }
        } else {
            s.bluetoothEngine.sendCommand(BluetoothCommand("PREV"))
        }
    }

    fun seekToPosition(positionMs: Long) {
        _currentPlaybackPosition.value = positionMs
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            if (s.hostUseNotificationHook.value) {
                MyNotificationListener.executeCommand("SEEK", seekPosition = positionMs)
            } else {
                s.exoPlayer?.seekTo(positionMs)
                s.broadcastHostStateToClient()
            }
        } else {
            s.bluetoothEngine.sendCommand(BluetoothCommand("SEEK", seekPosition = positionMs))
        }
    }

    fun setVolume(volumeIndex: Int) {
        val s = _service.value ?: return
        if (s.isHostMode.value) {
            try {
                val audioManager = s.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volumeIndex, android.media.AudioManager.FLAG_SHOW_UI)
                s.broadcastHostStateToClient()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed local volume update", e)
            }
        } else {
            s.bluetoothEngine.sendCommand(BluetoothCommand("SET_VOLUME", volume = volumeIndex))
        }
    }
}
