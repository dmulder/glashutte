# Glashutte Architecture v2 — a spatial window manager

## 1. Why reorganize

`WorkspaceRenderer` has grown into a ~700-line god object. It currently owns GL setup
and shaders, the head-tracked camera, screen quads, the wallpaper, the cursor, the
taskbar, the drawer panel, the app-icon grid, all hit-testing, frame capture, and app
launching.

Every new DeX element — system tray, clock, window chrome, animations, multi-screen
layouts — means another hand-placed GL quad and another bespoke hit-test. That does not
scale.

The fix: separate concerns into **three libraries + the application**, along the four
layers below.

## 2. The four layers

| # | Layer        | What it is                                                            | Module     | Key types                                      |
|---|--------------|-----------------------------------------------------------------------|------------|------------------------------------------------|
| 1 | **View**     | The 3D stage — a head-tracked camera compositing Screens in a layout  | `:spatial` | `Scene`, `Camera`, `ScreenLayout`, `Surface3D` |
| 2 | **Screen**   | An N×M content surface that becomes a texture in the scene            | `:spatial` | `Screen`, `AppScreen`, `UiScreen`              |
| 3 | **UI framework** | A 2D widget toolkit — widgets, layout, input, animation           | `:ui`      | `Widget`, `UiRoot`, `Button`, `IconGrid`, `Animator` |
| 4 | **UI layout**| A concrete screenful of widgets — the DeX desktop                     | `:app`     | `DesktopLayout`, `Taskbar`, `AppDrawer`        |

Plus two infrastructure libraries: `:tracking` + `:viture` — pluggable head tracking,
VITURE being one implementation (§10) — and `:privileged` — bundled Shizuku (§8).

## 3. Modules and dependencies

```
:tracking    head-tracking abstraction — HeadTracker, HeadPose     -> Android
:viture      VITURE head-tracker: native SDK + USB + JNI bridge    -> :tracking
:privileged  bundled Shizuku server + client + Wi-Fi pairing       -> Android
:ui          cursor, input mapping, shared One UI components       -> Android
:spatial     GL toolkit + Camera + Screen + Scene                  -> :ui
:app         control panel + desktop + provider wiring             -> :spatial, :ui, :tracking, :viture, :privileged
```

Acyclic. `:tracking`, `:privileged` and `:viture` stand alone; the native CMake build
lives in `:viture`. Only `:app` names a concrete vendor — swap `:viture` for another
glasses SDK and nothing else changes. See §8 (`:privileged`) and §10 (`:tracking`).

## 4. Layer detail

### Layer 1 — View (`:spatial`, package `scene`)

- `Camera` — owns the projection + view matrix and the **ViewMode** (PINNED / FREE).
  Consumes a head-pose quaternion: PINNED → identity view; FREE → inverse head rotation.
  FREE is offered only when a head tracker is connected (§10).
- `Surface3D` — a textured quad with a 3D transform (position, yaw, size). The atomic
  visible thing.
