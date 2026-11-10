from pathlib import Path
root = Path(r'D:\Web & Apps\REA Filmic Pro\RearViewPro\android\app\src\main\java\com\rearviewpro\camera')
p = root / 'ui/CameraViewModel.kt'
t = p.read_text(encoding='utf-8')
t = t.replace('if (m.isManualFocusSupported) commands += listOf("SET_MANUAL_FOCUS", "LOCK_FOCUS")', 'if (m.isManualFocusSupported) commands += "SET_MANUAL_FOCUS"\n        if (cap?.supportsTouchAf == true && m.stabilizationMode != StabilizationMode.ON) commands += "LOCK_FOCUS"')
t = t.replace('"SET_FOCUS_POINT", "SET_EXPOSURE_POINT" -> {', '''"LOCK_FOCUS" -> {
                val lock = payload.getBoolean("locked")
                if (lock && !_manualControlsState.value.isAfLocked) {
                    if (_manualControlsState.value.focusMode == FocusMode.LOCKED) setFocusMode(FocusMode.AUTO)
                    val point = _manualControlsState.value.focusMeteringPoint ?: (_viewWidth.value / 2f to _viewHeight.value / 2f)
                    setFocusMeteringPoint(point.first, point.second, lock = true)
                    while (_manualControlsState.value.focusMeteringState == FocusMeteringState.FOCUSING) kotlinx.coroutines.delay(30)
                    require(_manualControlsState.value.isAfLocked) { "Camera could not acquire focus lock." }
                } else if (!lock && _manualControlsState.value.focusMode == FocusMode.LOCKED) {
                    val next = _manualControlsState.value.copy(focusMode = FocusMode.AUTO, isAfLocked = false)
                    cameraManager.updateManualSettings(next).getOrThrow()
                    _manualControlsState.value = next
                }
            }
            "SET_FOCUS_POINT", "SET_EXPOSURE_POINT" -> {''')
t = t.replace('if (type == "SET_FOCUS_POINT") {\n                    stopSubjectTracking()', '''if (type == "SET_FOCUS_POINT") {
                    require(manualControlsState.value.stabilizationMode != StabilizationMode.ON) { "Disable electronic stabilization for precise touch focus." }
                    require(manualControlsState.value.focusMode != FocusMode.LOCKED) { "Unlock focus before selecting a new point." }
                    stopSubjectTracking()''')
start = t.index('                    "LOCK_FOCUS" -> {', t.index('suspend fun executeLanCommand'))
end = t.index('                    "SET_MANUAL_FOCUS"', start)
t = t[:start]+t[end:]
t = t.replace('require(preset != WhiteBalancePreset.LOCKED) { "Use local white balance lock." }', '''val mode = when (preset) {
                            WhiteBalancePreset.AUTO -> 1
                            WhiteBalancePreset.DAYLIGHT -> 5
                            WhiteBalancePreset.CLOUDY -> 6
                            WhiteBalancePreset.SHADE -> 8
                            WhiteBalancePreset.TUNGSTEN -> 2
                            WhiteBalancePreset.FLUORESCENT -> 3
                            WhiteBalancePreset.LOCKED -> -1
                        }
                        require(capabilities.value[currentLens.value]?.supportedAwbModes?.contains(mode) == true) { "White balance mode unavailable on this lens." }''')
p.write_text(t, encoding='utf-8')
p = root / 'remote/LanRemoteHost.kt'
t = p.read_text(encoding='utf-8').replace('import kotlinx.coroutines.*', 'import kotlinx.coroutines.*\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock')
t = t.replace('private val inFlight', 'private val commandMutex = Mutex()\n    private val inFlight')
t = t.replace('withTimeout(15000) { camera.executeLanCommand(type, json.getJSONObject("payload")) }', '''withTimeout(15000) { commandMutex.withLock {
                require(epoch == generation && activeSession == session && conn == controller) { "Session ended" }
                camera.executeLanCommand(type, json.getJSONObject("payload"))
            } }''')
p.write_text(t, encoding='utf-8')
print('Refined focus acquisition, command serialization and device validation')
