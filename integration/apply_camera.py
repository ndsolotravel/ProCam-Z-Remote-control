from pathlib import Path
import shutil

root = Path(r'D:\Web & Apps\REA Filmic Pro\RearViewPro\android')
stage = Path(__file__).parent / 'camera'
java = root / 'app/src/main/java/com/rearviewpro/camera'
def replace(path, old, new):
    text = path.read_text(encoding='utf-8')
    if new in text:
        return
    assert old in text, (str(path), old[:100])
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

(java / 'remote').mkdir(exist_ok=True)
for name in ('LanRemoteHost.kt', 'LanRemoteSheet.kt'):
    shutil.copyfile(stage / name, java / 'remote' / name)
vm = java / 'ui/CameraViewModel.kt'
fragment = (stage / 'CameraLanAdapter.txt').read_text(encoding='utf-8').replace('_viewHeight.value, false)', '_viewHeight.value)')
text = vm.read_text(encoding='utf-8')
if 'suspend fun executeLanCommand' not in text:
    index = text.rfind('\n}')
    vm.write_text(text[:index] + '\n' + fragment + text[index:], encoding='utf-8')
replace(vm, 'fun onAppBackgrounded() {', 'fun onAppBackgrounded() {\n        lanRemote.stop()')
replace(vm, 'override fun onCleared() {', 'override fun onCleared() {\n        lanRemote.stop()')
screen = java / 'ui/CameraScreen.kt'
replace(screen, 'viewModel.onPreviewFrameRendered()', 'viewModel.onPreviewFrameRendered()\n                                    textureViewInstance?.let { viewModel.lanRemote.capture(it) }')
replace(screen, 'val isRecording = recordingState is RecordingState.Recording', 'com.rearviewpro.camera.remote.LanApprovalDialog(viewModel)\n    val isRecording = recordingState is RecordingState.Recording')
sheet = java / 'ui/RemoteSettingsSheet.kt'
replace(sheet, 'val context = LocalContext.current', '''var lanOpen by remember { mutableStateOf(false) }
    if (lanOpen) {
        com.rearviewpro.camera.remote.LanRemoteSheet(viewModel) { lanOpen = false }
        return
    }
    val context = LocalContext.current''')
replace(sheet, 'RemoteAssignmentsSheet(creator.remoteShutterEnabled,', 'RemoteAssignmentsSheet(creator.remoteShutterEnabled,\n        onLan = { lanOpen = true },')
replace(sheet, 'onKeyReceived: (Int) -> Unit = {}', 'onKeyReceived: (Int) -> Unit = {},\n    onLan: () -> Unit = {}')
replace(sheet, 'Text("Receives supported volume,', 'TextButton(onClick = onLan) { Text("ProCam Z Remote · Wi-Fi / hotspot") }\n        Text("Receives supported volume,')
replace(root / 'app/build.gradle.kts', 'dependencies {', 'dependencies {\n    implementation("org.java-websocket:Java-WebSocket:1.5.7")\n    implementation("com.google.zxing:core:3.5.3")')
manifest = root / 'app/src/main/AndroidManifest.xml'
replace(manifest, '<uses-permission android:name="android.permission.CAMERA" />', '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n    <uses-permission android:name="android.permission.CAMERA" />')
print('Applied LAN integration to', root)