- `ScreenLayout` — a placement strategy: `Single`, `ArcOfThree`, `Stack`. Maps logical
  screen slots → `Surface3D` transforms. (M3's arc, generalized.)
- `Scene` — holds the camera, the wallpaper, and the `Surface3D` list; draws them each
  frame. The slimmed-down successor to `WorkspaceRenderer`.

The View knows nothing about what is *inside* a Screen.

### Layer 2 — Screen (`:spatial`, package `screen`)

Every `Screen` is a `VirtualDisplay` rendered to a `SurfaceTexture` → GL texture — one
uniform mechanism.

- `DisplayScreen` — base: owns the `VirtualDisplay`, `SurfaceTexture`, and external-OES
  texture; exposes pixel size, `updateTexture()`, and `dispatchInput(x, y, …)`.
- `AppScreen` — a `DisplayScreen` showing a third-party app, launched onto it via
  Shizuku. (Today's `VirtualScreen`.)
- `UiScreen` — a `DisplayScreen` showing *our* `Presentation` — a real Android layout.
  The desktop (wallpaper + taskbar + drawer) is a `UiScreen`. Input dispatches straight
  into its view tree; no Shizuku needed, since it is our own window.

The View composites both kinds identically — each is just a textured quad.

### Layer 3 — UI framework (`:ui`)

With Option C the heavy lifting is Android's own widget toolkit, so `:ui` stays thin:

- `Cursor` — the pointer overlay: a static texture the View moves each frame, never
  re-rasterised.
- `CursorInput` — maps a cursor position to the front-most Screen and its pixel
  coordinates, and synthesises the `MotionEvent` / `KeyEvent` to dispatch.
- Reusable One UI-themed view components shared across layouts (taskbar item, app-grid
  cell, …) — ordinary Android `View`s, themed by `Theme.DeviceDefault`.

### Layer 4 — UI layout (`:app`, package `desktop`)

- `DesktopPresentation` — the content shown on the desktop `UiScreen`: an Android layout
  on `Theme.DeviceDefault` (One UI on Samsung) with a wallpaper, a `Taskbar` (launcher;
  later tray + clock) and an `AppDrawer` (a `RecyclerView` icon grid). Standard Android
  click listeners and animations.
- Future layouts reuse the same components.

## 5. Input flow

The phone touchpad and a Bluetooth mouse both drive one screen-space cursor.

```
Move     trackpad drag / mouse move        -> Scene moves the cursor overlay
Click    trackpad tap / mouse click        -> ray-cast cursor onto the front Screen,
                                              dispatch a tap into it
Scroll   two-finger trackpad drag /         -> ray-cast cursor onto the front Screen,
         mouse wheel                          dispatch an ACTION_SCROLL event into it
Keys     phone keyboard / BT keyboard      -> dispatch KeyEvents to the focused Screen
```

The cursor lives in the View; the View ray-casts it onto the front-most Screen and maps
to that Screen's pixels. For a `UiScreen` (the desktop) the synthesised events go
straight into our Presentation's view tree — `dispatchTouchEvent` /
`dispatchGenericMotionEvent` / `dispatchKeyEvent`, no Shizuku, since it is our own
window. For an `AppScreen` (a third-party app) they are injected via Shizuku.

Scroll specifically: a `GridView`/`ScrollView` consumes a generic `ACTION_SCROLL`
motion event (`AXIS_VSCROLL`) — the same event a mouse wheel produces — so two-finger
trackpad drags and the wheel both map to one path. `TrackpadView` reports a two-pointer
drag as a scroll delta; M4 wires it through.

## 6. Migration — incremental, app stays runnable at every step

Sequenced value-first: each step is visible and self-contained. New code lands in clean
packages now; Gradle modules are extracted once the structure has settled.

**Status (2026-05-22):** steps 1–3 done, plus the DeX-feature work of step 6 (two-finger
scroll, taskbar buildout). Step 4 (`:ui`) is deferred — it has no cohesive content yet;
the cursor/input framework that belongs there arrives with M4. Build-verified but not yet
glasses-tested, so the renderer decoupling (step 3) is the first thing to confirm on
device. The decoupling also sets up the `:tracking`/`:viture` split and Shizuku bundling.

1. ✅ **`UiScreen` + the real desktop.** Built `UiScreen` and `DesktopPresentation` — a
   `Theme.DeviceDefault` Android layout with wallpaper + taskbar + app drawer. The
   renderer draws this `UiScreen` instead of hand-drawn GL quads; cursor clicks
   dispatch into it. Option C, proven.
2. ✅ **Extract `:glasses`.** Head tracking + the native VITURE build moved into a
   `:glasses` library; `HeadTracking` decoupled from the renderer via an `onPose`
   callback. (The `:tracking`/`:viture` abstraction split — a vendor-neutral
   `HeadTracker` interface — is a later refinement; see §10.)
3. ✅ **Extract `:spatial`.** Moved the rendering layer — `WorkspaceRenderer`, `UiScreen`,
   `VirtualScreen`, `WorkspaceController`, `WorkspaceSurfaceView`, `WorkspacePresentation`
   — into a `:spatial` library. The renderer reaches `DesktopPresentation` and Shizuku
   only through injected `WorkspaceController` hooks, so the graph
   `:glasses ← :spatial ← :app` is acyclic. Code is in package `io.github.dmulder.glashutte.spatial`; the
   desktop UI (`DesktopPresentation`) in `io.github.dmulder.glashutte.desktop`.
4. ⏳ **Extract `:ui`.** Cursor, input mapping, shared view components — once that
   framework exists (M4).
5. **Bundle Shizuku** into `:privileged` — server in the APK, in-app Wireless-Debugging
   pairing; drop the separate-app dependency. Independent of steps 1–4; can run in
   parallel. See §8.
6. **Then** DeX features — system tray, clock, window chrome, multi-screen layouts —
   are each just a new view component or a new `ScreenLayout`.

Each step leaves a working app.

## 7. Decision — how a `UiScreen` renders (and can we reuse One UI?)

**Can we use One UI components?** Samsung does not publish One UI as a developer
library. But its look is not in a library — it is baked into the *device's framework*:
standard Android widgets (`Button`, `TextView`, `RecyclerView`, …) are automatically
themed as One UI on a Samsung device when the app theme derives from
`Theme.DeviceDefault`. That is exactly how Samsung DeX itself is styled. So we can have
the One UI look "for free" — by rendering the UI with **real Android widgets**, not by
hand-drawing it.

That tilts the rendering choice:

- **A — Canvas → texture.** The UI framework hand-draws every widget to a `Bitmap`.
  Full control, render-agnostic, testable headless. But we must *recreate* the One UI
  look ourselves — colours, metrics, fonts, ripples — and it will never quite match,
  nor track Samsung updates.
- **C — Android Views on a VirtualDisplay (recommended).** The desktop UI is a real
  Android layout shown in a `Presentation` on an internal `VirtualDisplay`, rendered to
  a `SurfaceTexture` → GL texture — the *same* pipeline `AppScreen` already uses. With
  the app theme on `Theme.DeviceDefault`, every widget is One UI-styled by the device,
  for free. Cursor clicks dispatch straight into that Presentation's view tree
  (`dispatchTouchEvent`) — no Shizuku, since it is our own window. Animations are
  Android's own and simply appear in the sampled texture.

Under **C**, Layer 2 unifies: `UiScreen` and `AppScreen` are both "a VirtualDisplay
behind a quad" — `UiScreen` shows *our* Presentation, `AppScreen` shows a third-party
app. Layer 3 (`:ui`) shrinks to the cursor overlay, the cursor → display input mapping,
and a small set of reusable layout pieces; Layer 4 becomes an ordinary Android layout.

Trade-off: the One UI look appears only on One UI devices (stock Material elsewhere) —
fine for a Samsung-targeted app. Unofficial `sesl` / `oneui-design` GitHub ports exist
if the look is ever needed off-Samsung.

**Decided: C** — it is how DeX itself is built, reuses our existing display → texture
pipeline, and gives the One UI look without rebuilding it.

## 8. Privileged access — bundling Shizuku

Glashutte needs shell-level privilege to launch third-party apps onto its virtual displays
(`am start --display`) and, later, to inject input. A non-rooted app **cannot
self-elevate**, so today Glashutte depends on the separately-installed **Shizuku app**.

Shizuku's server is open source (Apache-2.0) — no conflict with Glashutte, also open
source. So Glashutte will **bundle it**: ship the Shizuku server inside Glashutte's own APK and
own the whole flow in a `:privileged` module. No second app to install. (Apache-2.0
requires keeping Shizuku's licence + NOTICE — added to the module.)

What bundling changes — and what it cannot:

- **Removed:** the separate Shizuku-app install and its onboarding.
- **Owned by Glashutte:** its own start command, its own pairing, restarting the server on
  later launches.
- **Irreducible (OS security boundary):** the privileged process must be started by
  *something* that already holds shell privilege. Glashutte does this in-app, no PC needed —
  it pairs with the device's own **Wireless Debugging**, connects over local ADB-Wi-Fi,
  and starts the bundled server. The pairing is kept, so later launches reconnect
  silently — but the one-time pairing itself cannot be removed without root.

Net: a one-time, in-app Wireless Debugging pairing, after which privileged access is
invisible. Integrating Shizuku removes the *separate app*, not the bootstrap.

## 9. The phone control surface

The phone is the workspace's **input device** — a trackpad + keyboard ("DeX for
glasses"), not a second screen. Once Shizuku is set up, the control panel is, top to
bottom:

- **Toolbar** — icon buttons: view mode (pinned ⇄ free), capture, screen layout,
  keyboard show/hide.
- **Touchpad** — fills the middle; the relative-motion pad that drives the workspace
  cursor.
- **System keyboard** — when toggled on, the device IME rises from the bottom; the
  keyboard button shows/hides it. Keystrokes route to the focused screen (the routing
  itself is M4 input).

App launching lives entirely in the in-glasses app drawer, so the phone no longer shows
an app list. Before Shizuku is set up, the panel shows the setup banner instead of the
toolbar.

## 10. Vendor-agnostic — any display, pluggable trackers

Glashutte must not be welded to the VITURE SDK. Two capabilities, independent:

- **An external display.** The workspace `Presentation` runs on *any* connected external
  display — no SDK needed. With only a display, Glashutte still gives the full desktop:
  multiple screens, taskbar, app drawer, cursor — in **PINNED** view (the desktop locked
  to the display).
- **Head tracking.** An *optional* capability behind the `HeadTracker` interface
  (`:tracking`) — `start()`, `stop()`, `recenter()`, a pose stream. `:viture` implements
  it with the VITURE SDK; other glasses are other implementations (`:xreal`, …). The app
  selects whichever provider matches the connected device.

**Graceful degradation:** the `Camera`'s **FREE** mode (world-locked desktop) needs a
live `HeadTracker`. With none — a plain monitor, or unsupported glasses — the view-mode
toggle offers PINNED only. Everything else — multi-screen layouts, desktop, drawer,
cursor, input — is unchanged.

Only `:app` knows VITURE exists; it composes a provider in, and the rest of Glashutte sees
just the `HeadTracker` interface and "an external display."
