# CamPro Z Remote Control Protocol Specification
**Document Version:** 1.0.0  
**Target Applications:** CamPro Z (`org.ndsolotravel.apps`) & CamPro Z Remote (`org.ndsolotravel.camproz.remote`)  
**Network Scope:** Local Offline WiFi & Mobile Hotspot (Zero Cloud / Zero Internet Dependencies)

---

## 1. Architectural Overview

CamPro Z Remote enables zero-latency, secure monitoring and camera parameter manipulation across two Android devices communicating exclusively over local networking (Wi-Fi LAN or cellular hotspot).

The architecture cleanly divides communication into two isolated, high-performance transports:

```
+-------------------------------------------------------------------+
|                        CamPro Z Host                              |
|                                                                   |
|  [ Camera2 Engine ]  <--->  [ OpenGL ES 3.0 Renderer (3D LUTs) ]  |
|          |                                 |                      |
|  [ MediaRecorder ]                         v (Rate-limited Tap)   |
|  (Local 1080p Master)            [ JPEG Compression ]             |
|          ^                                 |                      |
|          | Command Channel                 | Live Monitor Stream  |
|          v (TCP :8989)                     v (TCP :8990)          |
+----------|---------------------------------|----------------------+
           |                                 |
           | WebSocket (JSON)                | WebSocket (Binary)   |
           |                                 |
+----------|---------------------------------|----------------------+
|          v                                 v                      |
|  [ RemoteClientManager ]        [ Viewfinder Texture / Canvas ]   |
|          |                                                        |
|  [ Normalized Touch Transform ]                                   |
|  [ Compose Dark Studio UI ]                                       |
|                                                                   |
|                      CamPro Z Remote Companion                    |
+-------------------------------------------------------------------+
```

### Transport Channels

| Channel | Default Port | Transport | Payload Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Command & State** | `8989` | WebSocket / TCP | JSON text | Bi-directional RPC: Commands, acknowledgments, state snapshots, capabilities, and heartbeats. |
| **Live Stream** | `8990` | WebSocket / TCP | Raw Binary (JPEG) | Unidirectional low-latency video feed sent directly from the OpenGL ES 3.0 frame tap. |

---

## 2. Zero-Trust Security & Pairing Lifecycle

All communications enforce zero-trust local authentication. The host will **never** stream frames or execute commands from an unauthenticated client.

### 2.1 Service Discovery (mDNS / Android NSD)
* **Service Type:** `_camproz._tcp.`
* **Service Name:** `CamProZ-XXXX` (where `XXXX` is a randomized alphanumeric suffix).
* **Port Advertised:** `8989`
* **Network Isolation Handling:** When access points enforce client isolation or block multicast mDNS packets, manual IP and port entry is supported as an immediate fallback.

### 2.2 Pairing Credentials & QR Payload
When pairing mode is opened on the host camera screen:
1. The host derives a cryptographically secure 16-byte hex token (`sessionToken`).
2. The host generates a 6-digit numeric PIN code (`pinCode`, e.g., `582914`).
3. A QR code payload URI is generated:
   ```
   camproz://pair?host=<IP>&cmd=<PORT>&stream=<PORT>&token=<TOKEN>&pin=<PIN>&name=<DEVICE_NAME>
   ```
4. The QR code is displayed on the camera host screen alongside the numerical PIN.

### 2.3 Authentication Handshake Sequence
```
Remote Companion                       CamPro Z Camera Host
       |                                         |
       |----- (1) Connect WS (ws://host:8989) -->|
       |                                         |
       |----- (2) Send PAIR Handshake ---------->|
       |      Payload: {clientName, token, pin,  |
       |                signature}               |
       |                                         |
       |                                  [ Verify Signature ]
       |                                  [ Prompt Host Operator ]
       |                                  "Allow [Client] to control?"
       |                                         |
       |                                  [ Operator: APPROVE ]
       |                                         |
       |<---- (3) Send PAIR_APPROVED ------------|
       |      Payload: {sessionId, caps, state}  |
       |                                         |
       |----- (4) Connect Stream (ws://host:8990)|
       |<---- (5) Stream Binary JPEG Frames -----|
       |                                         |
       |<---> (6) Bi-directional Commands <----->|
```

