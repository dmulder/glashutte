# Glashutte

> A head-tracked, multi-window Android desktop for VITURE glasses, driven from a Pixel phone.

[![Android build](https://github.com/dmulder/glashutte/actions/workflows/android.yml/badge.svg)](https://github.com/dmulder/glashutte/actions/workflows/android.yml)

> ### Credit where it is due
>
> **Glashutte is a fork of [UxSpace](https://github.com/darkclad/uxspace) by
> [Demian Vladi (darkclad)](https://github.com/darkclad), licensed under the
> [Apache License 2.0](LICENSE).** The spatial workspace, virtual-display window
> framework, privileged-helper bootstrap, input handling and the Luma Ultra tracking
> pipeline are his work. This fork re-targets that foundation at a Pixel 10 Pro XL running
> Android 17 and restructures how it talks to the glasses. Please star and support the
> [original project](https://github.com/darkclad/uxspace). See [`NOTICE`](NOTICE).

## Goals

- **A real mobile workstation.** Plug VITURE glasses into a Pixel and get several
  world-locked virtual monitors, a taskbar and app drawer, with the phone as trackpad and
  a Bluetooth keyboard and mouse as first-class input.
- **Target hardware:** Google Pixel 10 Pro XL on Android 17, with VITURE Luma Ultra
  glasses. Other recent phones with USB-C DisplayPort and other SDK-supported VITURE
  glasses may work but are not a goal.
- **VITURE Android SDK only.** No separate open-protocol (HID) backends. Head tracking
  and display control sit behind a small `GlassesBackend` interface
  ([`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)), so the SDK is an optional build input.
- **Builds without the SDK.** Without the SDK you get a stub backend: the workspace still
  runs on the glasses' display in PINNED view, just without head tracking.
- **DRM apps via a flat "native handoff" mode (planned).** Apps that render protected
  content cannot be re-rendered into a head-tracked scene, so the plan is to hand the
  glasses' display to such an app natively (flat, no head tracking) and return to the
  spatial scene afterwards.
- **Out of scope:** genuine Android XR / Jetpack XR on the phone.

## Status

**Alpha, inherited from upstream.** The code works on the upstream author's Galaxy Z Fold 7
with VITURE Luma Ultra. It has **not yet been run on a Pixel 10 Pro XL**; see the hardware
checklist in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#7-hardware-test-checklist).

| Area                                              | State                                              |
|---------------------------------------------------|----------------------------------------------------|
| Rebrand to Glashutte (package `io.github.dmulder.glashutte`) | Done                                    |
| CI build without the VITURE SDK                   | Done ([workflow](.github/workflows/android.yml))   |
| `GlassesBackend` abstraction (SDK + stub)         | Done, compiles both ways; SDK path untested on hardware |
| Desktop, taskbar, drawer, multi-window, input     | Inherited, working upstream                        |
| Head tracking (Carina VIO) on Pixel 10 Pro XL     | Untested                                           |
| Native handoff mode for DRM apps                  | Planned                                            |
| SBS / stereo output                               | Not implemented (mono image mirrored to both eyes) |

## Supplying the VITURE SDK (optional, local only)

The VITURE Android SDK is proprietary. **It is never committed to this repository and is
never distributed by it.** You obtain it yourself from the
[VITURE developer portal](https://www.viture.com/developer) and read its licence.
Builds you make *with* the SDK may be redistributed as the SDK terms allow; the SDK files
themselves may not be.

Gradle looks for it in this order (first hit wins):

1. the Gradle property `viture.sdk.dir` (`-Pviture.sdk.dir=/path/to/sdk`, or a line in
   `~/.gradle/gradle.properties`)
2. the `VITURE_SDK_DIR` environment variable
3. `Android/libs/viture-sdk/` (git-ignored)

The directory must have the layout VITURE ships:

```
<sdk>/libs/arm64-v8a/libglasses.so
<sdk>/libs/arm64-v8a/libcarina_vio.so
<sdk>/libs/arm64-v8a/libcloud_protocol.so
<sdk>/include/viture_*.h
```

Tested against SDK 2.4.0 on the upstream author's hardware. Extra build requirements when
the SDK is present: NDK `30.0.14904198` and CMake `4.1.2` (install via the SDK Manager).
Pass `-Pviture.sdk=off` to force the stub backend even if an SDK is present. The build
prints which one it chose (`:glasses: VITURE SDK found ...` or `... not supplied ...`).

## Building

Requirements: JDK 21 and the Android SDK with platform `37.0` and build-tools `36.0.0`
(the NDK and CMake are needed only for builds that include the VITURE SDK).

```bash
cd Android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # path to your Android SDK
./gradlew :glasses:testDebugUnitTest                  # unit tests
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The same command builds with or without the SDK. CI runs exactly this, without the SDK,
and uploads the stub-backend debug APK as a build artifact. Build notes and the
dependency (JitPack) caveat are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#6-build-and-ci).

First run on the phone: pair the privileged helper through Wireless Debugging (see
[`Android/docs/PRIVILEGE.md`](Android/docs/PRIVILEGE.md)), plug in the glasses, accept the
USB permission dialog. More on usage, hotkeys and troubleshooting in
[`Android/README.md`](Android/README.md).

## Repository layout

```
Android/    The app: :app (phone UI + desktop), :spatial (GL workspace), :glasses (backend + tracking)
docs/       Architecture of this fork (ARCHITECTURE.md)
Windows/    Inherited from upstream UxSpace; not part of Glashutte, unmaintained here
.github/    CI
```

`Windows/` is the upstream author's Windows companion (a VITURE Windows SDK consumer and a
forked IddCx driver). Glashutte targets the VITURE **Android** SDK only, so that directory
is kept solely as inherited history and is not built or tested here.

## Licence and notices

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Upstream
copyright remains with its author. The `Windows/driver/` directory retains its own MIT
licence (`Windows/driver/UPSTREAM_LICENSE`). The VITURE SDK is proprietary, not covered by
this licence, and not part of this repository.

Glashutte is an independent project and is **not affiliated with, endorsed by or
sponsored by VITURE**. "VITURE" and related product names belong to their owners and are
used here only to say which hardware this software works with.
