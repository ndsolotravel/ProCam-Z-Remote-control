from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
p = root / 'campro-z/remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote/ui/RemoteMainScreen.kt'
t = p.read_text(encoding='utf-8')
start = t.index('@Composable\nfun LiveMonitorView')
end = t.index('@Composable\nfun ConnectionHubView', start)
t = t[:start] + (root / 'integration/LiveMonitorView.txt').read_text(encoding='utf-8') + '\n' + t[end:]
t = t.replace('clientManager.connect(host, port, pin = pin)', 'clientManager.connect(host, port, streamPort = port + 1, pin = pin)')
t = t.replace('onClick = { onConnectCamera(cam) }', 'onClick = { manualHost = cam.host; manualPort = cam.port.toString(); showManualDialog = true }')
t = t.replace('Column(verticalArrangement = Arrangement.spacedBy(10.dp))', 'Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp))')
t = re.sub(r'\.fillMaxWidth\(\)(\s*)\.widthIn\(max = 480.dp\)', r'.widthIn(max = 480.dp)\1.fillMaxWidth()', t)
p.write_text(t, encoding='utf-8')

p = root / 'campro-z/remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote/RemoteMainActivity.kt'
t = p.read_text(encoding='utf-8').replace('    override fun onDestroy()', '    override fun onStop() {\n        clientManager.disconnect()\n        super.onStop()\n    }\n\n    override fun onDestroy()')
p.write_text(t, encoding='utf-8')

p = root / 'campro-z/remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote/network/RemoteClientManager.kt'
t = p.read_text(encoding='utf-8').replace('object : WebSocketClient(uri) {', 'object : WebSocketClient(uri, java.util.Collections.emptyMap<String, String>(), 5000) {').replace('        socket.connectTimeout = 5000\n','')
p.write_text(t, encoding='utf-8')
print('Updated capability-driven monitor and connection layout')
