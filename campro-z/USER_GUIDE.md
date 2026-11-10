# ProCam Z & ProCam Z Remote - User & Operator Guide

Welcome to **ProCam Z** and **ProCam Z Remote**, a professional Android dual-device remote camera monitoring and control system. This system allows one Android phone to act as the master recording camera (mounted on a tripod, gimbal, crane, or rig) while a second Android phone or tablet functions as a wireless wireless field monitor and control console.

---

## 1. Package Overview & Installation

Both applications are completely standalone and have distinct package namespaces and application IDs. They can be installed on two separate devices, or even side-by-side on the same device for testing:

| Application | Role | Application ID | APK File Location |
| :--- | :--- | :--- | :--- |
| **ProCam Z** | Camera Host & Master Recorder | `org.ndsolotravel.apps` | `campro-z/androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| **ProCam Z Remote** | Wireless Field Monitor & Controller | `org.ndsolotravel.camproz.remote` | `campro-z/remoteApp/build/outputs/apk/debug/remoteApp-debug.apk` |

### Installing via ADB (Command Line)
If you have devices connected via USB or wireless ADB:

```powershell
# 1. Install ProCam Z on your recording phone:
adb -s <CAMERA_DEVICE_SERIAL> install -r "campro-z\androidApp\build\outputs\apk\debug\androidApp-debug.apk"

# 2. Install ProCam Z Remote on your monitoring phone/tablet:
adb -s <REMOTE_DEVICE_SERIAL> install -r "campro-z\remoteApp\build\outputs\apk\debug\remoteApp-debug.apk"
```

---

## 2. Network Configuration

ProCam Z requires **zero internet access**, **zero cloud logins**, and **zero external servers**. It operates entirely within your local local-area network (LAN).

### Option A: Mobile Phone Hotspot (Recommended for Field / Outdoor Shooting)
1. On either phone (e.g., your camera phone), turn on **Personal Hotspot / Portable Wi-Fi Hotspot**.
2. On the other phone/tablet, connect to that Wi-Fi hotspot network.
3. *Note:* Cellular data does **not** need to be turned on; local Wi-Fi routing works completely offline.

### Option B: Local Wi-Fi Router / Studio Network
1. Ensure both devices are connected to the same Wi-Fi router (2.4 GHz or 5 GHz).
2. For best performance, 5 GHz Wi-Fi or Wi-Fi 6 is recommended for the lowest streaming latency (<100ms).
3. If your corporate or public Wi-Fi network enforces *Client Isolation* (preventing devices from communicating directly), use **Option A (Mobile Hotspot)** instead.

---

## 3. Step-by-Step Pairing Guide

### Step 1: Launch ProCam Z on the Camera Phone
1. Open the **ProCam Z** app.
2. Grant camera and microphone permissions when prompted.
3. Tap the **Pairing / Wireless** icon (broadcast icon) in the top-right toolbar.
4. A pairing modal will appear displaying:
   * A high-contrast **QR Code**.
   * The camera phone's **Local IP Address** (e.g., `192.168.43.1`).
   * Command & Stream Ports (`8989` & `8990`).
   * A 6-digit **PAIRING PIN** (e.g., `839120`).

### Step 2: Connect from ProCam Z Remote
1. Open **ProCam Z Remote** on your monitoring phone or tablet.
2. In the connection hub:
   * **Automatic Discovery:** Available cameras on the network will appear automatically under *Discovered Cameras*. Tap your camera name (e.g., `CamProZ-92A1`).
   * **Manual Connect:** If multicast is restricted on your network, tap **Manual Connect**, enter the Host IP (e.g. `192.168.43.1`) and the 6-digit PIN code from the camera screen, then tap **Connect**.

### Step 3: Approve on the Camera Phone
1. As a security safeguard, a prompt will appear on the camera phone's screen:  
   `"Allow [Device Name] to view live preview and control this camera?"`
2. Tap **Approve** on the camera phone.
3. Both devices will immediately link! The remote screen switches to the live real-time camera viewfinder.

---

## 4. Operating the Remote Monitor & Controls

The companion interface is designed as a professional dark studio monitor optimized for daylight visibility, responsiveness, and one-thumb control.

```
+-------------------------------------------------------------------+
| [DISCONNECT]   1080p30   [REC 04:12]   RTT: 18ms   [BAT 86%]  [FIT]
|-------------------------------------------------------------------|
|                                                                   |
|                         LIVE MONITOR VIEW                         |
|                                                                   |
|            [  +  ]  <--- Cyan Draggable Focus Box                 |
|                                                                   |
|                 ( O )  <--- Amber Draggable Metering Circle       |
|                                                                   |
|-------------------------------------------------------------------|
| ZOOM: [ - ] ----------------( 2.4x )---------------- [ + ]        |
| EV:   [ - ] -----------------( 0.0 )----------------- [ + ]        |
| LUTs: [ Natural ] [ Travel ] [ Sunset ] [ Cinema ] [ Mono ] [ 80s]|
| CAM:  [ Switch Lens ]   TORCH: [ On/Off ]   REC: [ ● RECORD / ■ ] |
+-------------------------------------------------------------------+
```

### 4.1 Focus & Metering Controls
* **Tap to Focus:** Tap anywhere on the live video stream. The focus reticle moves directly to that spot and commands the camera engine to perform an auto-focus sweep.
* **Draggable Focus Reticle:** Press and drag the focus reticle box to adjust subject tracking interactively.
* **Double-Tap to Lock Focus:** Double-tap anywhere on the preview. The focus reticle switches to amber with a lock icon, locking the lens at the current focal plane. Double-tap again to unlock.
* **Exposure Metering Circle:** The distinct dashed circle meters exposure independently. Tap or drag to expose for highlights or shadows.

### 4.2 Recording Operations
* **Start Recording:** Tap the large red **Record** button. The camera phone will confirm recording start, and the telemetry banner turns red with a live elapsed time counter (`REC 00:01`, `00:02`...).
* **Stop Recording:** Tap the stop button. The video file is finalized and flushed to the camera phone's MediaStore gallery.

### 4.3 3D LUT Cinematic Look Switcher
* Browse through the horizontal LUT carousel at the bottom:
  * **Identity / Passthrough:** Unprocessed camera feed.
  * **Natural Rec.709:** Balanced, true-to-life contrast.
  * **Travel Vibrant:** Punchy greens and blues.
  * **Warm Sunset:** Golden-hour highlight tones.
  * **Cool Cinema:** Teal-and-orange modern blockbuster aesthetic.
  * **Monochrome:** High-contrast film black and white.
  * **Vintage 80s:** Nostalgic lifted-black look.
* Tapping a LUT instantaneously applies it on the camera phone's hardware OpenGL ES 3.0 shader and updates the live monitoring feed!

### 4.4 Zoom & Exposure Sliders
* **Continuous Zoom Slider:** Adjust optical and digital zoom smoothly from 1.0x up to 8.0x.
* **Exposure Compensation (EV):** Fine-tune exposure brightness from $-12$ to $+12$ steps.

---

## 5. Security & Operator Safeguards

1. **One Controller at a Time:** Prevents multiple people from competing for camera control.
2. **Instant Host Revocation:** The camera operator can tap the pairing button at any time and select **Revoke Remote Access** to immediately kick off the remote device.
3. **Telemetry & Thermal Safeguards:** Remote displays real-time battery percentage, charging status, and free storage remaining on the camera host.
