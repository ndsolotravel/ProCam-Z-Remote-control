package org.ndsolotravel.apps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.ndsolotravel.apps.camera.CameraEngine
import org.ndsolotravel.apps.camera.CameraGlRenderer
import org.ndsolotravel.apps.camera.LutManager
import org.ndsolotravel.apps.network.CamProZHostServer
import org.ndsolotravel.apps.ui.CamProZCameraScreen

class MainActivity : ComponentActivity() {

    private lateinit var lutManager: LutManager
    private lateinit var glRenderer: CameraGlRenderer
    private lateinit var cameraEngine: CameraEngine
    private lateinit var hostServer: CamProZHostServer

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startCameraAndHost()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lutManager = LutManager(this)
        glRenderer = CameraGlRenderer()
        cameraEngine = CameraEngine(this, glRenderer, lutManager)
        hostServer = CamProZHostServer(this, cameraEngine)

        setContent {
            androidx.compose.material3.MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                    onPrimary = androidx.compose.ui.graphics.Color.Black,
                    surface = androidx.compose.ui.graphics.Color(0xFF161A20),
                    onSurface = androidx.compose.ui.graphics.Color.White,
                    background = androidx.compose.ui.graphics.Color(0xFF0C0E12),
                    onBackground = androidx.compose.ui.graphics.Color.White
                )
            ) {
                CamProZCameraScreen(
                    cameraEngine = cameraEngine,
                    hostServer = hostServer
                )
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCameraAndHost()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCameraAndHost() {
        hostServer.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        hostServer.stop()
        cameraEngine.release()
        glRenderer.release()
    }
}
