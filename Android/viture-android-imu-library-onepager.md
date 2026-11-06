# VitureKit-Android — Project One-Pager

**Working title:** VitureKit-Android (placeholder; see Naming below)
**Status:** Draft v0.1
**Owner:** Glashutte project
**Last updated:** May 20, 2026

---

## Problem

The official VITURE Android SDK exposes head-tracking, IMU, and device-control APIs, but the surface is low-level: manual USB lifecycle handling, raw callback registration, byte-level IMU parsing, and no idiomatic Kotlin/coroutines or `Flow` interface. Every Android developer who wants to do anything beyond passive video display ends up reimplementing the same boilerplate.

On Linux there is already an EasyVXR wrapper that smooths over the C SDK's rough edges. Nothing equivalent exists for Android — and notably, **nothing exists that makes the IMU usable inside a Samsung DeX session**, which is the most common "glasses + phone as desktop" workflow today. Head-tracked UI, gesture input, stereo rendering, and 3DoF virtual-display apps on DeX are all blocked on this missing layer.

## Goals

- Provide a clean, idiomatic Kotlin library that wraps the official VITURE Android SDK.
- Expose IMU and device state as Kotlin `Flow`s with proper lifecycle scoping (no manual callback registration, no leaked USB handles).
- Work transparently in both standard Android and Samsung DeX sessions.
- Ship a small reference app demonstrating head-tracked input and a basic stereo-rendered view in DeX.
- Stay permissively licensed (MIT or Apache-2.0) so it can be vendored into other projects.

## Non-goals

- Reimplementing or reverse-engineering the closed VITURE SDK. The library is a wrapper; users still drop in the official `.aar` / `.so` themselves.
- Supporting non-VITURE glasses (Xreal, Rokid, etc.). Out of scope for v1.
- Full SLAM / 6DoF. The Pro Neckband + Unity path covers that; this project targets IMU-based 3DoF only.
- A finished productivity app (virtual monitor, etc.). Those are downstream consumers, not this library.

## Scope (v1)

| Area | In | Out |
|---|---|---|
| IMU access | 3DoF orientation, raw accel/gyro, configurable rate | Sensor fusion beyond what the SDK provides |
| Device control | Connect / disconnect, resolution switch, 2D/3D mode toggle | Firmware updates, low-level USB protocol |
| Surface | Kotlin `Flow`, coroutine-scoped, lifecycle-aware | Java-first API (Java interop is fine but not the design target) |
| DeX | Verified working in DeX sessions, sample app runs there | DeX-specific window-manager features |
| Reference app | Head-tracked cursor demo + minimal SBS stereo view | Full virtual-monitor product |
| Distribution | Maven Central (or JitPack initially), AGP 8.x | Gradle plugin, KMP targets |

## Approach

Thin Kotlin facade over the official SDK. The hot path is the IMU callback: the SDK invokes a Java callback at up to ~120 Hz, and the wrapper republishes it onto a `SharedFlow` with conflation and configurable backpressure. Device lifecycle (USB attach/detach, permission grants, resolution changes) is owned by a single `VitureSession` object bound to a `LifecycleOwner`, so connection state survives configuration changes and tears down cleanly. DeX support comes essentially for free since DeX is still Android — the work is verification, edge-case handling around display reconfiguration when the user enters/exits DeX, and making sure the reference app proves it end-to-end.

## Milestones

1. **M1 — Spike (1 week).** Get the official Android SDK building in a clean project. Confirm IMU callbacks fire on a phone connected to Luma / Pro. Confirm same works in a DeX session. Decision point: continue, or escalate gaps to VITURE.
2. **M2 — Core API (2 weeks).** `VitureSession`, IMU `Flow`, connect/disconnect, resolution control. Unit tests against a fake SDK shim. Published as a `-SNAPSHOT` on JitPack.
3. **M3 — Reference app (1 week).** Head-tracked cursor demo. Runs on phone and in DeX. Screen recording for the README.
4. **M4 — Stereo sample + polish (1 week).** Minimal SBS stereo renderer (OpenGL ES or simple `SurfaceView` pair) as a second sample. API review, KDoc pass, README, CONTRIBUTING.
5. **M5 — 1.0 release.** Tag, publish to Maven Central, announce on the `viture` GitHub topic and the relevant Reddit / Discord communities.

Total: roughly 5–6 weeks of part-time work.

## Risks & open questions

- **SDK licensing.** The official Android SDK's redistribution terms need to be read carefully before publishing anything that bundles or links it. Most likely outcome: users must download the SDK themselves and drop it in, following the EasyVXR precedent.
- **Luma Ultra support gap.** The XRLinuxDriver issue tracker suggests Luma Ultra needs an updated SDK from VITURE. If the Android SDK has the same gap, Luma Ultra users may be blocked until VITURE ships an update. Mitigate by testing on Pro first and documenting Luma Ultra status clearly.
- **DeX display-reconfiguration edge cases.** Entering/exiting DeX changes the active display set; the SDK may or may not handle re-enumeration gracefully. Needs empirical testing in M1.
- **USB permission UX on Android.** First-connect permission dialog is unavoidable but should not require code from the consumer of the library.

## Naming

Placeholder: `VitureKit-Android`. Alternatives to consider: `EasyVXR-Android` (continuity with the Linux wrapper, but risks confusion if maintainers differ), `vxr-kt`, `viture-flow`. Decide before M2.

## Open decisions for next pass

- Confirm hardware on hand: Pro vs. Luma vs. Luma Ultra. Affects test matrix and what M1 can actually verify.
- Pick license (Apache-2.0 is the safer default for a library others may vendor).
- JitPack first vs. Maven Central from day one.
