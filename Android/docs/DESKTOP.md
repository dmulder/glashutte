# Desktop shortcuts

Pinning installed apps onto a screen's wallpaper so they launch with a single tap, the
way they do on any desktop OS. Shortcuts are placed by dragging from the app drawer and
can be moved or removed by dragging again.

## Concepts

### Desktop index — shared across layouts

A workspace has up to **three logical desktops**, identified by an index `0..2`. Each
[`Layout`](../spatial/src/main/kotlin/io/github/dmulder/glashutte/spatial/Layout.kt) maps its physical
screens (its slot ordinals) onto these desktops, anchored on the user's chosen **main
screen**:

| Layout       | Main slot (default) | Slot → desktop mapping            |
|--------------|---------------------|-----------------------------------|
| Single       | 0                   | 0 → 0                             |
| Single Wide  | 0                   | 0 → 0                             |
| Two SBS      | 0 (Left)            | 0 → 0, 1 → 1                      |
| Three SBS    | 1 (Center)          | 0 → 2, 1 → 0, 2 → 1               |
| V / H / V    | 1 (H)               | 0 → 2, 1 → 0, 2 → 1               |

The rule: **main slot = desktop 0**; the slot to its right (cyclically) = desktop 1; the
next = desktop 2.

Shortcuts are stored *per desktop index*, not per `(layout, screenIdx)`. So a shortcut
placed on the main desktop in Two SBS shows up on the main desktop in Three SBS too — on
whichever physical screen the active layout has wired to that index. Switching layout
preserves the user's pinned arrangement.

The main slot per layout is configurable in the
[Settings panel](SETTINGS.md) ("Main screen" section); the default is
[`Layout.defaultMainScreen`](../spatial/src/main/kotlin/io/github/dmulder/glashutte/spatial/Layout.kt).

### Storage

[`DesktopShortcutsStore`](../app/src/main/kotlin/io/github/dmulder/glashutte/desktop/DesktopShortcutsStore.kt)
is a `SharedPreferences`-backed list of `DesktopShortcut(packageName, activityName, label,
xFraction, yFraction)` per desktop index. Positions are stored as fractions of the
screen's content rect so they survive density / shape changes when the same desktop is
rendered on a different physical screen.

* `add(desktopIdx, shortcut)` — rejects if `packageName` already pinned on that desktop.
* `remove(desktopIdx, packageName)` — used by the trash drop.
* `move(desktopIdx, packageName, x, y)` — used by drop-to-reposition.
* `shortcutsFor(desktopIdx)` — read by the
  [`DesktopShortcutsView`](../app/src/main/kotlin/io/github/dmulder/glashutte/desktop/DesktopShortcutsView.kt).
* Change listener notifies all subscribed views.

## Gestures

The cursor + touchpad model:

| Gesture                                  | Action                                                |
|------------------------------------------|-------------------------------------------------------|
| Tap a drawer icon                        | Launch the app (existing behavior)                    |
| Tap a desktop icon                       | Launch the app                                        |
| **Long-press** a drawer icon (~500 ms)   | Arm a drag carrying the app from the drawer           |
| **Long-press** a desktop icon (~500 ms)  | Arm a drag carrying the *placed* shortcut             |
| Tap on wallpaper while armed             | Drop (drawer drag = add; desktop drag = move/transfer)|
| Tap on the taskbar while armed           | Drawer drag → cancel; desktop drag → **delete**       |
| Tap on a window while armed              | Cancel                                                |

`TrackpadView` detects a one-finger hold that hasn't moved past the tap slop for
`LONG_PRESS_MS` (500 ms) and fires `onLongPress`; the `longPressFired` flag suppresses
the upcoming tap on `ACTION_UP` so a hold doesn't also act as a click.

While armed, the cursor renders a ghost copy of the icon. The original copy on the source
desktop is hidden until the drag finishes; cross-desktop transfers leave the source
visible until the drop succeeds.

## Wiring

```
TrackpadView.onLongPress
  → WorkspaceController.longPress()
    → WorkspaceRenderer.requestLongPress()
      → handleLongPress() (next frame)
        ├─ tryArmDrawerDragAt()           — drawer modal hit?
        └─ tryArmDesktopShortcutAt()      — placed shortcut hit on this slot?
              ↓ ArmedDrag(..., sourceSlotIdx)
        WorkspaceController.armDrawerDrag(drag)
            (cursor ghost renders each frame; drawer auto-closes)

Cursor click while armed:
  WorkspaceRenderer.handleClick()
    → handleArmedDrop()
        ├─ window / no-screen / drawer / settings → cancel
        ├─ taskbar zone + sourceSlotIdx != null   → removeArmedShortcut()
        ├─ taskbar zone + sourceSlotIdx == null   → cancel
        └─ wallpaper area                         → placeArmedDrop()
```

* [`WorkspaceController.drawerItemAt`](../spatial/src/main/kotlin/io/github/dmulder/glashutte/spatial/WorkspaceController.kt)
  — global hook (only one drawer can be open).
  Registered by [`DrawerView`](../app/src/main/kotlin/io/github/dmulder/glashutte/desktop/DrawerView.kt)
  while visible.
* `WorkspaceController.desktopShortcutLookupFor(slotIdx)` — per-slot lookup.
  Registered by each `DesktopShortcutsView` when attached.
* `WorkspaceController.placeArmedDrop` / `removeArmedShortcut` — wired by
  [`GlashutteApp`](../app/src/main/kotlin/io/github/dmulder/glashutte/GlashutteApp.kt) to the store; the
  rendering layer never touches `SharedPreferences` directly.

## Open follow-ups

* **Snap-to-grid** when dropping, for a cleaner look on the wallpaper.
* **Edit dialog** for a shortcut (rename, relaunch with parameters).
* **Restore on workspace start** — currently each `DesktopShortcutsView` reads the store
  in `onAttachedToWindow`; no separate boot pass.
