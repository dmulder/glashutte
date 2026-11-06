# DRM video and the native handoff ("flat") mode

Problem: in Netflix inside the workspace, previews play but real shows give sound and no picture.
That is protected video (Widevine) being blanked, not a bug in Netflix or in the workspace.

This document says why, what the app now does about it, what could not be tested without the
glasses attached, and exactly what to run and send back.

## 1. Why the picture is black

Glashutte shows each app by running it on a virtual display and re-rendering that display's
pixels in the GL workspace. Android's compositor (SurfaceFlinger) blanks a layer when:

```
blackOut = (layer has protected content AND target is not protected)
        OR (layer is FLAG_SECURE       AND target is not secure)
```

A virtual display is not secure and not protected by default, and the shell helper (uid 2000)
cannot make it secure: that needs `CAPTURE_SECURE_VIDEO_OUTPUT`, which only the platform holds.
So any design that copies another app's pixels into our own scene cannot show protected video.
That is also what stops screen recording and screenshots from capturing it. We do not try to
get around it.

The only legitimate way to show protected video is to let the platform send it to a display
that is allowed to show it: a real external display, composed by SurfaceFlinger itself, with
HDCP negotiated on the link. That is the "native handoff": run the app on the glasses' own
display, flat, with no copy.

## 2. What was added

