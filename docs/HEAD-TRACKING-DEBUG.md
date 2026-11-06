# Debugging "no head tracking" on a device

Use this when the workspace shows on the glasses but the view does not follow your head
(no 3DoF/6DoF). One run, about two minutes, tells you which stage of the pipeline fails.

## 0. Make sure you installed an SDK build

The APK file name says what it contains:

| File name ends in | Meaning |
|---|---|
| `-with-viture-sdk.apk` | Real VITURE SDK is packaged. Head tracking is possible. |
| `-no-viture-sdk.apk` | **Stub.** It can never track. The app shows `NO HEAD TRACKING: this is the STUB build` at the top of the glasses view. |

`make build/apk` refuses to build without the SDK (use `VITURE=off` for a stub on purpose).
See the [README](../README.md#supplying-the-viture-sdk-local-only).

## 1. The pipeline and what each stage means

A head pose has to travel through eight stages. The **first stage that is not OK** is the
one to look at. The same list appears in the diagnostics screen, the exported log, and (in
short form) on the glasses.

| # | Stage | Fails when |
|---|---|---|
| 1 | Backend | The APK has no SDK (stub). |
| 2 | Glasses visible on USB | Android does not list a VITURE (VID `0x35CA`) device with a supported product id (Luma Ultra: `0x1101`/`0x1104`). The report lists **every** USB device Android sees. |
| 3 | USB permission + open | Permission not granted, or `openDevice` returned null. If the device has a video-class interface, Android also wants the **CAMERA** permission first (see below). |
| 4 | SDK handle (create) | `libglasses.so`/the JNI bridge failed to load (`UnsatisfiedLinkError`), or `create(pid, fd)` returned false. |
| 5 | SDK initialize + start | `initialize` or `start` returned a negative code (the report names it, e.g. `-7 handshake`). |
| 6 | Tracking path | Records which path the SDK chose (Carina VIO, native DoF, or host IMU) and the Carina 3DoF/6DoF setting. WARN if the chosen path is not expected to produce poses. |
| 7 | Pose stream | No pose arrived, or poses arrive but never change (the report shows the pose rate, last quaternion and native counters). |
| 8 | Pose reaches renderer / world-locked | Poses arrive but the view is `PINNED`; the camera ignores head pose in `PINNED`. |

For the Luma Ultra the SDK is expected to report `Carina` (camera-based VIO) as device type
and `isProductSupportNativeDof=false`.

## 2. Capture

Install the APK, connect the glasses, open Glashutte, and wait about 15 seconds. Then either use
the in-app screen or logcat; the screen is enough for most reports.

### In-app: "Glashutte Diagnostics"

There is a second launcher icon named **Glashutte Diagnostics**. It shows the live report and has:

- **Share log**: writes the full report plus a filtered `logcat` and the SDK's own log to a text file and opens the share sheet.
- **Copy**: copies the report to the clipboard.
- **Restart tracking**: restarts the SDK session (needs the workspace to be running).
- **Camera permission**: asks for CAMERA, then restarts tracking.
- **Probe USB**: lists every USB device Android sees (included in the report).
- **Experiments** (apply on *Restart tracking*; defaults match upstream behaviour):
  force the tracking path (Auto / Carina 3DoF / Carina 6DoF / host IMU / native DoF),
  pass an app cache directory to SDK `initialize()`, and show a diagnostics overlay on the glasses.

### Logcat

```bash
adb logcat -c
# reproduce: replug glasses, start the app, wait 15 s
adb logcat -v threadtime \
  GlashutteDiag:V Glashutte/Tracking:V Glashutte/VitureSdk:V Glashutte/Native:V \
  Glashutte/SdkLog:V Glashutte/Usb:V Glashutte/Display:V Glashutte/Main:V \
  libglasses:V AndroidRuntime:E '*:S' | tee glashutte-tracking.log
```

| Tag | What it carries |
|---|---|
| `GlashutteDiag` | One line per stage change (`stage 5. SDK initialize + start: FAIL ...`) and a 5-second heartbeat: backend, mode, pose rate, view mode, verdict. **Start here.** |
| `Glashutte/VitureSdk` | SDK call order and every return code (`create`, `setDofType`, `initialize`, `start`), `deviceType`, which path was picked. |
| `Glashutte/Native` | JNI bridge: first pose, poll failures (rate-limited), callback counters. |
| `Glashutte/SdkLog` | The proprietary library's own log output (hooked at level 3 when the SDK exports the hook). |
| `Glashutte/Usb`, `Glashutte/Display` | USB inventory, permission/CAMERA state, glasses display lookup. |
| `Glashutte/Tracking` | Worker thread, watchdogs, restarts. |

### On the glasses

If tracking is not working, a banner at the top of the glasses view names the failing stage
(`HEAD TRACKING OFF - ...`). Enable the full overlay with the experiments checkbox.

## 3. What to send back

1. The file from **Share log** (or the logcat above), taken **after** about 15 seconds with the glasses connected.
2. Which icon the lock button in the toolbar shows (a lock icon = tracking is up; a retry icon = tracking is down).
3. Whether the view follows your head after tapping the lock button once.
4. If stage 7 or 6 is not OK: repeat once with **Tracking path** forced to *Carina 3DoF*, then *Host IMU*, press
   **Restart tracking**, wait 15 s, and share again.

## 4. Behaviour notes

- **World-lock.** The workspace starts `PINNED` (screen glued to your head). It switches to `FREE`
  (world-locked) automatically when tracking comes up, unless you pinned it yourself. Turn that off in
  Settings -> "World-lock automatically when head tracking starts".
- **CAMERA permission.** Android 9+ withholds a USB device with a video-class interface until the app
  holds CAMERA. The glasses expose Carina's cameras this way, so the app declares CAMERA and requests it
  (once) when the glasses device has such an interface. Denying it can leave USB access denied.
- **Pose rates.** The Carina callback runs at camera rate (about 25 Hz); the app polls the IMU-rate pose
  (about 120 Hz). Both counters are in the report.
