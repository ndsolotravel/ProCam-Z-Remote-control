from pathlib import Path
root = Path(r'D:\Web & Apps\REA Filmic Pro\RearViewPro\android\app\src\main\java\com\rearviewpro\camera')
p = root / 'ui/RemoteSettingsSheet.kt'
t = p.read_text(encoding='utf-8')
start = t.index('    var lanOpen')
end = t.index('    val context', start)
t = t[:start] + t[end:]
t = t.replace('onLan = { lanOpen = true },', 'lanContent = { com.rearviewpro.camera.remote.LanRemoteContent(viewModel) },')
t = t.replace('onLan: () -> Unit = {}', 'lanContent: @Composable () -> Unit = {}')
t = t.replace('CameraBottomSheet("Bluetooth Remote"', 'CameraBottomSheet("Remote control"')
t = t.replace('TextButton(onClick = onLan) { Text("ProCam Z Remote · Wi-Fi / hotspot") }', 'lanContent()\n        HorizontalDivider()\n        Text("Bluetooth shutter buttons", color = Color.LightGray)')
p.write_text(t, encoding='utf-8')
p = root / 'remote/LanRemoteSheet.kt'
t = p.read_text(encoding='utf-8')
t = t.replace('fun LanRemoteSheet(viewModel: CameraViewModel, onDismiss: () -> Unit)', 'fun LanRemoteContent(viewModel: CameraViewModel)')
t = t.replace('    CameraBottomSheet("ProCam Z LAN Remote", onDismiss) {', '    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {')
t = t.replace('        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }\n', '')
t = t.replace('import androidx.compose.ui.unit.dp', 'import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.semantics.contentDescription')
t = t.replace('Switch(status.enabled, { if (it) host.start() else host.stop() })', 'Switch(status.enabled, { if (it) host.start() else host.stop() }, modifier = Modifier.semantics { contentDescription = "Toggle LAN remote" })')
p.write_text(t, encoding='utf-8')
print('Combined LAN and Bluetooth controls in one persistent sheet')
