package org.ndsolotravel.camproz.remote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ndsolotravel.camproz.protocol.*
import org.ndsolotravel.camproz.remote.R
import org.ndsolotravel.camproz.remote.network.RemoteClientManager
import org.ndsolotravel.camproz.remote.settings.FramingGuideMode
import org.ndsolotravel.camproz.remote.settings.RemoteSettingsRepository

@Composable
fun SettingsScreen(
    clientManager: RemoteClientManager,
    settingsRepo: RemoteSettingsRepository,
    onBack: () -> Unit
) {
    val settings by settingsRepo.settings.collectAsState()
    val cameraState by clientManager.cameraState.collectAsState()
    val rttMs by clientManager.rttMs.collectAsState()
    val connectionStatus by clientManager.connectionStatus.collectAsState()

    var activeTab by remember { mutableStateOf(0) } // 0: Camera Host, 1: Monitor, 2: Network/Diag
    var dialogOptionSelector by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1114))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. Top Header Bar (Blackmagic Camera Styling)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Text("←", color = Color(0xFF00E5FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Settings",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            TextButton(onClick = { settingsRepo.resetToDefaults() }) {
                Text("Reset", color = Color(0xFF78909C), fontSize = 13.sp)
            }
        }

        // 2. Navigation Category Tabs (Horizontal Pill Selector)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tabs = listOf("Host Control", "Monitor UI", "Diagnostics")
            tabs.forEachIndexed { index, title ->
                val selected = activeTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            if (selected) Color(0xFF00E5FF) else Color(0xFF1B2026),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { activeTab = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        color = if (selected) Color.Black else Color(0xFFB0BEC5),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3. Tab Contents
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (activeTab) {
                0 -> {
                    // TAB 0: Camera Host Recording & Stream Control
                    Text(
                        "CAMERA HOST RECORDING & STREAM",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SettingsSectionCard {
                        SettingsRow(
                            label = "Monitoring Stream Quality",
                            value = settings.streamQuality,
                            badge = "CAM HOST",
                            onClick = { dialogOptionSelector = "stream_quality" }
                        )

                        SettingsDivider()

                        SettingsRow(
                            label = "Stream Frame Rate Target",
                            value = "${settings.streamFps} FPS",
                            badge = "CAM HOST",
                            onClick = { dialogOptionSelector = "stream_fps" }
                        )

                        SettingsDivider()

                        SettingsRow(
                            label = "Active Camera Sensor",
                            value = if (cameraState.activeFacing == "FRONT") "Front Selfie" else "Rear Main Wide",
                            badge = "CAM HOST",
                            onClick = {
                                val nextFacing = if (cameraState.activeFacing == "FRONT") "BACK" else "FRONT"
                                clientManager.sendCommand(
                                    CommandType.SWITCH_CAMERA,
                                    "{\"facing\":\"$nextFacing\"}"
                                )
                            }
                        )

                        SettingsDivider()

                        SettingsToggleRow(
                            label = "Camera Flash Torch",
                            badge = "CAM HOST",
                            checked = cameraState.isTorchOn,
                            onCheckedChange = { checked ->
                                clientManager.sendCommand(
                                    CommandType.SET_TORCH,
                                    "{\"enabled\":$checked}"
                                )
                            }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "CINEMATIC 3D LOOKS (REC.709)",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SettingsSectionCard {
                        SettingsRow(
                            label = "Active 3D LUT Grade",
                            value = cameraState.activeLutName.replace("_", " ").uppercase(),
                            badge = "CAM HOST",
                            onClick = { dialogOptionSelector = "lut_selector" }
                        )
                    }
                }

                1 -> {
                    // TAB 1: Monitor Preferences (This Device)
                    Text(
                        "FIELD MONITOR & VIEWING AIDS",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SettingsSectionCard {
                        SettingsRow(
                            label = "Framing Grid Overlay",
                            value = when (settings.framingGuide) {
                                FramingGuideMode.NONE -> "Off"
                                FramingGuideMode.THIRDS -> "Rule of Thirds (3×3)"
                                FramingGuideMode.CROSSHAIR -> "Center Crosshair"
                                FramingGuideMode.SAFE_AREA_90 -> "Safe Action (90%)"
                            },
                            badge = "REMOTE",
                            onClick = { dialogOptionSelector = "framing_guide" }
                        )

                        SettingsDivider()

                        SettingsToggleRow(
                            label = "Viewfinder Scale Fit (Letterbox)",
                            badge = "REMOTE",
                            checked = settings.scaleModeFit,
                            onCheckedChange = { settingsRepo.updateScaleModeFit(it) }
                        )

                        SettingsDivider()

                        SettingsToggleRow(
                            label = "Tactile Haptic Feedback",
                            badge = "REMOTE",
                            checked = settings.hapticFeedback,
                            onCheckedChange = { settingsRepo.updateHapticFeedback(it) }
                        )

                        SettingsDivider()

                        SettingsToggleRow(
                            label = "Keep Display Awake",
                            badge = "REMOTE",
                            checked = settings.keepScreenAwake,
                            onCheckedChange = { settingsRepo.updateKeepScreenAwake(it) }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "ON-SCREEN HUD TELEMETRY",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SettingsSectionCard {
                        SettingsToggleRow(
                            label = "Show Live RTT Ping Latency",
                            badge = "REMOTE",
                            checked = settings.showRttTelemetry,
                            onCheckedChange = { settingsRepo.updateShowRttTelemetry(it) }
                        )

                        SettingsDivider()

                        SettingsToggleRow(
                            label = "Show Battery & Storage Readout",
                            badge = "REMOTE",
                            checked = settings.showBatteryStorageHud,
                            onCheckedChange = { settingsRepo.updateShowBatteryStorageHud(it) }
                        )
                    }
                }

                2 -> {
                    // TAB 2: Diagnostics & Connection
                    Text(
                        "CONNECTION TELEMETRY & DIAGNOSTICS",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    SettingsSectionCard {
                        SettingsInfoRow(
                            label = "Connection Status",
                            value = connectionStatus.name
                        )

                        SettingsDivider()

                        SettingsInfoRow(
                            label = "Round-Trip Delay (RTT)",
                            value = "${rttMs} ms"
                        )

                        SettingsDivider()

                        SettingsInfoRow(
                            label = "Host Battery Telemetry",
                            value = "${cameraState.batteryPercent}% ${if (cameraState.isCharging) "(Charging)" else ""}"
                        )

                        SettingsDivider()

                        SettingsInfoRow(
                            label = "Host Free Storage",
                            value = "${cameraState.availableStorageMb / 1024} GB Available"
                        )

                        SettingsDivider()

                        SettingsInfoRow(
                            label = "Protocol Specification",
                            value = "CamPro Z v${SecurityHandshake.CURRENT_PROTOCOL_VERSION}"
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "ABOUT",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    SettingsSectionCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo_remote),
                                contentDescription = "CamPro Z Remote",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("CamPro Z Remote", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text("Version 1.0.0 • Studio Companion", color = Color(0xFF90A4AE), fontSize = 12.sp)
                                Spacer(Modifier.height(2.dp))
                                Text("High-performance low-latency camera controller", color = Color(0xFF00E5FF), fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (connectionStatus == ConnectionStatus.CONNECTED) {
                        Button(
                            onClick = { clientManager.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("Disconnect Camera Session", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // 4. Option Selector Dialogs
    when (dialogOptionSelector) {
        "stream_quality" -> {
            val options = listOf("AUTO", "360P", "540P", "720P")
            OptionPickerDialog(
                title = "Select Stream Quality",
                options = options,
                selected = settings.streamQuality,
                onSelect = {
                    settingsRepo.updateStreamQuality(it)
                    clientManager.sendCommand(
                        CommandType.SET_STREAM_PROFILE,
                        "{\"quality\":\"$it\",\"fps\":${settings.streamFps}}"
                    )
                    dialogOptionSelector = null
                },
                onDismiss = { dialogOptionSelector = null }
            )
        }

        "stream_fps" -> {
            val options = listOf("15", "24", "30")
            OptionPickerDialog(
                title = "Select Target FPS",
                options = options,
                selected = settings.streamFps.toString(),
                onSelect = {
                    val fps = it.toIntOrNull() ?: 30
                    settingsRepo.updateStreamFps(fps)
                    clientManager.sendCommand(
                        CommandType.SET_STREAM_PROFILE,
                        "{\"quality\":\"${settings.streamQuality}\",\"fps\":$fps}"
                    )
                    dialogOptionSelector = null
                },
                onDismiss = { dialogOptionSelector = null }
            )
        }

        "framing_guide" -> {
            val options = listOf(
                FramingGuideMode.NONE.name to "Off",
                FramingGuideMode.THIRDS.name to "Rule of Thirds (3×3)",
                FramingGuideMode.CROSSHAIR.name to "Center Crosshair",
                FramingGuideMode.SAFE_AREA_90.name to "Safe Action (90%)"
            )
            AlertDialog(
                onDismissRequest = { dialogOptionSelector = null },
                containerColor = Color(0xFF161A20),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                title = { Text("Select Framing Guide") },
                text = {
                    Column {
                        options.forEach { (key, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsRepo.updateFramingGuide(FramingGuideMode.valueOf(key))
                                        dialogOptionSelector = null
                                    }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = Color.White, fontSize = 14.sp)
                                if (settings.framingGuide.name == key) {
                                    Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialogOptionSelector = null }) { Text("Cancel") }
                }
            )
        }

        "lut_selector" -> {
            val luts = listOf(
                "identity" to "Identity (Passthrough)",
                "natural" to "Natural Rec.709",
                "travel" to "Travel Vibrant",
                "warm_sunset" to "Warm Sunset",
                "cool_cinema" to "Cool Cinema",
                "mono" to "Monochrome Contrast",
                "vintage_80s" to "Vintage 80s"
            )
            AlertDialog(
                onDismissRequest = { dialogOptionSelector = null },
                containerColor = Color(0xFF161A20),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                title = { Text("Select 3D LUT Color Grade") },
                text = {
                    Column {
                        luts.forEach { (id, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clientManager.sendCommand(
                                            CommandType.SET_LUT,
                                            "{\"lutId\":\"$id\",\"intensity\":1.0}"
                                        )
                                        dialogOptionSelector = null
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = Color.White, fontSize = 14.sp)
                                if (cameraState.activeLutName.equals(id, ignoreCase = true) || cameraState.activeLutName.equals(label, ignoreCase = true)) {
                                    Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialogOptionSelector = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161A1F)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF242C36), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    badge: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            BadgeLabel(badge)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Color(0xFF90A4AE), fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("›", color = Color(0xFF78909C), fontSize = 18.sp)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    badge: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            BadgeLabel(badge)
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF00E5FF),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color(0xFF2C3440)
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Text(value, color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BadgeLabel(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF242C36), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = Color(0xFF78909C),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color(0xFF222933), thickness = 0.8.dp)
}

@Composable
private fun OptionPickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161A20),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(option, color = Color.White, fontSize = 14.sp)
                        if (option == selected) {
                            Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
