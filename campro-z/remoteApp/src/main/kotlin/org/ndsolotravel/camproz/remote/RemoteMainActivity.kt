package org.ndsolotravel.camproz.remote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.ndsolotravel.camproz.remote.network.RemoteClientManager
import org.ndsolotravel.camproz.remote.ui.RemoteMainScreen

val DarkStudioColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    surface = Color(0xFF161A20),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFFB0BEC5),
    background = Color(0xFF0C0E12),
    onBackground = Color.White
)

class RemoteMainActivity : ComponentActivity() {

    private lateinit var clientManager: RemoteClientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        clientManager = RemoteClientManager(this)
        clientManager.startDiscovery()

        setContent {
            MaterialTheme(colorScheme = DarkStudioColors) {
                RemoteMainScreen(clientManager = clientManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clientManager.release()
    }
}
