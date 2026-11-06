# Head-tracking latency settings

Four runtime switches in the **Glashutte Diagnostics** screen (launcher entry, section
"Latency experiments") control how a head pose travels from the glasses SDK to the frame that
is drawn. They apply **live** (no restart), persist across launches, and every change is logged.

| Setting | Default | What it does |
|---|---|---|
| Atomic pose, pulled on the render thread each frame | **ON** | Each pose is published as one immutable, timestamped value. The GL thread reads the newest one at the start of every frame (one JNI call, no allocation), so a frame never mixes components of two samples and there is no second poll loop between the SDK and the renderer. OFF = the previous behaviour: a poll loop pushes four separate volatile floats into the renderer. |
| Prediction mode | **Off** | `Off`: render the newest pose as reported. `SDK predict_time`: the amount is handed to the SDK's own pose prediction (Carina only). `App extrapolation`: the app rotates the newest pose forward by its measured angular velocity for `amount + pose age` (needs pull-on-render). |
| Prediction amount | 10 ms | Look-ahead used by the two prediction modes: 0, 5, 10, 15, 20, 30 or 40 ms. App extrapolation adds the pose's age on top and caps the total at 40 ms. |
| Select the glasses display's highest refresh rate | **ON** | Requests the fastest display mode that has the panel's current resolution (`preferredDisplayModeId` on the workspace window). OFF = leave it to the system. |

## Why these defaults

- *Pull-on-render* and *highest refresh* only change **when** the pose is read and how often the
  display scans out; neither can make the view less accurate, so they default ON.
- *Prediction* is the one change that can visibly overshoot (wobble when the head stops), and
  the right amount depends on the unit and firmware, which cannot be measured without the
  hardware. It therefore defaults to **Off** (today's behaviour) with a 10 ms starting amount,
  and is opt-in for A/B testing. Safeguards when it is on: total look-ahead is capped at 40 ms,
  velocity is faded in between about 1.7 and 8.6 deg/s so sensor noise at rest never jitters, and
  rotations faster than about 1700 deg/s (a re-anchor or glitch) are treated as "no motion".

## What happens underneath

- The native layer stamps every pose with `CLOCK_MONOTONIC` (the same clock as
  `System.nanoTime()`) the moment the SDK call returns. The stamp is therefore
  "SDK pose obtained at", not the sensor's own capture time; age figures do not include
  anything the SDK does before returning.
- With pull-on-render ON the native poll period tightens from about 8.3 ms to 4 ms, so the
  sample the GL thread reads is fresher. The Kotlin tracking loop stays alive only to run
  the diagnostics and the stream watchdogs; it no longer delivers poses.
- The head pose is recentred (heading only, as before) on the GL thread, then velocity is
  estimated over at least 16 ms of history, not between adjacent samples.
- SDK `predict_time` is handed to the Carina pose call. Gen1/2 (host IMU) and native-DoF
  paths have no such knob; "SDK predict_time" does nothing there.
- App extrapolation applies to orientation only; the 6DoF position is never extrapolated.

## Logging (`adb logcat -s GlashutteDiag`)

On every session start and whenever a setting changes:

```
latency settings active: pullOnRender=true prediction=APP(10ms) highestRefresh=true (sdk predict_time=0.0 ms, native poll 4000 us)
refresh rate: highestRefresh=true requested=mode 3 @120Hz supported=[1:1920x1200@60,3:1920x1200@120]
```

And a heartbeat every 5 seconds:

```
latency: {pullOnRender=true prediction=APP(10ms) highestRefresh=true} display: 1920x1200@120Hz (mode 3) [highestRefresh requested=mode 3 @120Hz] | render 119.6 fps dt p50=8.3 p95=8.9 max=13.1 ms | pose age p50=2.4 p95=4.1 max=5.8 ms (n=598) lookahead=12.4 ms | sdk pose rate 248 Hz
```

- `display: ...@NNHz` is the **live** mode of the glasses display: it shows whether the refresh
  request took effect.
- `render ... fps / dt` is the GL loop's real frame cadence (percentiles over the last window).
  If `dt p95` is well above the refresh period, the scene is too heavy for that rate.
- `pose age` is how old the sample was when a frame used it (render time minus SDK-return
  time). With pull-on-render OFF it is the age of the last pushed pose at render time, so the
  two modes are directly comparable.
- `lookahead` is the mean extrapolation applied by app extrapolation (0 in other modes).
- `sdk pose rate` is the native poll's successful calls per second.

The same line is also in the Diagnostics report ("Session" section, key `latency`) and in the
shared log export.

## A/B procedure (on the glasses)

Make one change at a time and watch for 20 to 30 seconds per setting. Use FREE (world-locked)
view. Keep `adb logcat -s GlashutteDiag | grep latency` running, or read the report.

1. **Baseline.** Turn pull-on-render OFF, prediction Off, highest refresh OFF. Note the
   heartbeat numbers and how a world-fixed window feels when you turn your head quickly and
   stop.
2. **Pull-on-render.** Turn it ON. Expect `pose age` p50/p95 to drop (baseline is roughly half
   a poll interval plus the Kotlin loop's interval) and `sdk pose rate` to rise to about 250 Hz.
   There should be no new visible artefact; the view should feel the same or slightly tighter.
3. **Refresh.** Turn "highest refresh" ON. Confirm `display:` now shows the higher rate and
   `render fps` follows it. If `dt p95` is much larger than one refresh period, or the picture
   judders, turn it OFF again and tell us (the scene may be too heavy for that rate).
4. **SDK prediction.** Set prediction to "SDK predict_time" and try 10, 15, 20, 30 ms with
   quick head turns then sudden stops. Too much shows as the scene overshooting and
   settling back; too little shows as the old "lag behind the head".
5. **App extrapolation.** Switch to "App extrapolation" and repeat the amounts (it adds the
   measured pose age, so use smaller amounts than in step 4, e.g. 0 to 10 ms). Compare against
   step 4 at the amount where each one looks best.
6. Leave the winner on. The defaults only change in code when the owner decides what the
   numbers and feel support.

Things to confirm on device:

- No crash or stall when toggling any switch while the workspace is running.
- `GlashutteDiag` shows the expected `latency settings active` line after each toggle.
- Tracking still recovers (Restart tracking button, USB replug) with pull-on-render ON.
- The "pose stream stalled" watchdog still fires if the glasses are unplugged mid-session.
- Heading recentre and the device recentre (Ctrl+Alt+R) still behave as before in both modes.
- Gen1/2 and native-DoF products (if available) still track with pull-on-render ON.
