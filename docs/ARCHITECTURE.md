# Glashutte architecture: the glasses layer

This document covers what this fork changed relative to upstream
[UxSpace](https://github.com/darkclad/uxspace): how the app talks to the glasses, the
`GlassesBackend` abstraction, how the proprietary VITURE SDK is kept out of the repository,
and how to build and test. Upstream's broader design (layers, virtual displays, the
privileged helper) is unchanged and documented in
[`Android/docs/ARCHITECTURE.md`](../Android/docs/ARCHITECTURE.md) and
[`Android/docs/PRIVILEGE.md`](../Android/docs/PRIVILEGE.md).

## 1. Modules

```
:glasses   GlassesBackend API, VITURE-SDK backend (optional), stub backend,
           HeadTracking (watchdogs, recentre), USB + display discovery   -> Android
:spatial   GL workspace: camera, screens, renderer, WorkspacePresentation -> :glasses
:app       phone UI, desktop UI, privileged helper, wiring                -> :spatial, :glasses
```

Only `:glasses` knows the VITURE SDK exists, and only a source set inside `:glasses` that
is compiled solely when the SDK is present references it.

## 2. How the app talks to the glasses (as found in upstream)

The glasses use three independent channels over one USB-C cable.

| Channel | What | Code |
|---|---|---|
| Video | DisplayPort Alt Mode; Android sees a *presentation-category* external display. The app shows a `Presentation` (a GL surface) on it. | `GlassesDisplay.find()` (filters out the app's own `glashutte-*` virtual displays) -> `MainActivity.syncGlasses()` -> `WorkspacePresentation` |
| Control + IMU | A USB device (VID `0x35CA`). The app asks USB permission, opens it and passes the file descriptor and product id to the SDK. | `GlassesUsb` (permission, `openDevice`, PID allow-list) -> `HeadTracking.onUsbOpened()` |
| Head pose | Native SDK through JNI (`NativeGlasses` -> `glasses_bridge.cpp` -> `libglasses.so`). Three paths picked at runtime: on-glasses native DoF, Carina VIO (Luma Ultra; 3DoF or 6DoF), Gen1/2 host IMU. | previously inlined in `HeadTracking.startSdk()` |

Pose flow before this change:

```
USB attach / app start
  MainActivity.syncGlasses -> WorkspacePresentation.onCreate
    HeadTracking(onPose, onPosition, use6Dof).start()
      GlassesUsb.find/open -> (fd, pid)
      worker thread: NativeGlasses.create/initialize/start + path setup   <- SDK lifecycle
      poll thread (~120 Hz): NativeGlasses.isPoseFresh/getPose            <- SDK
        recentre heading in-app -> onPose(w,x,y,z) / onPosition(x,y,z)
          WorkspaceRenderer.setHeadPose / setHeadPosition  (inverse pose = camera)
      watchdogs: no first pose in 4 s -> auto-restart (max 3); stream silent 1.5 s -> "DOF lost"
Ctrl+Alt+R -> WorkspaceController.sdkRecenter -> NativeGlasses.resetOriginCarina()   (app module)
```

Display control was limited to one call: native-DoF products are put into flat
1920x1200@60 (`setupNativeDofDevice`). There is no SBS/stereo path in upstream (a mono image
is mirrored to both eyes).

Everything else that touches glasses hardware (`PrivilegedServer.rescanGlassesUsb`, which
unbinds/rebinds the glasses' USB device through sysfs when the IMU wedges) works at the
OS level and does not use the SDK.

## 3. The `GlassesBackend` seam

```
            +--------------------------- :glasses (always compiled) --------------------------+
            |  GlassesBackend  --open(pid, usbFd, SessionOptions)-->  GlassesSession?        |
            |       ^                                                  | capabilities         |
            |       |                                                  | pollPose(): PoseSample?
 HeadTracking ------+  USB discovery, watchdogs, auto-retry,           | recenter(), setDisplayMode()
 (PoseRecenterer)      app-side heading recentre                       | readDeviceState(), close()
            +----------------------------------------------------------------------------------+
                  ^                                   ^
   StubGlassesBackend (src/main)       VitureGlassesBackend (src/sdkPresent, only with the SDK)
   open() == null                      NativeGlasses (JNI) -> glasses_bridge.cpp -> libglasses.so
```

Types (all in `io.github.dmulder.glashutte.glasses`, file `GlassesBackend.kt`):

- `GlassesBackend`: `name`, `isSdkBacked`, `open(productId, usbFd, SessionOptions): GlassesSession?`.
- `GlassesSession`: `capabilities`, `pollPose()`, `recenter()`, `setDisplayMode()`,
  `readDeviceState()`, `close()`.
- `GlassesCapabilities`: `TrackingPath` (`NATIVE_DOF`, `CARINA_VIO`, `HOST_IMU`),
  `providesPosition`, supported and active `GlassesDisplayMode`s.
- `PoseSample`: orientation quaternion plus an optional position, in the SDK frame, before
  recentring.

Design choices:

- **Behaviour-preserving.** `VitureGlassesBackend.open()` is the old `startSdk()` sequence
  line for line (create, optional Carina DoF type, register callback, initialize, start,
  then native-DoF setup, Carina poll thread or IMU open). Session `close()` is the old
  four-call teardown. The JNI bridge only changed its package name.
- **`HeadTracking` keeps all vendor-neutral logic**: USB permission, the lifecycle lock,
  both watchdogs and auto-retry. Its recentre maths moved to `PoseRecenterer`, a pure
  class with JVM unit tests.
- **Wired into the existing paths**: `WorkspacePresentation` still creates `HeadTracking`
  (which defaults to `GlassesBackends.default()`); the Ctrl+Alt+R hook is now registered by
  `WorkspacePresentation` and calls `HeadTracking.recenterDevice()` -> `GlassesSession.recenter()`.
  Previously `:app` called `NativeGlasses` directly, which would have tied `:app` to the SDK.
- **One deliberate behaviour change**: Ctrl+Alt+R used to call the Carina origin reset
  unconditionally. `recenter()` now dispatches by tracking path (native-DoF devices use the
  native recentre; Carina keeps the origin reset; host-IMU returns false and the app-side
  recentre still applies).
- **Display control is deliberately thin.** `setDisplayMode` and `GlassesDisplayMode`
  exist so a native-handoff or SBS mode has a place to land, but the JNI bridge only
  implements flat 1920x1200@60 for native-DoF products. Adding SBS means adding bridge calls
  checked against the real SDK headers, which is hardware-dependent work.

## 4. Keeping the SDK out of the repository

`glasses/build.gradle.kts` decides at configuration time:

| | SDK found | SDK missing |
|---|---|---|
| Kotlin source set added | `src/sdkPresent/kotlin` (`NativeGlasses`, `VitureGlassesBackend`, provider returning it) | `src/sdkAbsent/kotlin` (provider returning `null`) |
| Native build | CMake builds `libglasses_bridge.so` against `<sdk>/libs` and `<sdk>/include` | none; no NDK or CMake needed |
| `jniLibs` | `<sdk>/libs/arm64-v8a/*.so` packaged into the APK | none |
| Backend at runtime | `viture-sdk` | `stub` |

Lookup order: Gradle property `viture.sdk.dir`, env `VITURE_SDK_DIR`, then
`Android/libs/viture-sdk/`. `-Pviture.sdk=off` forces the stub. The presence check uses
`providers.fileContents`, so the configuration cache notices the SDK appearing or vanishing.
If the headers exist but `libglasses.so` does not, the build fails with an explanatory
message rather than half-building.

Guards against accidental commits: `.gitignore` entries (`*.so`, `/Android/libs/viture-sdk/`,
`/Viture/`, jniLibs and include paths) and a CI step that fails if any SDK-looking file is
tracked. CI never has the SDK, so CI artifacts are stub-backend builds.

## 5. Failure modes

- No SDK: `StubGlassesBackend.open()` returns `null`, `HeadTracking` logs it, the
  `streaming` flag never goes true, FREE view stays unavailable, PINNED view and every other
  feature work.
- SDK present but the glasses reject the handshake: `NativeGlasses.create` fails, `open()`
  returns `null` and the same graceful path applies.
- Pose stream dies or never starts: unchanged watchdogs, now above the backend.

## 6. Build and CI

Observed when getting the unmodified fork building in a clean cloud VM (Ubuntu, JDK 21):

| Issue | Resolution |
|---|---|
| Without the SDK the stock build **failed** at `:glasses:configureCMake` (missing CMake 4.1.2, and even with it the headers/`libglasses.so` are absent). The upstream README claimed it still builds. | Native build is now conditional on the SDK (section 4). |
| `gradlew` was committed without the executable bit. | Mode fixed. |
| AGP 9.1.0 is "tested up to compile SDK 36.1"; Android 17 is API 37. | `compileSdk = 37` builds fine; warning suppressed with `android.suppressUnsupportedCompileSdk=37.0`. `targetSdk` stays 36 for now (see open questions). |
| `libadb-android` and `sun-security-android` come from JitPack. | Works on GitHub runners. It was unreachable from the restricted agent VM, where all three JitPack artifacts (including `spake2-android`) were built from source into `~/.m2` to verify locally. This is a VM egress limit, not a repo problem, but JitPack is a single point of failure for CI. |
| The Gradle daemon JVM criteria require JDK 21. | CI uses Temurin 21. |

CI ([`.github/workflows/android.yml`](../.github/workflows/android.yml)): checks that no SDK
files are tracked, installs platform 37.0 and build-tools, runs `:glasses:testDebugUnitTest`
and `:app:assembleDebug`, uploads the stub-backend debug APK.

Verified locally: stub build (APK has no VITURE libraries), unit tests (7 pass), and the
SDK-present path using a fake `libglasses.so` plus VITURE's publicly mirrored headers kept
outside the repository (JNI bridge compiles, symbols carry the new package name, `.so` files
get packaged, Kotlin picks `VitureGlassesBackend`). The real SDK was not available, so
the real link and runtime behaviour are in the checklist below.

### Container build

`Dockerfile`, `docker/entrypoint.sh`, and the top-level `Makefile` provide a reproducible build
environment (usage in the [README](../README.md#building-in-a-container-podman-or-docker)).
Design points:

- **Image = toolchain only.** Ubuntu 24.04, OpenJDK 21, Android cmdline-tools (pinned by
  SHA-256), platform `android-37.0`, build-tools 36.0.0, NDK 30.0.14904198 and CMake 4.1.2.
  `.dockerignore` excludes the whole build context except the entrypoint, so neither source nor
  an SDK can leak into a layer. The SDK is only ever a runtime `:ro` bind mount at
  `/viture-sdk`, enabled when `include/viture_glasses_provider.h` exists there.
- **Source is read-only.** `/src` is mounted `:ro` (with `:Z`, SELinux private label), the
  entrypoint `rsync`s `Android/` to a scratch volume (excluding `build/`, `.gradle/`, `.cxx/`,
  `local.properties` and `libs/viture-sdk`), builds there, and copies APKs to `/out` (`dist/`).
- **Users.** Rootless podman: `--userns=keep-id`; rootful docker: `--user uid:gid`; the home,
  Gradle and Android directories are under `/cache` (world-writable, a named volume) so any
  uid works, and the JVM `user.home` is pinned to it.
- **Platform.** The Android NDK/build-tools are x86-64 Linux binaries, so the image is
  `linux/amd64` (emulated on arm64 hosts).
- **`compileSdk`.** Now declared as `release(37) { minorApiLevel = 0 }`, which resolves to the
  `android-37.0` platform that `sdkmanager` installs (plain `compileSdk = 37` looked for
  `android-37` and failed in the container).
- **CI.** The default `build` job is unchanged. A manual run of the workflow with the
  `container` input ticked also runs `make CONTAINER_ENGINE=docker build/apk` and uploads the APKs.

Verified in an agent VM: podman 4.9.3 (rootless) and docker 29.1.3 (rootful) both built the
image and produced debug and release stub APKs through `make build/apk`; with a fake SDK
(public headers, outside the repo) mounted, the NDK/CMake build ran and the APK contained the
`.so` files, `VITURE=off` forced the stub, `make test` passed, and the image contains no SDK.
Not verified: SELinux-enforcing hosts, arm64 hosts, and the CI container job (manual-only).

## 7. Hardware test checklist

Only testable with the owner's Pixel 10 Pro XL, Android 17 and glasses:

- [ ] Build with the real SDK (2.4.0 or whichever the owner has): CMake compiles the bridge
      against the real headers and links; APK contains the three SDK `.so` files; log
      shows `backend 'viture-sdk'`.
- [ ] Build without the SDK installs and runs on the Pixel: workspace shows in PINNED view,
      log shows `stub`, no crash on USB attach.
- [ ] App starts on Android 17 with `targetSdk` 36 and with `targetSdk` 37 (if raised).
- [ ] Wireless Debugging pairing works on the Pixel and the privileged helper starts after
      a reboot.
- [ ] The helper can create trusted virtual displays (Shell holding `ADD_TRUSTED_DISPLAY`
      on Android 17) and launch a third-party app onto one.
- [ ] `GlassesDisplay.find` selects the glasses' display; the `Presentation` appears; the
      phone keeps its own screen.
- [ ] USB permission flow and product id (`0x1101`/`0x1104` for Luma Ultra) accepted by
      `GlassesUsb`.
- [ ] Which tracking path the SDK picks (`isProductSupportNativeDof` for the Luma Ultra
      should be false, giving Carina VIO).
- [ ] 3DoF pose stream live at ~60-120 Hz; head motion matches on-screen motion (axes and
      yaw sign unchanged by the refactor; `PoseRecenterer` is unit tested but not on hardware).
- [ ] 6DoF toggle (Settings) gives positional parallax after reconnect.
- [ ] Ctrl+Alt+R (`recenter()` -> Carina origin reset) and Ctrl+Alt+C behave as before.
- [ ] Unplug and replug the glasses: auto-retry and "DOF lost" watchdog recover.
- [ ] Bluetooth keyboard/mouse pairing does or does not kill the USB endpoint on the Pixel
      (upstream sees this on Samsung); `rescanGlassesUsb` sysfs path works on Pixel.
- [ ] Thermals and battery: 15+ minutes at 120 Hz with 6DoF off and on.
- [ ] Probes for the later native-handoff mode (HDCP on the glasses' DisplayPort link,
      `Display.FLAG_SECURE`, `MediaDrm` HDCP level, desktop windowing on external displays).

## 8. Open questions

- `targetSdk`: stay at 36 until the app is exercised on Android 17, or raise to 37 now?
- Is the package name `io.github.dmulder.glashutte` acceptable (it is also the application id)?
- Delete the inherited `Windows/` directory, which targets a different SDK and is untested here?
- SDK terms: written confirmation from VITURE that builds containing the SDK libraries may
  be published (the owner states this is allowed; the licence text was not available to
  this project's research).
- `LICENSE` still carries upstream's boilerplate line ("The VitureKit-Android Authors");
  it was left untouched on purpose.
- Replace JitPack-hosted dependencies with vendored or mirrored copies for CI resilience?