| Piece | Where |
|---|---|
| DRM / secure-video probe (Widevine level, HDCP, secure decoders, display flags, helper view) | `glasses/.../DrmProbe.kt`, `DrmVerdict.kt`; helper half in `PrivilegedServer.probeSecureDisplay` |
| Native handoff (flat on the glasses' display, phone as trackpad) | `app/.../handoff/NativeHandoff.kt` |
| Cursor overlay on a non-secure window | `handoff/FlatCursorPresentation.kt` |
| Way back: notification action, Ctrl+Alt+H, "Return to workspace" button, auto-return when the app closes | `HandoffNotifier.kt`, `PrivilegedHotkeys.HK_RETURN_TO_WORKSPACE`, `MainActivity` |
| Always-flat app list (data file + persisted edits) | `glasses/src/main/assets/flat_apps_default.txt`, `FlatApps.kt` |
| Launcher UI: long-press an app in the drawer; "Open flat now" | `desktop/DrawerView.kt` |
| Phone quick controls (Back, Home, Play/Pause, Vol-/+, Return) | `res/layout/activity_main.xml`, `MainActivity` |
| 16:10 / readable-size handling and checks | `FlatFit.kt`, helper `handoffPrepare` |
| Phone-mirror fallback and per-app switch | `NativeHandoff.startMirror`, list editor |
| Secure-layer tests and Widevine playback test (public test vector) | `handoff/SecureLayerTest.kt`, `DrmTestActivity.kt` |
| Diagnostics block | `DrmSection.kt` (Diagnostics app) |

Everything logs under logcat tag `GlashutteDiag` and is included in Diagnostics, Share log:

- `DRM-PROBE ...` probe lines and verdicts
- `FLAT-ROUTE: <pkg> routed flat because it is on the always-flat list ...`
- `NATIVE-HANDOFF: ...` each step of a flat launch and return
- `FLAT-FIT [OK|WARN|FAIL] ...` aspect ratio and readable-size checks
- `SECURE-LAYER-TEST: ...` and `DRM-PLAY: ...` the tests

### 2.1 Flat + trackpad (preferred)

1. The workspace window on the glasses is made fully transparent and untouchable (alpha 0). It is
   kept alive underneath, so coming back is instant and nothing is torn down.
2. The helper sets the glasses display to the panel's native **1920x1200 (16:10)** if it reports
   anything else, and a **density of 240 dpi** (1280x800 dp: tablet-sized UI with readable icons).
   Both are restored on return, and a "pending restore" marker undoes them on the next start if
   the app was killed meanwhile.
3. The helper runs `am start --display <glasses> --windowingMode 1 -n <app>`. After 1.8 s it checks
   (`dumpsys`) that an activity really is on that display, and logs the display facts and the fit
   checks.
4. The phone trackpad now drives that display. Touches and wheel events are injected by the
   shell helper (the path from PR #6), one pointer in the glasses display's own pixels. A small
   cursor is drawn in a separate transparent, non-secure window. Keyboard and mouse input are
   forwarded too. Right-click is Back.
5. Return: the notification's "Return to workspace", the phone's Return button, Ctrl+Alt+H, or
   closing the app (polled every 3 s). Return force-stops the app (so audio does not keep
   playing; switch it off in settings), restores the display and shows the workspace again.

No Android API for a window to be "mirrored" is used here, so the app sees a normal external
display at a normal size.

### 2.2 Phone mirror (fallback, and a per-app choice)

The workspace window is dismissed and the app is started on the phone's own display. If the
glasses mirror the phone, the glasses show whatever the phone shows, touch works natively, and
the notification brings you back. Trade-offs, all inherent:

- The phone screen is busy and must stay on.
- No trackpad; the phone is the screen.
- The picture has the phone's shape (portrait, or letterboxed when the app goes landscape), not the
  panel's 16:10. This is the "plain screen mirroring" the owner rejected, so it is a fallback only.
  In "AUTO" the app uses it only if the flat launch did not land on the glasses display.
- Whether DRM survives depends on the phone's output to the glasses being HDCP-protected, which is
  the same question as for flat mode (section 3). If the glasses are only mirrored by a
  software path (a virtual display or casting), it is blanked just like the workspace.

### 2.3 The always-flat list

`flat_apps_default.txt` is data, one `package|Label|mode` per line. Defaults, by package:

| App | Package | Checked |
|---|---|---|
| Netflix | `com.netflix.mediaclient` | Play listing |
| Prime Video | `com.amazon.avod.thirdpartyclient` | Play listing |
| Disney+ | `com.disney.disneyplus` | Play listing |
| Hulu | `com.hulu.plus` | Play listing |
| Peacock | `com.peacocktv.peacockandroid` | Play listing, APK mirrors |
| Apple TV | `com.apple.atve.androidtv.appletv` | Play listing |
| VidAngel | `com.vidangel` | Play listing, APK mirrors (`com.vidangel.tvapp` is the TV edition) |
| Max / HBO Max | `com.wbd.stream`, `com.hbo.hbonow` | seen in several open-source package lists |
| Paramount+ | `com.cbs.app` | open-source package lists |
| Crunchyroll | `com.crunchyroll.crunchyroid` | open-source package lists |
| YouTube TV | `com.google.android.apps.youtube.unplugged` | open-source package lists |
| Google TV | `com.google.android.videos` | open-source package lists |
| YouTube / Premium | `com.google.android.youtube` | Premium is the same app; most of it is not protected, so remove it if you prefer |

Edits are stored as a difference from the file in `glashutte_flat_apps` SharedPreferences, so an
updated defaults file still reaches people who never touched an entry. Edit in the launcher
(long-press an app in the drawer: add or remove, change mode, "Open flat now") or in Diagnostics
(master switch, default mode, per-app mode, Open, Remove, add by package name, pick from installed
apps, reset). A marked cell (`▣`) in the drawer means "listed". Desktop shortcuts and every other
launch path go through the same hook.

## 3. What the probe can and cannot tell you

What Android 17 allows, from the platform source and earlier research
(`android-xr-workarounds-and-drm.md`):

- A virtual display never shows protected video. That cannot be changed from app or shell uid.
- A real external display is marked `FLAG_SECURE | FLAG_SUPPORTS_PROTECTED_BUFFERS` only after HDCP
  negotiation succeeds on its link. So whether flat mode works is decided by the hardware: does the
  Pixel's display HAL report HDCP on this DisplayPort-over-USB-C link to the glasses, and do the
  glasses' receiver and Viture's firmware negotiate it?
- That is not knowable from here. The probe reads it.

How to read the probe (`Run DRM probe`):

| Probe says | Meaning | What flat mode can do |
|---|---|---|
| Widevine L1, glasses `SECURE`, HDCP connected 2.x | Platform treats the glasses as a protected sink | Netflix HD and others should work flat |
| Widevine L1, glasses not `SECURE`, HDCP none / unknown | Link has no HDCP, or it is not reported while nothing protected plays | Check the Widevine test on the glasses. If it plays, apps will; if black with sound, flat mode cannot show DRM on this hardware |
| Widevine L3 | Software DRM only | Netflix plays at SD at most, and may refuse some titles |
| HDCP `none` on a connected display | Receiver or firmware does not negotiate HDCP | Protected video is blocked or downgraded; no software fix |
| Secure test: virtual display "blanked" | Expected, confirms the root cause | none |
| Secure test: glasses shows blue half | The glasses display accepts secure layers right now | flat mode should work |

The helper section of the probe adds: whether the shell uid can create a non-secure, trusted,
secure, and trusted+secure virtual display (all but the first are expected to be refused),
whether it holds `CAPTURE_SECURE_VIDEO_OUTPUT` (expected: no), desktop-mode and XR feature flags,
and the HDCP / secure lines from `dumpsys display` and SurfaceFlinger.

Alternatives if the probe says the link has no HDCP:

1. **The glasses' own video mode (side-by-side handoff).** Viture glasses have a mode that shows a
   video signal directly, outside Android's display stack. Whether a protected stream can reach it
   depends on HDCP on that same link, so it is only worth trying if the probe shows the link
   itself can carry HDCP. Not verified here.
2. **Netflix's own cast-to-display**, if the glasses or a dongle appear as a Cast receiver.
3. **Keep DRM apps on the phone** and use the glasses for everything else.

## 4. What was not tested

All of this was built and unit-tested without hardware. These are open until verified on the
Pixel 10 Pro XL, Android 17 CP3A.261005.005, with the Luma Ultra:

- Does the HWC report HDCP on the glasses link (the probe answers).
- Does `am start --display <glasses>` land on the glasses display in the phone's current output
  mode (extended vs mirror)? The launch log says. If the Pixel mirrors, there may be no separate
  display to launch on.
- Does `wm size 1920x1200 -d <id>` and `wm density` work on the physical glasses display, and does
  the restore put it back. The prepare/restore lines say.
- Does an alpha-0 workspace window above the app stay out of the secure path (it should, as
  SurfaceFlinger skips zero-alpha layers). If protected video blanks only while the workspace
  window is there, that is the cause.
- Does a second transparent window (the cursor) disturb protected video. It should not: it is a
  separate non-secure layer; protected content shows through. If it blanks the video, turn off
  "Draw a cursor" in Diagnostics.
- Does HOME on a secondary display do anything, and does the phone-mirror path preserve DRM.

## 5. Test procedure (for the owner)

Setup: install the debug APK, pair the helper (Wireless Debugging) as usual, connect the glasses,
open Glashutte so the workspace is up. Open "Glashutte Diagnostics" and find the block "DRM /
NATIVE HANDOFF".

A. Probe
1. With the glasses connected, tap **Run DRM probe**. Read the summary line.
2. Tap **Measure glasses fit**. Note the aspect and UI size lines.
3. Tap **Secure test: virtual display**: expect "blanked". Tap **Secure test: glasses** and look at
   the glasses for 30 s: do you see a red half, and is the other half blue, black, or missing?
4. Tap **Widevine test: glasses (flat)** and watch the glasses: picture, or sound only? Also run
   **Widevine test: phone** for comparison. The on-screen log says which decoder and what DRM did.
5. Scroll down and tap **Share log**. Send it back with what you saw in steps 3 and 4.

B. Flat + trackpad
1. Make sure Netflix is on the list (it is by default). In the workspace, open the app drawer and
   tap Netflix. The phone shows a bar with Back, Home, Play/Pause, Vol-, Vol+, and Return.
2. The glasses should show Netflix full-screen, 16:10, not letterboxed, with readable icons and
   text. Check: no black bars at the sides, text is readable, and the cursor follows the trackpad.
3. Use the trackpad: move, tap, tap-and-hold, drag, two-finger scroll; type with the phone keyboard
   or a Bluetooth keyboard. Start a real show and see whether there is a picture. Use Play/Pause and
   volume from the phone.
4. Return with each of: the **Return to workspace** button, the notification action, Ctrl+Alt+H.
   After returning, the workspace should be back and the glasses display at its normal size. Check
   Diagnostics, Measure glasses fit, which should show the idle display again.
5. Share log; the `FLAT-ROUTE`, `NATIVE-HANDOFF`, `FLAT-FIT` lines show each step.

C. Always-flat list
1. Long-press an app in the drawer: a strip appears. "Always open flat" adds it (a mark shows in the
   drawer); "Remove from flat list" takes it off; "Mode" switches flat + trackpad / phone mirror;
   "Open flat now" launches it flat once.
2. In Diagnostics, add a package by name and by "Pick an installed app", change a mode, Remove, and
   Reset to defaults. Restart the app: edits should persist.
3. Open a listed app and an unlisted app; the listed one goes flat, the other opens as a workspace
   window. The log shows `FLAT-ROUTE` only for the listed one.

D. Phone mirror
1. In the list set Netflix to "mirror" (or choose "Phone mirror" as the default). Open it from the
   drawer. The workspace goes away, Netflix opens on the phone, and the glasses should mirror it.
2. Note: does the glasses picture have Netflix video, or black with sound? Is the shape the phone's
   (narrow), and is the UI size readable? Use the notification to return.
3. Compare with B: this is what the owner called plain screen mirroring, so expect the shape problem.

E. Aspect ratio and readable size (in B and D)
- In flat mode `FLAT-FIT` should say `[OK] display shape: 1920x1200 is the panel's native 16:10`,
  `[OK] UI size: density 240 dpi -> 1280x800dp`, and `[OK] window fills display`.
- If the shape is `WARN` or `FAIL`, or the UI is too small or large, change the density in "Flat
  mode settings" (the -/+ buttons) and repeat.

## 6. Safety and limits

- Nothing captures, decrypts, records, or copies protected content. The handoff only chooses which
  display an app starts on; the platform decides what that display may show.
- The `wm size` / `wm density` changes are scoped to the glasses' display id, restored on every
  exit path, and tracked with a persistent marker for the crash case. The phone's own display is
  never changed.
- Settings are additive: the list, the target, density, cursor, and stop-on-return switches have
  defaults that keep the existing behaviour for apps that are not on the list.
