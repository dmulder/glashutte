# Debugging "apps show on the glasses but I cannot interact with them"

Use this when apps appear in their window on the glasses (head tracking and FOV are fine) but
the phone trackpad, a Bluetooth mouse or a Bluetooth keyboard do nothing to them.

Input reaches an app like this, and **everything depends on stage 0**:

```
0. Input helper connected (shell-uid process, bootstrapped over Wireless Debugging)
   |
   +-- phone trackpad : phone touch -> gesture -> cursor -> click -> hit-test -> helper -> InputManager
   +-- Bluetooth mouse: Android sees mouse -> helper reads it (or phone-window fallback) -> cursor -> click -> ...
   +-- Bluetooth keyboard: Android sees keyboard -> keys reach Glashutte -> target app chosen -> helper -> InputManager
```

Without the helper, apps can still be *shown* (they render on virtual displays), but nothing can be
sent *into* them, the phone hides the trackpad behind the setup screen, and the mouse and keyboard
do nothing. That is the most likely explanation for "visible but dead", and it is the first thing
the log reports.

## What the logs are

* Logcat tag `GlashutteDiag`. Lines look like `input[mouse]: cursor ...`.
* The **Glashutte Diagnostics** app icon. The top section is **INPUT**. It shows the helper line
  and one row per path with a **Test** and a **Share log** button.
* Apps can read only their own logcat, so the helper process cannot log to it directly. Glashutte
  asks the helper for a report (`probe`) and merges it in. Pressing **Share log** refreshes it first.

Every report starts with:

```
HELPER CONNECTED: YES|NO (state, reason)
INPUT VERDICT: ...
```

## Setup (once)

1. Install the APK, connect the glasses, open Glashutte and wait until the glasses show the workspace.
2. If the phone shows the **setup wizard** instead of the trackpad, the helper is not connected.
   Follow the numbered steps on screen (Wi-Fi on, Developer options, Wireless debugging ON, pair).
   If the notification does not show up, or pairing fails, tap **Pair here instead**, put Settings
   and Glashutte in split-screen so the pairing dialog stays open, and type the 6-digit code plus
   the pairing `IP:port` from the dialog. If connecting still fails, type the connect `IP:port`
   from the main Wireless debugging screen. The wizard shows the last failure reason in amber.
   No USB cable and no computer are needed.
3. Open the Diagnostics app. The INPUT section must say **HELPER CONNECTED: YES**. If it says NO,
   tap **Share log** on any path row and send it; the reason is in the first lines.
4. Launch one simple app (for example Settings or Chrome) on the glasses.

## Test A: phone as trackpad

1. Diagnostics > INPUT > **Test: trackpad**. It clears the counters and returns to the trackpad.
2. Check the phone is showing the trackpad (not a wizard). Move one finger about on it for a few
   seconds so the cursor moves on the glasses.
3. Tap once on a button inside the app window. Then two-finger drag (scroll) once.
4. Open Diagnostics again and tap **Share trackpad log**.

## Test B: Bluetooth mouse

1. Pair the mouse in Android Bluetooth settings first, and move it once to check Android sees it.
2. Diagnostics > INPUT > **Test: mouse**.
3. Move the mouse about, left-click on a button inside the app window, scroll the wheel.
4. Diagnostics > **Share mouse log**.

## Test C: Bluetooth keyboard

1. Pair the keyboard in Android Bluetooth settings.
2. Click a text field inside an app window with the trackpad or the mouse (so the target is chosen).
3. Diagnostics > INPUT > **Test: keyboard**, then type a few letters, Enter and Backspace.
   (The test returns to the main screen; the keyboard must be typed into that screen, not the
   Diagnostics app.)
4. Diagnostics > **Share keyboard log**.

The **Share** buttons attach the per-path report plus the filtered logcat. Send the three files.

## Reading the stages

Each path is a numbered list; the first stage that is not OK is where it breaks.

| Stage | If it fails |
|---|---|
| 0. Input helper connected | The setup is not finished or the helper died. Read the reason (state and last failure). Nothing below can work. |
| Phone shows trackpad | The wizard or waiting screen is on top. Finish setup / start the glasses workspace. |
| Touches reach view / gestures recognised | The phone's own touch handling is not firing; the trackpad view is not receiving the finger. |
| Cursor moves | Gestures arrive but the renderer is not being driven (look for `noRenderer`). |
| Click reaches renderer | A tap was seen but not queued (long press, or `clickSkippedDrag`). |
| Click lands on app window | The hit-test missed. The report lists the reason (no screen, outside rect, window chrome, drawer open, modal) plus the cursor NDC, slot pixel, window package, display id, local x/y and, for flat screens, a projection round-trip error. A round-trip error above about 1% would mean the ray/plane maths and the drawn geometry disagree. |
| Helper reaches InputManager | The helper is connected, but no route to `injectInputEvent` works. The injection line gives the strategy tried and the exception. |
| Dispatcher accepted | InputManager took the event but reported failure. Try a different route and the wait-for-result switch in Diagnostics, and use **Dump windows** to see focus on the target display. |
| Mouse: Android sees a mouse | The device is not reported as a mouse (some report as keyboard-only). The device inventory is in the report. |
| Mouse: helper reading the mouse | `/dev/input` not readable or no node grabbed (STATS opened/grabbed/rel). The phone-window fallback (switch, on by default) then drives the cursor from Android's own mouse events. |
| Keyboard: keys reach the phone window | `keyDown` counter zero and helper `keys` zero: Bluetooth keyboard is not connected, or Android routes it elsewhere. |
| Keyboard: typing target | No app was clicked yet, so there is nowhere to send keys. |

**Injection self-test** in Diagnostics taps the top-left pixel of every Glashutte display without
any phone input. If it succeeds but the paths fail, the problem is upstream of injection; if it
fails, the problem is the helper or InputManager and the injection line says why. **Dump windows**
prints the window/focus state of each Glashutte display from the helper.

## Experiments (additive switches, defaults are the old behaviour or safest)

Diagnostics > INPUT: injection route (auto, service-context, global, legacy-getInstance, binder,
shell-input), wait-for-result, own-focus display flag, forward physical keys, mouse phone-window
fallback. Defaults: auto, wait on, own-focus off, forward keys on, fallback on.

## What to send back

1. The three **Share** logs (trackpad, mouse, keyboard), even the ones that look obviously broken.
2. First line of the trackpad log: was it `HELPER CONNECTED: YES`?
3. The helper-connected result of the **Injection self-test** and, if it fails, the injection line.
4. Whether the wizard's amber failure text appeared, and what it said.
5. On the device, which of these happened: cursor moved on the glasses, click opened or pressed
   something, scroll worked, typed text appeared.
6. Android version and build (Pixel 10 Pro XL, CP3A.261005.005).

None of these changes have been run on hardware. Compilation and the unit tests for the
diagnostics model pass; every statement above about device behaviour is to be confirmed by the
logs.
