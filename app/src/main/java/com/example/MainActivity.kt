package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.ui.MainViewModel
import com.example.ui.PremiumPlayerUI
import com.example.ui.theme.MyApplicationTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var boundService: PlaybackService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, serviceIBinder: IBinder?) {
            val binder = serviceIBinder as PlaybackService.LocalBinder
            val service = binder.getService()
            boundService = service
            isBound = true
            viewModel.setService(service)
            Log.d(TAG, "Service Bound and connected successfully")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
            viewModel.setService(null)
            Log.d(TAG, "Service Disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the service sticky so it continues offline playback in background
        val intent = Intent(this, PlaybackService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting service", e)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PremiumPlayerUI(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            viewModel.setService(null)
        }
    }
}
