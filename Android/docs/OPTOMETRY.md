# Optometry pseudo-app

A built-in Glashutte tool to help the user dial in the **focus wheels** on their VITURE
glasses — VITURE One / One Pro have per-eye dioptre adjustment knobs (~±5.0 D); Luma has
fixed lenses. An optometry-style chart is the standard way to know when you've nailed it.

## Why a pseudo-app

It's not a real Android app — there's no package to launch, no Shizuku call, no virtual
display for it. It is **Glashutte's own UI** drawn on its own `UiScreen`, the same way
[`DesktopPresentation`](../app/src/main/kotlin/io/github/dmulder/glashutte/desktop/DesktopPresentation.kt)
and [`DrawerPresentation`](../app/src/main/kotlin/io/github/dmulder/glashutte/desktop/DrawerPresentation.kt)
are. That keeps it instant to open, free of any launch failure modes, and able to render at
the workspace's native resolution.

## Where it lives

A toolbar button — see [TASKBAR.md](TASKBAR.md) for the layout — slotted between **Show
desktop** and **Search**. Tapping it overlays the chart on top of the workspace, full
bleed, hiding everything else (windows and the desktop wallpaper) so the chart is the only
thing the eye sees. An on-screen `×` (and the touchpad's Back) dismisses it.

## The chart

A **Snellen chart** is the canonical choice — 8–11 rows of black sans-serif letters on
white, each row roughly half the height of the row above. It's the chart VITURE's own setup
videos use and what most users already recognise. Five to six letters per row drawn from
the standard Snellen set (E, F, P, T, O, Z, L, P, E, D, …) at the standard size ratios
(20/200, 20/100, 20/70, 20/50, 20/40, 20/30, 20/20, 20/15, 20/10).

A second mode for non-Latin readers: the **Landolt C** (rings with a gap) or **Tumbling E**
(rotated Es) — same size progression, the user just judges sharpness of the orientation
rather than recognising a letter. Implemented as a toggle in a small corner; default is
Snellen.

## Per-eye masking

VITURE glasses present a side-by-side stereo image on a single panel — the left half goes
to the left eye, the right half to the right eye. To adjust one eye at a time without
asking the user to close the other one, the chart can blank the opposite half:

- **Both eyes** (default) — chart spans the workspace.
- **Left eye only** — right half of the workspace blanked black.
- **Right eye only** — left half blanked.

A small toggle in the corner cycles through the three modes.

## Implementation sketch

- New `OptometryPresentation : Presentation` on a `UiScreen` created and shown by
  `WorkspaceRenderer` when the taskbar button is tapped — mirrors the drawer's lifecycle
  (`WorkspaceController.setDrawerOpen` → renderer toggles overlay).
- Drawn full-bleed over the desktop and any windows (skip the windows in the renderer's
  draw loop when the overlay is open, same as the drawer's scrim handles the wallpaper).
- View hierarchy: a `LinearLayout` with the row of letters, plus a small bottom-right
  panel with the chart-style toggle, the eye-mask toggle, and a close `×`.
- Drawing the letters with vector fonts (`Theme.DeviceDefault`) is enough — the
  acuity-row sizes are computed from row height in dp.

**Touch points.** New `OptometryPresentation` + a `UiScreen` like the drawer's; a
`WorkspaceController.setOptometryOpen(open)` hook and the renderer's overlay-draw branch;
the taskbar button in `DesktopPresentation` (see [TASKBAR.md](TASKBAR.md)).

## Sources

- [Snellen chart — StatPearls (NCBI)](https://www.ncbi.nlm.nih.gov/books/NBK558961/)
- [Landolt C / Tumbling E comparison — Nature Scientific Reports](https://www.nature.com/articles/s41598-021-97875-3)
