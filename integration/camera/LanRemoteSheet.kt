package com.rearviewpro.camera.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.rearviewpro.camera.ui.CameraBottomSheet
import com.rearviewpro.camera.ui.CameraViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LanRemoteSheet(viewModel: CameraViewModel, onDismiss: () -> Unit) {
    val host = viewModel.lanRemote
    val status by host.status.collectAsState()
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(status.qr, status.ready) {
        qr = if (!status.ready || status.qr.isEmpty()) null else withContext(Dispatchers.Default) {
            val bits = MultiFormatWriter().encode(status.qr, BarcodeFormat.QR_CODE, 420, 420)
            Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(420 * 420) { i -> if (bits[i % 420, i / 420]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 420, 0, 0, 420, 420)
            }
        }
    }
    CameraBottomSheet("ProCam Z LAN Remote", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable LAN remote", Modifier.weight(1f))
            Switch(status.enabled, { if (it) host.start() else host.stop() })
        }
        Text(when { status.client != null -> "Connected: ${status.client}"; status.ready -> "Ready to pair"; status.enabled -> "Starting servers…"; else -> "Disconnected" })
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Use the same Wi-Fi or hotspot on both devices. Keep this camera app open. Approve the connection on this device.")
        if (status.ready && status.client == null) {
            qr?.let { Image(it.asImageBitmap(), "Scan with ProCam Z Remote", Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
            Text("${status.address}:8989 · Preview port 8990")
            Text("PIN: ${status.pin} · Pairing expires after 5 minutes")
            Text("In ProCam Z Remote, scan this QR or enter the IP address and PIN manually.")
        }
        if (status.client != null) Button(onClick = host::revoke) { Text("Revoke remote access") }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
    }
}

@Composable
fun LanApprovalDialog(viewModel: CameraViewModel) {
    val request by viewModel.lanRemote.approval.collectAsState()
    request?.let { pending ->
        AlertDialog(onDismissRequest = { pending.decide(false) },
            title = { Text("Allow remote camera control?") },
            text = { Text("${pending.name} requests access to the camera, recording controls and live preview.") },
            confirmButton = { TextButton(onClick = { pending.decide(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { pending.decide(false) }) { Text("Decline") } })
    }
}
