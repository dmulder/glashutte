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
- **Head tracking needs an SDK build.** Without the SDK you get a stub backend: the workspace still
  runs on the glasses' display in PINNED view, but it can **never** track your head. The container
  build (`make build/apk`) therefore **fails** when the SDK is missing; the stub needs an explicit
  `VITURE=off`. APK names tell you which you have: `-with-viture-sdk.apk` vs `-no-viture-sdk.apk`.
- **No head tracking on a with-SDK build?** Open the **Glashutte Diagnostics** app icon or follow
  [`docs/HEAD-TRACKING-DEBUG.md`](docs/HEAD-TRACKING-DEBUG.md); one run shows which stage fails.
- **Apps are visible on the glasses but do not react to the phone trackpad, a Bluetooth mouse or a
  Bluetooth keyboard?** Follow [`docs/INPUT-DEBUG.md`](docs/INPUT-DEBUG.md); it tests each of the
  three separately and the first line of the log says whether the input helper is connected.
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

## Supplying the VITURE SDK (required for head tracking, local only)

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

The directory must contain headers and native libraries:

```
<sdk>/android/arm64-v8a/libglasses.so
<sdk>/android/arm64-v8a/libcarina_vio.so
<sdk>/android/arm64-v8a/libcloud_protocol.so
<sdk>/include/viture_*.h
```

The native library directory may also be named `libs/` instead of `android/`.
If both contain `arm64-v8a/libglasses.so`, the build uses `libs/`.

Tested against SDK 2.4.0 on the upstream author's hardware. Extra build requirements when
the SDK is present: NDK `30.0.14904198` and CMake `4.1.2` (install via the SDK Manager).
Gradle modes (`-Pviture.sdk=...`):

| Value | Behaviour |
|---|---|
| (unset) | Use the SDK if found; otherwise build the stub and print a loud `WARNING ... STUB backend` line. CI and quick UI work use this. |
| `required` | **Fail** if the SDK is missing or incomplete. The container build uses this. |
| `off` | Build the stub even if an SDK is present. |

The build prints which one it chose (`:glasses: VITURE SDK found ...` or `... STUB backend ...`).
Check an APK with `unzip -l app-debug.apk | grep libglasses`: a with-SDK build contains
`lib/arm64-v8a/libglasses.so` and `libglasses_bridge.so`; a stub contains neither. A running app
also says so: the diagnostics screen shows `backend` and a stub build shows a banner on the glasses.

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

The same command builds with or without the SDK (add `-Pviture.sdk=required` to make a missing
SDK an error). CI runs exactly this, without the SDK, and uploads the stub-backend debug APK as
a build artifact; that artifact cannot track your head. Build notes and the
dependency (JitPack) caveat are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#6-build-and-ci).

### Building in a container (podman or docker)

No local JDK/Android SDK/NDK? The `Makefile` builds a container image holding every build
dependency (JDK 21, Android platform 37.0, build-tools 36.0.0, NDK 30.0.14904198, CMake 4.1.2)
and runs the Gradle build inside it. podman is preferred, docker is the fallback
(`CONTAINER_ENGINE=docker` / `=podman` overrides).

```bash
make help            # list targets and variables
make image           # build the build-environment image (once; ~1.5 GB)
make build/apk       # debug + release APKs -> dist/ (fails if the VITURE SDK is missing)
make build/apk VITURE=off   # stub APKs on purpose: no head tracking
make build/apk-debug # or build/apk-release
make test            # :glasses unit tests
make shell           # interactive shell in the build environment
make clean           # remove dist/ and the scratch build volume (distclean: also caches + image)
```

If Maven Central returns HTTP 429, you can select a Maven Central mirror or your
organization's repository cache for plugin and app dependencies:

```bash
make build/apk MAVEN_CENTRAL_URL=https://maven-central.storage-download.googleapis.com/maven2/
```

Without this variable, the build uses Maven Central directly. For builds outside the
container, export `MAVEN_CENTRAL_URL` before running Gradle.

- Your source tree is mounted **read-only** (`:Z` relabel for SELinux hosts); the build runs on a
  copy in a named volume, so the working tree is never touched. Gradle and Android caches
  live in the named volume `glashutte-cache`, so repeat builds are fast. Rootless podman uses
  `--userns=keep-id`, so `dist/` is owned by you.
- APKs are named `glashutte-<variant>-no-viture-sdk.apk` or `-with-viture-sdk.apk`. The
  release APK is **unsigned**; sign it yourself (`apksigner`) before installing.
- **VITURE SDK:** if `Android/libs/viture-sdk` (or the directory in `VITURE_SDK_DIR=...`)
  contains the SDK, it is bind-mounted **read-only** into the container and the build picks it
  up. It is never copied into the image or the build context, and never committed.
  Without it `make build/apk` stops with an error instead of quietly producing a stub APK;
  `make build/apk VITURE=off` builds the stub on purpose (also when the SDK is present).
  After the build the entrypoint checks that the APK really contains (or, for a stub, does not
  contain) `libglasses.so` and `libglasses_bridge.so`.
- **The image is safe to share in the sense that it holds no VITURE SDK and no Glashutte source,**
  but it does bundle Google's Android SDK/NDK under Google's licence terms. Build it locally;
  do not push it to a public registry.
- The Dockerfile pulls `docker.io/library/ubuntu:24.04` by default; use `BASE_IMAGE=...` for a
  mirror. Keep the version pins in the `Dockerfile` in sync with `Android/*/build.gradle.kts`.

First run on the phone: pair the privileged helper through Wireless Debugging (see
[`Android/docs/PRIVILEGE.md`](Android/docs/PRIVILEGE.md)), plug in the glasses, accept the
USB permission dialog. More on usage, hotkeys and troubleshooting in
[`Android/README.md`](Android/README.md).

### Troubleshooting startup

The `Glashutte/Privileged` log tag records the initial setup state, Developer options
read results, pairing-marker presence, and reasons helper startup stops. Failed settings
reads and state listeners include stack traces. `Glashutte/Main` records activity resumes
and setup scene changes. These messages do not include pairing codes or ADB credentials.
The Developer options setting is diagnostic only: it can read as disabled inside the app
even when enabled on the phone. Setup relies on actual ADB pairing and connection results.

After installing a debug build, capture logs in one terminal:

```bash
adb logcat -v threadtime -T 1 'Glashutte/Privileged:V' 'Glashutte/Main:V' 'AndroidRuntime:E' '*:S' > output
```

In another terminal, restart the app, then reproduce the problem:

```bash
adb shell am force-stop io.github.dmulder.glashutte
adb shell am start -n io.github.dmulder.glashutte/.MainActivity
```

Stop the capture with Ctrl+C. Glasses are not required to diagnose the setup wizard.

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