### 2.4 Mutual Exclusion & Revocation
* **Single Controller Enforcement:** Only one active controlling companion is allowed at any time. Any new connection attempt while a client is active is rejected with error `BUSY_ANOTHER_CLIENT_CONNECTED`.
* **Host Revocation:** The host operator has a prominent red `Revoke Remote Access` button in the pairing dialog. Clicking this immediately terminates both WebSocket sockets and resets the camera state.

---

## 3. Command & Message Schemas

All command and state messages are sent over WebSocket port `8989` formatted as UTF-8 JSON.

### 3.1 Command Message Envelope
```json
{
  "protocolVersion": "1.0",
  "sessionId": "4a7f28bc...",
  "commandId": "cmd-1700000000-001",
  "type": "START_RECORDING",
  "payloadJson": "{}",
  "timestampMs": 1700000000123
}
```

### 3.2 Command Execution Result Envelope
```json
{
  "commandId": "cmd-1700000000-001",
  "success": true,
  "error": null,
  "executionTimeMs": 14
}
```

---

## 4. Supported Commands and Payloads

### 4.1 Recording Control
* **`START_RECORDING`**
  - Payload: `{}`
  - Effect: Starts high-bitrate 1080p SDR MP4 recording to Android MediaStore.
* **`STOP_RECORDING`**
  - Payload: `{}`
  - Effect: Finalizes current MP4 file, flushes AAC audio tracks, and updates gallery.

### 4.2 Optics & Focus Control
* **`SET_ZOOM`**
  - Payload: `{"zoom": 2.5}`
  - Clamped between `capabilities.minZoom` and `capabilities.maxZoom`.
* **`SET_FOCUS_POINT`**
  - Payload: `{"x": 0.52, "y": 0.41}`
  - Triggers Camera2 `CONTROL_AF_MODE_AUTO` and `CONTROL_AF_TRIGGER_START` at the normalized sensor coordinate.
* **`LOCK_FOCUS`**
  - Payload: `{"locked": true}`
  - Locks lens position at current focal distance using `CONTROL_AF_MODE_AUTO` with trigger locked.
* **`SET_MANUAL_FOCUS`**
  - Payload: `{"distance": 0.35}`
  - Distance in diopters (`0.0` = infinity, `10.0` = 10cm macro).

### 4.3 Exposure & Metering Control
* **`SET_EXPOSURE_POINT`**
  - Payload: `{"x": 0.60, "y": 0.38}`
  - Applies 3A AE metering rectangle centered on normalized coordinates.
* **`LOCK_EXPOSURE`**
  - Payload: `{"locked": true}`
  - Sets Camera2 `CONTROL_AE_LOCK = true`.
* **`SET_EXPOSURE_COMPENSATION`**
  - Payload: `{"index": -2}`
  - Applied using `CONTROL_AE_EXPOSURE_COMPENSATION`.
* **`SET_MANUAL_EXPOSURE`**
  - Payload: `{"iso": 400, "exposureTimeNs": 20000000}`
  - Disables AE mode and sets direct sensor sensitivity and shutter duration.

### 4.4 Hardware Features & Look Customization
* **`SET_TORCH`**
  - Payload: `{"enabled": true}`
  - Activates camera flash torch.
* **`SWITCH_CAMERA`**
  - Payload: `{"facing": "FRONT"}` (or `"BACK"`)
  - Seamlessly re-opens the specified camera sensor.
* **`SET_LUT`**
  - Payload: `{"lutId": "warm_sunset", "intensity": 0.85}`
  - Applies 3D Rec.709 color grade on the hardware GLES30 pipeline.
