# Projection FOV calibration: how to A/B it on the glasses

The workspace camera used to render a fixed 55 degree vertical FOV into the centred band and
apply zoom to the *projection*. Neither matches the glasses' optics, so a world-locked screen
moved across the panel by only about 0.4-0.5x of the head rotation, which feels like the scene is
partly dragged along with your head.

The **calibrated projection** setting fixes both:

- The projection FOV is derived from the panel spec (Luma Ultra: 1920x1200, 52 degrees diagonal,
  which gives about 44.9 x 29.0 degrees across the full panel; about 24.2 degrees vertical for the
  83% band). Panel specs are looked up from the USB product id of the connected glasses
  (`PanelSpecs` in `PanelOptics.kt`); unknown products fall back to the Luma Ultra numbers and say
  so in the log.
- Zoom in FREE (world-locked) view scales the virtual displays in the scene instead of the
  projection, so it changes how big they are, not how far they move per degree of head rotation.
  PINNED (head-locked) view is unchanged apart from using the same FOV; its zoom is a magnifier.

## The switch

Diagnostics screen -> Experiments -> **Calibrated projection** (default ON). It applies live,
no restart. ON is the new behaviour; OFF restores the previous 55 degree / projection-zoom
behaviour exactly.

## What is logged

Tag `GlashutteDiag`, once when anything relevant changes and every 5 seconds:

```
projection CALIBRATED panel=Luma Ultra(pid=0x1101) 1920x1200@52.0degDiag surface=1920x1200 viewportH=996 fovY=24.19 view=FREE zoom=1.20 zoomOn=world centreGain=1.00
projection LEGACY     panel=Luma Ultra(pid=0x1101) 1920x1200@52.0degDiag surface=1920x1200 viewportH=996 fovY=55.00 view=FREE zoom=1.20 zoomOn=projection centreGain=0.49
```

`centreGain` is the predicted head-rotation-to-screen-movement ratio at the centre (1.0 is
correct). `SURFACE-ASPECT-MISMATCH` is appended if the glasses display is not the panel's aspect.
The same line is in the diagnostics report (`projection:`).

```bash
adb logcat -v time GlashutteDiag:I '*:S' | grep projection
```

## A/B procedure

1. Connect the glasses, open the workspace, wait for FREE (world-locked) view.
2. In Diagnostics, confirm the log line shows `surface=1920x1200` (or the panel's native mode) and
   `panel=Luma Ultra(pid=...)`, not "fallback".
3. Mark two points on a wall 20 degrees apart as seen from your seat (for example tape at
   `distance x tan(20 deg)` apart), or use a protractor on a desk.
4. Put a vertical edge of a virtual window on the centre of the view (recenter if needed).
5. Turn your head 20 degrees to the right and note where that edge ends up.
   - Correct (ON): about 89% of the way to the panel's left edge (20 / 22.5 degrees of half-width).
   - Legacy (OFF): about 44% of the way (gain about 0.49).
6. Flip the checkbox, repeat. Also compare by feel: ON should look "fixed in the room", OFF should
   look like it swings with you.
7. Pinch to zoom in both modes. ON: displays grow and head motion still maps 1:1. OFF: displays
   grow but the swing gets worse.
8. Layouts: repeat in Three SBS and Single Wide; the on-panel size at head-forward should be the
   same as OFF at the same zoom (that is intentional), only the motion differs.

Send back the log lines and whether step 5 matched the expected percentages. If it is off by more
than about 10%, the panel mode or the 52 degree figure for your unit needs checking.
