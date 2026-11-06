# Glashutte — Android app

> Part of [Glashutte](../README.md), a fork of
> **[UxSpace](https://github.com/darkclad/uxspace) by Demian Vladi (darkclad)**
> (Apache-2.0). The desktop, windowing, input and tracking foundations described below are
> his work; this README is adapted from the upstream one.

An Android app that turns VITURE glasses (or any external display) into a **DeX-style
spatial workspace** — a desktop with a taskbar and an app drawer, floating in front of
you, driven from the phone as a trackpad and from a Bluetooth keyboard / mouse.

For the project goals, status, how to supply the VITURE SDK locally and how to build, see
the **[root README](../README.md)**. For the design of this fork's glasses layer see
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

---

## How it works

The glasses are a USB-C external display. Glashutte owns that display with a
`Presentation` hosting an OpenGL scene:

```
VITURE glasses ──USB-C──▶ phone
   │  external Display
   ▼
Presentation + GLSurfaceView            ← 3D scene; camera = inverse head pose
   ├─ the desktop  — a UiScreen: a real Android UI (taskbar, app drawer,
   │                 wallpaper) on its own VirtualDisplay, textured onto a quad
   └─ app windows  — each a VirtualDisplay running an installed app, on a quad
```

The desktop is a real Android view hierarchy on `Theme.DeviceDefault` — on a Samsung
device it is styled as One UI, the same way Samsung DeX is. Launched apps render onto
their own *trusted* virtual displays. Head pose (from the native VITURE SDK) drives the
camera: in **FREE** view the workspace stays fixed in space as you look around; in
**PINNED** view it follows your head. With no head tracker, PINNED view and multiple
screens still work on any external display.

### Constraints

A stock, non-rooted Android app, which bounds what is possible:

- **Launching third-party apps needs shell-uid privilege.** Placing an app on a virtual
  display is not allowed for normal apps. Glashutte activates its own shell-uid helper on
  first run through Android 11+ **Wireless Debugging** (see
  [`docs/PRIVILEGE.md`](docs/PRIVILEGE.md)). You enable Wireless Debugging once, type
  the 6-digit pairing code into Glashutte, and from then on Glashutte bootstraps the helper
  itself on every launch — no separate app required.
- **The phone is the primary input device.** Trackpad gestures (one finger moves the
  cursor, two fingers scroll, hold-to-drag, etc.). A Bluetooth mouse / keyboard works
  too — the privileged helper grabs the mouse via `EVIOCGRAB` so the phone's system UI
  never sees the events, and the cursor lives entirely inside the workspace.

---

## Modules

| Module    | What it is                                                              |
|-----------|-------------------------------------------------------------------------|
| `app`     | The phone control panel and the desktop UI layout                       |
| `spatial` | The 3D workspace — camera, screens, the GL renderer                     |
| `glasses` | `GlassesBackend` (VITURE SDK + stub), head tracking, USB / display discovery |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (upstream's layered design) and
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) (the glasses backend in this fork).

---

## Configure on the phone (first-run)

1. **Pair the privileged helper.** Open Glashutte. On first launch the setup screen asks
   you to enable **Settings → Developer options → Wireless debugging** on the phone,
   tap *Pair device with a pairing code*, and type the 6-digit code into Glashutte. From
   then on Glashutte bootstraps its own shell-uid helper on every launch (no Shizuku, no
   second app). See [`docs/PRIVILEGE.md`](docs/PRIVILEGE.md) for the details.

2. **Plug in the glasses.** A USB permission dialog will appear the first time —
   accept it. Glashutte claims the glasses display and head tracking should come up
   within ~2 seconds.

3. **Optional: pair a Bluetooth keyboard + mouse.** Standard Android pairing flow. The
   privileged helper grabs the mouse exclusively (`EVIOCGRAB`) so its events go
   straight to the workspace cursor — the phone's system UI never sees them.

4. **Hotkeys** (matches the Windows companion app at `Windows/`):

   | Combo                | Action                            |
   |----------------------|-----------------------------------|
   | `Ctrl+Alt+Z`         | Cycle screen band                 |
   | `Ctrl+Alt+X`         | Toggle PINNED / FREE              |
   | `Ctrl+Alt+R`         | SDK recenter                      |
   | `Ctrl+Alt+C`         | Anchor pose                       |
   | `Ctrl+Alt+A`         | Cycle layout (FREE only)          |
   | `Ctrl+Alt + scroll`  | Workspace zoom                    |
   | `Ctrl+Alt + drag`    | Pan zoomed viewport (PINNED)      |
   | `Ctrl+Alt` held      | Keymap legend overlay             |

   Samsung's One UI launcher intercepts `Meta` — that is why Glashutte uses `Ctrl+Alt`
   on both Android and Windows.

5. **Right-click on the desktop** for: Arrange icons, Change wallpaper, Settings,
   Cycle layout, Recenter view, Lock / Unlock, Reset zoom, Show / Hide taskbar.

---

## Troubleshooting

- **"No VITURE glasses found on USB."** The display side of the glasses sometimes
  enumerates before the USB IMU endpoint. Glashutte retries on the next
  `USB_DEVICE_ATTACHED` — unplug and re-plug. If that fails, the watchdog will toast
  you after 3 failed rescans.
- **"DOF lost" after pairing a BT keyboard.** Samsung's BT / USB stack interaction can
  kill the glasses USB endpoint. Glashutte auto-rescans (up to 3 attempts) and pops a
  toast if it can't recover. Unplug + re-plug usually fixes it.
- **`./gradlew` fails on Windows with permission errors.** Make sure Wireless
  Debugging is on, but also make sure your antivirus isn't blocking `gradlew.bat`.
- **No head tracking / "stub glasses backend" in the log.** The app was built without the
  VITURE SDK. Supply it and rebuild — see the root [README](../README.md#supplying-the-viture-sdk-optional-local-only).
- **Mouse cursor jumps to phone-screen edges.** Older builds — the raw EV_REL path
  shipped recently. Pull the latest `main`.

---

## Acknowledgements

Glashutte stands on the shoulders of the **[Shizuku](https://shizuku.rikka.app/)**
project by [RikkaApps](https://github.com/RikkaApps). Shizuku pioneered the mechanism
that makes apps like this one possible on a stock, non-rooted Android phone: borrow
ADB-shell privilege at runtime, bootstrapped from the device itself over Wireless
Debugging — no PC, no root. Glashutte (and upstream Glashutte before it) shipped on top of the Shizuku app first; the
in-app pairing flow now built into Glashutte is modelled directly on Shizuku's.
**Thank you.**

Thanks also to **[libadb-android](https://github.com/MuntashirAkon/libadb-android)**
by Muntashir Al-Islam — the embedded ADB client that lets Glashutte pair and connect to
its own device's wireless-debugging service without a separate Shizuku install.

---

## License

Apache-2.0 — see [LICENSE](LICENSE) and [`../NOTICE`](../NOTICE). The VITURE SDK is
**not** covered by this licence; obtain and use it under VITURE's terms.