* **`SET_STREAM_PROFILE`**
  - Payload: `{"quality": "PREVIEW_720P", "fps": 30}`
  - Adapts stream compression resolution and frame rate.

---

## 5. Camera State Telemetry Schema

State updates are broadcast proactively on every parameter change, and continuously at 1 Hz during active recording:

```json
{
  "revision": 42,
  "isRecording": true,
  "recordingDurationSec": 75,
  "recordedBytes": 145892300,
  "zoom": 1.5,
  "activeLutId": "cinema_cool",
  "focusMode": "AUTO",
  "isFocusLocked": false,
  "focusPoint": { "x": 0.50, "y": 0.50 },
  "exposureMode": "AUTO",
  "isExposureLocked": false,
  "exposurePoint": { "x": 0.50, "y": 0.50 },
  "exposureCompensation": 0,
  "manualIso": 100,
  "manualExposureTimeNs": 33333333,
  "torchEnabled": false,
  "activeFacing": "BACK",
  "batteryLevel": 88,
  "batteryCharging": false,
  "availableStorageMb": 48200,
  "streamProfile": "PREVIEW_540P"
}
```

---

## 6. Coordinate Transformation Geometry

Touches registered on the remote device screen must accurately translate into physical Camera2 sensor coordinates regardless of screen aspect ratios, letterboxing, device rotation, or lens mirroring.

The transformation pipeline implemented in `CoordinateTransformer.kt`:

1. **Letterbox Offset Removal:**
   $$\tilde{x} = \frac{x_{touch} \cdot W_{container} - \text{offset}_X}{W_{content}}, \quad \tilde{y} = \frac{y_{touch} \cdot H_{container} - \text{offset}_Y}{H_{content}}$$
   Clamped strictly to $[0.0, 1.0]$.

2. **Sensor Rotation Mapping:**
   Cameras typically have fixed sensor orientations (rear is usually $90^\circ$, front is $270^\circ$).
   * $0^\circ$: $(x_{rot}, y_{rot}) = (\tilde{x}, \tilde{y})$
   * $90^\circ$: $(x_{rot}, y_{rot}) = (\tilde{y}, 1.0 - \tilde{x})$
   * $180^\circ$: $(x_{rot}, y_{rot}) = (1.0 - \tilde{x}, 1.0 - \tilde{y})$
   * $270^\circ$: $(x_{rot}, y_{rot}) = (1.0 - \tilde{y}, \tilde{x})$

3. **Front Camera Horizontal Mirroring:**
   If `isFrontFacing == true`:
   $$x_{mirrored} = 1.0 - x_{rot}, \quad y_{mirrored} = y_{rot}$$

4. **Sensor Crop Region Transformation:**
   Normalized coordinates are multiplied by the active `SCALER_CROP_REGION` rectangle to produce the exact pixel target for `MeteringRectangle` structures passed to Camera2 capture requests.

---

## 7. Fault Tolerance & Automatic Recovery

1. **Heartbeat RTT Measurement:**
   Every 2 seconds, the client sends a `PING` payload with a client timestamp. The server returns an immediate `PONG` acknowledgment. The client computes round-trip latency (`rttMs`) and displays it in real time on the telemetry bar.
2. **Connection Loss & Auto-Reconnect:**
   If the socket closes unexpectedly, the client enters `CONNECTING` mode with an exponential backoff retry strategy ($1\text{s}, 2\text{s}, 4\text{s}, \dots, 10\text{s}$).
3. **State Reconciliation on Reconnect:**
   Upon reconnecting, the client immediately requests a fresh state snapshot (`REQUEST_SNAPSHOT`). The remote UI re-synchronizes entirely with the authoritative camera host.
4. **Command Deduplication:**
   Every command includes a unique `commandId`. The camera host maintains a sliding cache of recent executed command IDs to reject duplicate transmissions caused by network retries.
