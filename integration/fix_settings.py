from pathlib import Path
root=Path(__file__).resolve().parents[1]/'campro-z/remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote'
p=root/'ui/SettingsScreen.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('    val cameraState by', '    val caps by clientManager.capabilities.collectAsState()\n    val commandError by clientManager.errorMessage.collectAsState()\n    val cameraState by',1)
t=t.replace('            when (activeTab)', '''            commandError?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(8.dp)) }
            Text("Unavailable host controls are disabled. Format and presets stay on the camera; preview is fixed at 640px / 5 fps.", color = Color.LightGray, fontSize = 12.sp)
            when (activeTab)''',1)
t=t.replace('value = settings.streamQuality,', 'value = cameraState.previewQuality,\n                            enabled = clientManager.supports(CommandType.SET_STREAM_PROFILE),')
t=t.replace('value = "${settings.streamFps} FPS",', 'value = "Host controlled",\n                            enabled = clientManager.supports(CommandType.SET_STREAM_PROFILE),')
t=t.replace('label = "Active Camera Sensor",', 'label = "Active Camera Sensor",\n                            enabled = clientManager.supports(CommandType.SWITCH_CAMERA) && !cameraState.isRecording,')
t=t.replace('label = "Camera Flash Torch",', 'label = "Camera Flash Torch",\n                            enabled = clientManager.supports(CommandType.SET_TORCH),')
t=t.replace('label = "Active 3D LUT Grade",', 'label = "Active 3D LUT Grade",\n                            enabled = clientManager.supports(CommandType.SET_LUT) && !cameraState.isRecording,')
start=t.index('            val luts = listOf(', t.index('"lut_selector" ->'))
end=t.index('            AlertDialog(',start)
t=t[:start]+'''            val lutList = caps?.optJSONArray("luts")
            val luts = if (lutList == null) emptyList() else (0 until lutList.length()).map {
                val lut = lutList.getJSONObject(it); lut.getString("id") to lut.getString("name")
            }
'''+t[end:]
t=t.replace('"{\\"lutId\\":\\"$id\\",\\"intensity\\":1.0}"', 'org.json.JSONObject().put("lutId", id).put("intensity", 1.0).toString()')
t=t.replace('    onClick: () -> Unit\n) {', '    onClick: () -> Unit,\n    enabled: Boolean = true\n) {',1)
t=t.replace('.height(52.dp)\n            .clickable(onClick = onClick)', '.heightIn(min = 52.dp)\n            .clickable(enabled = enabled, onClick = onClick)',1)
t=t.replace('    onCheckedChange: (Boolean) -> Unit\n) {', '    onCheckedChange: (Boolean) -> Unit,\n    enabled: Boolean = true\n) {',1)
t=t.replace('onCheckedChange = onCheckedChange,\n            colors', 'onCheckedChange = onCheckedChange,\n            enabled = enabled,\n            colors',1)
# Let labels wrap and give the value its own bounded area on narrow screens.
t=t.replace('Row(verticalAlignment = Alignment.CenterVertically) {\n            Text(label', 'Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {\n            Text(label')
p.write_text(t,encoding='utf-8')

p=root/'network/RemoteClientManager.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('    val capabilities =', '    var autoReconnect = true\n    val confirmedCommand = kotlinx.coroutines.flow.MutableSharedFlow<CommandType>(extraBufferCapacity = 8)\n    val capabilities =',1)
t=t.replace('if (retries < 3)', 'if (autoReconnect && retries < 3)')
t=t.replace('if (!result.success) _errorMessage.value', 'if (result.success) confirmedCommand.tryEmit(type)\n                                if (!result.success) _errorMessage.value')
p.write_text(t,encoding='utf-8')

p=root/'ui/RemoteMainScreen.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('    val settings by settingsRepo.settings.collectAsState()', '''    val settings by settingsRepo.settings.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val latestHapticEnabled by rememberUpdatedState(settings.hapticFeedback)
    LaunchedEffect(settings.autoReconnect) { clientManager.autoReconnect = settings.autoReconnect }
    LaunchedEffect(clientManager) { clientManager.confirmedCommand.collect {
        if (latestHapticEnabled) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    } }
    DisposableEffect(settings.keepScreenAwake) {
        val window = (context as? android.app.Activity)?.window
        if (settings.keepScreenAwake) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }''',1)
t=t.replace('''                    }, onDoubleTap = {''', '''                    }, onLongPress = { offset ->
                        PreviewTouchMapper.map(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(),
                            liveFrame.width.toFloat(), liveFrame.height.toFloat(), scaleFit)?.let { p ->
                            if (allowed(CommandType.SET_EXPOSURE_POINT)) send(CommandType.SET_EXPOSURE_POINT, "{\\"x\\":${p.x},\\"y\\":${p.y}}")
                        }
                    }, onDoubleTap = {''',1)
marker='''                Text(previewStatus, color = Color.White, fontSize = 11.sp,'''
t=t.replace(marker, '''                if (liveFrame != null && framingGuide != FramingGuideMode.NONE) Canvas(Modifier.fillMaxSize()) {
                    val scale = if (scaleFit) minOf(size.width / liveFrame.width, size.height / liveFrame.height) else maxOf(size.width / liveFrame.width, size.height / liveFrame.height)
                    val w = liveFrame.width * scale; val h = liveFrame.height * scale
                    val left = (size.width - w) / 2; val top = (size.height - h) / 2
                    val color = Color.White.copy(alpha = .5f)
                    when (framingGuide) {
                        FramingGuideMode.THIRDS -> for (fraction in listOf(1f/3, 2f/3)) {
                            drawLine(color, Offset(left + w*fraction, maxOf(0f, top)), Offset(left+w*fraction, minOf(size.height, top+h)))
                            drawLine(color, Offset(maxOf(0f, left), top+h*fraction), Offset(minOf(size.width,left+w), top+h*fraction))
                        }
                        FramingGuideMode.CROSSHAIR -> { drawLine(color, Offset(size.width/2-16, size.height/2), Offset(size.width/2+16,size.height/2)); drawLine(color, Offset(size.width/2,size.height/2-16),Offset(size.width/2,size.height/2+16)) }
                        FramingGuideMode.SAFE_AREA_90 -> drawRect(color, Offset(left+w*.05f,top+h*.05f), Size(w*.9f,h*.9f), style=androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        else -> Unit
                    }
                }
'''+marker,1)
p.write_text(t,encoding='utf-8')
print('Connected monitor preferences and disabled unsupported settings')
