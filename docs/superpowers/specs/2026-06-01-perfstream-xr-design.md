# PerfStream-XR Design (approved 2026-06-01)

> Working spec / handoff anchor for the next session. Status: design APPROVED, no
> code or toolchain installed yet. Next step: run `writing-plans` against this to
> produce a phased implementation plan, then implement.

## Goal

A spatial build of PerfStream for Samsung Galaxy XR (Android XR), shipped from the
SAME app module / APK that runs flat on phones. Two spatial elements:

1. The existing 2D dashboard floating as a grabbable `SpatialPanel`.
2. Centerpiece: an "Unknown Pleasures" (Joy Division) stacked ridgeline surface of
   per-CPU-core load history - each core is a lane, its ridge is that core's load
   across the rolling 60-sample window, lanes stacked in depth with front ridges
   occluding back ones.

## Architecture (one app, not a fork)

- Add Jetpack XR to the EXISTING app module: `androidx.xr.compose` + `androidx.xr.scenecore`
  (Developer Preview 4, May 2026). Pin exact versions during planning.
- Gate spatial UI on `LocalSpatialCapabilities.current.isSpatialUiEnabled`:
  - Non-spatial context (phones, the Folds, XR-2D fallback) -> existing flat
    `DashboardScreen`, completely unchanged.
  - Galaxy XR with spatial enabled -> `Subspace { ... }` spatial layout.
- One APK runs everywhere. Telemetry stack (`StatsCollector` -> `PerformanceMonitorRepository`
  StateFlow -> `PerformanceMonitorService`) is UNTOUCHED.

## The ridgeline surface

- **Data is already present.** Each `PerformanceStats` carries `cpuCoreFrequencies` and
  `cpuMaxFreqs` per core; `PerformanceMonitorRepository.history` holds 60 samples. Per-core
  load% per sample = `cur / max * 100`. No telemetry changes needed.
- **Lanes = CPU cores.** Each lane plots that core's load history (60 points). Stacked along
  Z (depth), offset in Y. Newest sample at the front edge; scrolls back each 2s tick.
- **Hidden-line occlusion comes free from 3D.** Give each ridge an opaque fill skirt below the
  line; stack lanes front-to-back; the depth buffer makes near lanes occlude far ones when
  viewed head-on. The 2D trick that made the album cover iconic is just how 3D works.
- **Rendering:** one procedural Custom Mesh ribbon per lane (DP4 Custom Meshes, experimental),
  rebuilt each tick as history scrolls. Material via `setBaseColorFactor(RGBA Vector4)`.
- **Aesthetic:** monochrome neon, cyan-on-black, faithful to Unknown Pleasures. Optional subtle
  tint by core cluster (little vs big cores) as a nod without breaking the cohesive look.

## Fallbacks (both real, both planned in)

- **Per-core sysfs empty on XR hardware** (same caveat as emulators: `/sys/.../cpufreq` may not
  be readable): lanes gracefully fall back to the 4 channels (CPU avg / RAM / NET / Disk) so the
  surface still pulses with signal instead of going flat.
- **DP4 Custom Mesh too raw / unstable to drive reliably:** build each ridge from many thin
  scaled-box segments (the rock-solid SceneCore primitive) instead. The look survives either way.

## Phasing (for the plan)

- **Phase A** - app compiles with Jetpack XR deps; spatial-capability gate; existing dashboard
  renders as a `SpatialPanel` on XR, flat on phone. Proves the toolchain end to end. Low risk.
- **Phase B** - the per-core ridgeline surface (Custom Mesh, with the scaled-box fallback ready;
  lane fallback to 4 metrics).
- **Phase C (optional)** - four live "now" bars (CPU/RAM/NET/Disk) as a SceneCore accent;
  deferred from v1 to keep focus on panel + surface.

## Risks

- DP4 is a moving preview: APIs shift release to release; expect churn.
- CPU may read 0% on XR hardware -> CPU lanes flat while RAM/NET/Disk fine. Not a bug.
- Custom Mesh is experimental in DP4 and its vertex/index API is thinly documented; verify
  against the live API reference / `android/xr-samples` during planning.

## Toolchain / deploy

- User owns the Android Studio Canary + Google Play XR system image + XR emulator setup
  (the Google-blessed path). SDK is at `C:\Users\mtsch\AppData\Local\Android\Sdk` (no XR image
  installed yet; only `android-36` / `android-36.1`).
- Galaxy XR is open: on-device APK sideloading (no PC needed) AND full adb (`adb connect <ip>`).
  The current 2D debug APK (`app/build/outputs/apk/debug/app-debug.apk`) should already run as a
  floating panel on the headset with no code changes - good for a "does it install" smoke test.

## Decided / open

- DECIDED: hybrid (panel + ridgeline surface); per-core lanes; monochrome cyan-on-black;
  now-bars deferred to Phase C; one-module spatial-capability gate.
- OPEN for planning: exact `androidx.xr.*` versions; Custom Mesh vs scaled-box decision pending a
  spike against DP4; surface dimensions / lane spacing / how many history points read well in 3D;
  whether the panel and surface share one `Subspace` row or are independently posed.
