# Contributing to Glashutte

## Ground rules

- **The VITURE SDK is never committed.** Not the `.so` files, not the `viture_*.h` headers,
  not a trimmed copy. CI fails if they appear in git. It is supplied locally; see the root
  `README.md`.
- **The build must work without the SDK.** Anything that needs the SDK lives in
  `glasses/src/sdkPresent` (compiled only when the SDK is found) behind the
  `GlassesBackend` interface; `glasses/src/sdkAbsent` provides the stub.
- **Keep `glasses/src/main` SDK-free** so it compiles and unit-tests on any machine.

## Development setup

- JDK 21, Android SDK platform 37.0, build-tools 36.0.0 (NDK 30.0.14904198 and CMake
  4.1.2 only for builds that include the SDK).
- Point `sdk.dir` in `local.properties` at your Android SDK.

## Before opening a pull request

1. `./gradlew :glasses:testDebugUnitTest :app:assembleDebug` passes without the SDK.
2. If you touched the SDK path, also build with `-Pviture.sdk.dir=...` and say so.
3. Changes that can only be verified on physical glasses or the Pixel should say so in the
   pull request description (add them to the hardware checklist in
   `docs/ARCHITECTURE.md`).

## Commit style

Small, focused commits with imperative subject lines.
