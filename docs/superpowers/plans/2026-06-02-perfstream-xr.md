# PerfStream-XR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a spatial build of PerfStream for Samsung Galaxy XR (Android XR) from the same app module/APK that runs flat on phones: the existing 2D dashboard floating as a grabbable `SpatialPanel`, plus an "Unknown Pleasures" stacked-ridgeline surface of per-CPU-core load history.

**Architecture:** One app module, not a fork. Add Jetpack XR (`androidx.xr.compose` + `androidx.xr.scenecore`) to the existing module. A single spatial-capability gate (`LocalSpatialCapabilities.current.isSpatialUiEnabled`) chooses between the untouched flat `DashboardScreen` (phones, Folds, XR-2D fallback) and a `Subspace { ... }` spatial layout (Galaxy XR). The telemetry stack (`StatsCollector` → `PerformanceMonitorRepository` StateFlow → `PerformanceMonitorService`) is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Jetpack XR (Compose for XR + SceneCore), existing Navigation3 host.

---

## ⏸ RESUME HERE — session handoff (2026-06-02)

**Status (updated 2026-06-02, session 2):** Phase A COMPLETE + verified on real
hardware. Phase B: B0 DONE (Custom Mesh chosen), B1 DONE. B2 IN PROGRESS - a single
ridgeline ribbon renders correctly in a grabbable volume on the SM-I610, but the
per-core multi-lane version does NOT render (no crash, but no volume/ridgeline
visible). Handoff details below.

**Verified on the device, session 2:**
- B0 spike: a hard-coded `CustomMesh` sine ribbon (FLOAT3 POSITION, 32-bit Int
  index, `TRIANGLE_STRIP`) renders in Full Space. ✅ Custom Mesh is VIABLE.
- Material: bare `KhronosPbrMaterial` (no normals) looks like a blotchy reflection
  gradient; **self-lit emissive** (black base, metallic 0, roughness 1, emissive
  cyan) gives clean flat neon. ✅
- Single ribbon wrapped in `SceneCoreEntity(factory = { meshEntity },
  modifier = offset().transformingMovable().resizable())` rendered WITH working
  grab/move/resize volume chrome. ✅ (`SceneCoreEntity` is the `Volume` replacement.)

**Broken / open at handoff (session 2):**
- **Per-core multi-lane ridgeline does not render.** Reworked `RidgelineSurface.kt`
  to: build ONE combined multi-lane mesh per 2s tick from `ridgelineLanes()` over
  `PerformanceMonitorRepository.history`, wrap a persistent `GroupEntity` in the
  `SceneCoreEntity` volume, and swap the lane `MeshEntity` under the group each tick.
  Result: no crash, but nothing renders (no volume, no ridges). Suspects + likely
  fix in `~/.claude/notes/spike_xr_custom_mesh.md` ("no volume shows" section) -
  prime suspect is wrapping a *contentless* `GroupEntity` (no bounds) and
  hand-parenting the mesh under it instead of letting `SceneCoreEntity` manage a
  `MeshEntity` directly. First thing to try next session: wrap the combined lane
  `MeshEntity` itself in the `SceneCoreEntity` (as the single ribbon did), or give
  the group an explicit bounded size.
- **Per-tick mesh rebuild leaks** (KNOWN, intentional): `CustomMesh.close()` aborts
  natively if called while the retired `MeshEntity` still borrows the mesh, and the
  borrow releases only on the entity's GC (non-deterministic). Current code never
  closes meshes -> no crash, but ~tens of KB leak per tick. Full analysis +
  fix ideas in the spike note. Needs a real solution before this ships.
- On XR, per-core CPU sysfs is empty so `ridgelineLanes` uses the 4-channel
  CPU/RAM/NET/Disk fallback - that's expected, not a bug.

**Key findings (do NOT re-litigate):**
- **No 3D in home space** on Android XR - spatial content needs Full Space (spiked
  on device; `~/.claude/notes/spike_xr-homespace-volume.md`). No visionOS-style
  shared-space volume; "auto-spatialization" only fakes a 2D stereo inset.
- **No stable cube/box primitive** (SceneCore = GltfModelEntity / PanelEntity /
  SurfaceEntity only; procedural geometry only via experimental `CustomMesh`).
- **Stacked `SpatialPanel`s CANNOT produce the album occlusion** - it needs
  transparent-above / opaque-below per ridge (per-pixel panel transparency we can't
  rely on). `CustomMesh` (opaque fill skirt below the line, empty 3D space above,
  depth-buffer occlusion) is the CORRECT tool, so it is the PRIMARY path. Final
  user pick was open: CustomMesh primary vs a flat-2D-album-panel stepping stone.

**NEXT STEP when resuming (session 3):**
1. **Fix the "multi-lane renders nothing" regression** (the headline blocker). The
   single ribbon rendered when the `MeshEntity` itself was wrapped in
   `SceneCoreEntity`; it stopped when a contentless `GroupEntity` was wrapped and
   the mesh hand-parented under it. Try wrapping the combined lane `MeshEntity`
   directly in `SceneCoreEntity` (drop the GroupEntity), or give the group an
   explicit bounded `.width/.height/.depth`. Deploy to SM-I610 (`adb -t <id>`,
   enter full space, look right of the panel) to confirm. See the "no volume shows"
   section of `~/.claude/notes/spike_xr_custom_mesh.md`.
2. **Solve the per-tick mesh leak** (see spike note "resource-lifetime trap"): the
   current code never closes meshes to avoid a native borrow-abort. Find a real
   answer (updatable buffer? observe entity GC via WeakReference? pool? throttle?).
3. Tune the look once it renders: lane spacing (`LANE_RISE`/`LANE_DEPTH`/`AMPLITUDE`
   in `RidgelineSurface.kt`), confirm near-occludes-far reads correctly.
4. Then the experimental-render toggle the user wanted.

**Device note:** the SM-I610 connects via wireless adb-TLS and DROPS when the adb
daemon bounces (e.g. a stray `adb` version mismatch). Reconnect from the headset's
Wireless debugging screen; target it by `adb -t <transport_id>` (the mDNS serial
gains a `(N)` suffix on reconnect, which is awkward to quote). applicationId is
`com.sticktoitive.schoenmon`; launch via `adb -t <id> shell monkey -p
com.sticktoitive.schoenmon -c android.intent.category.LAUNCHER 1`.

The Phase A/B task detail below is historical; this block is the source of truth
for "where we are."

---

## ⚠️ Preview-API discipline (read before any task)

Jetpack XR is a **moving Developer Preview**. Two consequences shape this plan:

1. **No version numbers are hard-coded here.** Task A1 pins them from the live
   AndroidX release notes at execution time. Do NOT trust any version string
   from memory or training data.
2. **Symbol names in code blocks below are best-effort against the documented DP
   API shape, not guaranteed.** Every phase ends in a real compile/run gate.
   When a symbol has drifted, fix it against the live reference (URLs in each
   task) and update this plan inline - the plan is authoritative once execution
   starts.

**Canonical references to check at execution time:**
- Compose for XR release notes: https://developer.android.com/jetpack/androidx/releases/xr-compose
- SceneCore release notes: https://developer.android.com/jetpack/androidx/releases/xr-scenecore
- XR runtime release notes: https://developer.android.com/jetpack/androidx/releases/xr-runtime
- Develop UI for XR (Compose): https://developer.android.com/develop/xr/jetpack-xr-sdk/develop-ui
- SceneCore / 3D content: https://developer.android.com/develop/xr/jetpack-xr-sdk/add-3d-content
- Samples: https://github.com/android/xr-samples

## Prerequisites (environment - confirm before Phase A)

- **XR emulator OR physical Galaxy XR for spatial verification.** The SDK at
  `C:\Users\mtsch\AppData\Local\Android\Sdk` has only `android-36`/`android-36.1`
  and **no XR system image installed**. Spatial behavior (the gate's spatial
  branch, the panel, the ridgeline) cannot be verified without one of:
  (a) the Google Play XR system image + an XR AVD in Android Studio Canary, or
  (b) the physical Galaxy XR reachable via `adb connect <ip>`.
- **The flat-fallback path IS verifiable today** on the connected Folds
  (`SM-F926U`, `SM-F956U1`): Phase A's core guarantee - "non-spatial devices
  render the existing dashboard, completely unchanged" - is a regression test
  that runs on a phone with no XR hardware.
- This plan does NOT install the XR image or the headset connection; that is a
  user/environment step. Phase A tasks that need spatial hardware are marked
  **[needs XR target]**; do them when a target is available, but the flat-path
  regression and the build itself gate without it.

## Worktree

Execute in a dedicated worktree off `main` (preview-API churn will be noisy):
`feature/perfstream-xr`. The current branch `feature/glanceable-surfaces` carries
unrelated glanceable-surface work; keep XR isolated.

## File Structure

- **Create** `app/src/main/java/com/example/perfstream/ui/xr/PerfStreamRoot.kt`
  - The spatial-capability gate. One `@Composable` that branches flat vs spatial.
    Becomes the single content entry point called from `MainActivity`.
- **Create** `app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt`
  - The `Subspace { ... }` spatial layout: the dashboard `SpatialPanel` plus
    (Phase B) the ridgeline surface, posed in one subspace.
- **Create** `app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt`
  - Phase B. The per-core stacked ridgeline. Owns lane-data derivation from
    `PerformanceMonitorRepository.history` and the SceneCore rendering (scaled-box
    default, Custom Mesh spike-gated alternative).
- **Modify** `app/src/main/java/com/example/perfstream/MainActivity.kt`
  - Replace the inline `MainNavigation()` content with `PerfStreamRoot()`.
- **Modify** `app/build.gradle.kts` (or `app/build.gradle`)
  - Add the pinned Jetpack XR dependencies.
- **Modify** `app/src/main/AndroidManifest.xml`
  - Add the XR feature/property entries required to launch spatial.
- **Untouched:** everything under `core/`, `data/`, `service/`, `surface/`,
  `theme/`, and `ui/dashboard/DashboardScreen.kt`. The flat UI must not change.

---

## Phase A: Toolchain + spatial gate + dashboard as a floating panel

**Goal of phase:** App compiles with Jetpack XR deps. On non-spatial devices the
existing dashboard renders byte-for-byte as today. On a spatial target, the same
dashboard renders inside a grabbable `SpatialPanel`. Proves the toolchain end to
end. Low risk.

### Task A1: Pin Jetpack XR dependency versions ✅ DONE 2026-06-02

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Resolved versions** (AndroidX release notes, 2026-06-02; artifacts version on
independent trains - compose+runtime on alpha14, scenecore one ahead at alpha15;
`compose:1.0.0-alpha14` transitively depends on `scenecore:1.0.0-alpha15`,
confirming the cross-train pairing):
- `androidx.xr.compose:compose` / `compose-testing` = `1.0.0-alpha14`
- `androidx.xr.runtime:runtime` / `runtime-testing` = `1.0.0-alpha14`
- `androidx.xr.scenecore:scenecore` = `1.0.0-alpha15`

**Deviation from draft:** added via the existing version catalog
(`gradle/libs.versions.toml` → `libs.androidx.xr.*`) instead of raw coordinate
strings, matching the project's established `libs.*` convention. Intent (these
three artifacts, these versions) is unchanged.

- [x] **Step 1: Look up current versions** - resolved above.
- [x] **Step 2: Add the dependencies** - catalog entries + `implementation`/`testImplementation` wiring in `app/build.gradle.kts`. `compileSdk`/`targetSdk` 36, `minSdk` 24 unchanged (XR libs did not raise the floor).
- [x] **Step 3: Sync / resolve** - `:app:dependencies` resolved all XR artifacts cleanly; full `assembleDebug` BUILD SUCCESSFUL. Note: two non-fatal D8 `WARNING`s about `com.google.ar.core` stack-map tables come from the transitive ARCore jar (`scenecore` → `arcore:1.0.0-alpha14`); cosmetic, build passes. Relevant to Phase B (ridgeline uses scenecore).
- [x] **Step 4: Commit**

### Task A2: Manifest entries for spatial launch

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Entries added** (verified verbatim against the official `android/xr-samples`
manifest, 2026-06-02 - the docs pages punted, the sample is the source of truth):
- `<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />`
  (manifest level) - declares spatial use without blocking phone installs.
- `<property android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"`
  `android:value="XR_ACTIVITY_START_MODE_HOME_SPACE_MANAGED" />` (first child of
  `<application>`) - launches as a 2D panel; on XR it floats and can be promoted to
  full space. Same value the official hybrid sample uses; ignored on non-XR.

**Deliberately deferred:** `enableOnBackInvokedCallback="true"` on the activity.
It is only "recommended" for `SpatialPanel` GNav back events (spatial path,
untestable without a headset) and it changes phone back-dispatch behavior, which
would weaken the flat-regression guarantee. Add it with the spatial work once
testable on the device.

- [x] **Step 1: Verify required manifest entries** - via `android/xr-samples`.
- [x] **Step 2: Add the entries** - both phone-safe / install-only; no `required="true"`.
- [x] **Step 3: Build confirms manifest merges** - BUILD SUCCESSFUL, no merger errors.
- [x] **Step 4: Regression-test flat app on Fold 3** - `isForeground=true`; notification ticks live on wake (CPU 61%→71%), confirming the flat path is intact AND the battery fix still pauses/resumes with XR deps in.
- [x] **Step 5: Commit**

### Task A3: Spatial-capability gate (`PerfStreamRoot`)

**Files:**
- Create: `app/src/main/java/com/example/perfstream/ui/xr/PerfStreamRoot.kt`
- Modify: `app/src/main/java/com/example/perfstream/MainActivity.kt`

Code now lives in `PerfStreamRoot.kt` / `SpatialDashboard.kt` (stub) /
`MainActivity.kt` - the plan no longer duplicates it.

**Drift fixed during execution:** the draft imported
`androidx.xr.compose.spatial.LocalSpatialCapabilities`; the real package is
`androidx.xr.compose.platform.LocalSpatialCapabilities` (verified against
`android/xr-samples` `HelloAndroidXRApp.kt`). `.current.isSpatialUiEnabled` and
the `if/else` gate shape were correct.

- [x] **Step 1: Write the gate composable** (`PerfStreamRoot`).
- [x] **Step 2: `SpatialDashboard` stub** (filled by A4).
- [x] **Step 3: Route `MainActivity` through the gate**.
- [x] **Step 4: Build** - BUILD SUCCESSFUL.
- [x] **Step 5: Regression-test flat path on Fold 3** - notification live (33%→45%), `MainActivity` is the ResumedActivity, no FATAL/AndroidRuntime crash. Gate routes to flat on the phone.
- [x] **Step 6: Commit**.

### Task A4: Dashboard as a grabbable `SpatialPanel`

**Files:**
- Modify: `app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt`

Code lives in `SpatialDashboard.kt`: a `Subspace { SpatialPanel(...) { Surface {
DashboardScreen() } } }`. **Drift fixed:** draft used `.movable()`; the real
grab-and-resize pair is `.transformingMovable().resizable()`
(`androidx.xr.compose.subspace.layout.*`, verified against `android/xr-samples`
panel usage).

- [x] **Step 1: Implement the spatial panel** - single panel hosting the untouched flat `DashboardScreen`.
- [x] **Step 2: Build** - BUILD SUCCESSFUL; flat path still resumes clean on the Fold 3 (no regression).
- [ ] **Step 3: [needs XR target] Verify spatial render** - panel floats / grabs / resizes; 4 cards populate (CPU may read 0% on XR). **PENDING headset/XR emulator.**
- [ ] **Step 4: [needs XR target] "does it install" smoke test** on the headset. **PENDING.**
- [x] **Step 5: Commit** (code + build).

> **Phase A is CODE-COMPLETE and builds green.** The only open items are the two
> `[needs XR target]` spatial-render verifications - they need the Galaxy XR
> (`adb connect`) or the XR emulator image, neither connected as of 2026-06-02.

```bash
git add app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt
git commit -m "feat(xr): render dashboard as a grabbable SpatialPanel"
```

**End of Phase A.** Toolchain proven; one APK runs flat on phones and spatial on
XR. This is a coherent, shippable milestone - a natural pause point if Phase B is
deferred.

---

## Phase B: Per-core "Unknown Pleasures" ridgeline surface

**Goal of phase:** A stacked ridgeline of per-CPU-core load history beside the
panel - each core a lane, its ridge that core's load across the rolling 60-sample
window, lanes stacked in depth so near ridges occlude far ones.

**Decision gate up front:** the spec leaves "Custom Mesh vs scaled-box" open
"pending a spike against DP4." Task B0 runs that spike and **picks the rendering
technique**. Tasks B2/B3 are the two branches; execute only the chosen one.

### Task B0: Spike - Custom Mesh viability on the live DP ✅ DONE 2026-06-02

**DECISION: Custom Mesh is VIABLE ⇒ Phase B uses Task B2 (Custom Mesh). B3 (scaled-box) is SKIPPED.**
A hard-coded sine ribbon (FLOAT3 POSITION, 32-bit Int index, `TRIANGLE_STRIP`)
rendered correctly on the physical SM-I610 in Full Space, first try. Full API
shape, gotchas, and the stale-alpha14-sources trap are in
`~/.claude/notes/spike_xr_custom_mesh.md` (registered). One rough edge: bare
`KhronosPbrMaterial` (no vertex normals) renders a blotchy reflection gradient -
fix is self-lit emissive (black base, metallic 0, roughness 1, emissive cyan).

**REQUIRED SUB-SKILL:** Use superpowers:running-spikes (new-project template ⇒
announce-and-go in a scratch dir; breadcrumb to `~/.claude/notes/spike_xr_custom_mesh.md`).

- [x] **Step 1: Spike the Custom Mesh API**

In a throwaway spike (or a scratch composable behind a debug flag in this app),
attempt to build ONE procedural ribbon mesh from a vertex/index list via the
SceneCore mesh API and apply a material with `setBaseColorFactor` (verify exact
names on the "add 3D content" reference + `android/xr-samples`). Drive it from a
hard-coded 60-point array.

- [x] **Step 2: Decide and record** - done; see the DECISION block above. Custom
  Mesh present + renders on device ⇒ B2. B3 skipped.

### Task B1: Lane-data derivation (technique-independent, TDD)

**Files:**
- Create: `app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt`
- Test: `app/src/test/java/com/example/perfstream/ui/xr/RidgelineDataTest.kt`

This is pure data shaping over the existing history buffer - fully testable on
the JVM with no XR hardware. Do it TDD.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.perfstream.ui.xr

import com.example.perfstream.core.PerformanceStats
import org.junit.Assert.assertEquals
import org.junit.Test

class RidgelineDataTest {
    private fun stat(cur: List<Long>, max: List<Long>) = PerformanceStats(
        cpuCoreFrequencies = cur, cpuMaxFreqs = max,
        rxSpeedBytesPerSec = 0, txSpeedBytesPerSec = 0,
        totalMemoryBytes = 0, availableMemoryBytes = 0,
        totalDiskBytes = 0, availableDiskBytes = 0,
    )

    @Test
    fun perCoreLoadFraction_isCurOverMax_clampedToUnit() {
        val history = listOf(
            stat(cur = listOf(1000L, 500L), max = listOf(2000L, 2000L)),
            stat(cur = listOf(2000L, 0L),   max = listOf(2000L, 2000L)),
        )
        val lanes = ridgelineLanes(history, coreCount = 2)
        // lane 0 = core 0 across both samples; lane 1 = core 1.
        assertEquals(listOf(0.5f, 1.0f), lanes[0])
        assertEquals(listOf(0.25f, 0.0f), lanes[1])
    }

    @Test
    fun fallsBackToFourChannels_whenNoPerCoreData() {
        val history = listOf(stat(cur = emptyList(), max = emptyList()))
        val lanes = ridgelineLanes(history, coreCount = 0)
        // 4 fallback lanes: CPU avg / RAM / NET / Disk - all present, never empty.
        assertEquals(4, lanes.size)
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*RidgelineDataTest*"`
Expected: FAIL - `ridgelineLanes` unresolved.

- [ ] **Step 3: Implement `ridgelineLanes`**

In `RidgelineSurface.kt`, add the derivation. Per-core load fraction =
`cur/max` clamped to `[0,1]`; lane `c` = that fraction for core `c` across every
history sample (oldest→newest). When per-core data is absent (`coreCount == 0`,
the emulator/XR sysfs caveat), fall back to 4 lanes from the existing aggregate
metrics so the surface still pulses:

```kotlin
package com.example.perfstream.ui.xr

import com.example.perfstream.core.PerformanceStats

/**
 * Build one lane per CPU core: each lane is that core's load fraction (cur/max,
 * clamped) across the rolling history, oldest first. When per-core frequencies
 * are unavailable (XR/emulator sysfs restriction), fall back to four lanes from
 * the aggregate CPU/RAM/NET/Disk signal so the ridgeline never goes flat.
 */
fun ridgelineLanes(history: List<PerformanceStats>, coreCount: Int): List<List<Float>> {
    if (coreCount <= 0) return fallbackLanes(history)
    return (0 until coreCount).map { core ->
        history.map { s ->
            val cur = s.cpuCoreFrequencies.getOrNull(core) ?: 0L
            val max = s.cpuMaxFreqs.getOrNull(core) ?: 0L
            if (max > 0L) (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
        }
    }
}

private fun fallbackLanes(history: List<PerformanceStats>): List<List<Float>> {
    fun lane(select: (PerformanceStats) -> Float) = history.map { select(it).coerceIn(0f, 1f) }
    return listOf(
        lane { it.avgCpuFrequencyPercent / 100f },
        lane { if (it.totalMemoryBytes > 0) it.usedMemoryBytes.toFloat() / it.totalMemoryBytes else 0f },
        lane { (it.rxSpeedBytesPerSec / (10f * 1024 * 1024)) }, // 10 MB/s full-scale
        lane { if (it.totalDiskBytes > 0) it.usedDiskBytes.toFloat() / it.totalDiskBytes else 0f },
    )
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*RidgelineDataTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt app/src/test/java/com/example/perfstream/ui/xr/RidgelineDataTest.kt
git commit -m "feat(xr): per-core ridgeline lane derivation with 4-channel fallback"
```

### Task B2: Render ridgeline via Custom Mesh  *(only if B0 chose Custom Mesh)*

**Files:**
- Modify: `app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt`
- Modify: `app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt`

- [ ] **Step 1: Build one ridge ribbon mesh per lane**

Using the exact mesh/material API confirmed in B0, write `RidgelineSurface()` (a
spatial composable / SceneCore entity setup): for each lane, build a ribbon mesh -
a line of `width` segments at `y = baseY + laneIndex * laneGap`, vertex height =
`load * amplitude`, with an opaque fill skirt down to the lane's baseline so near
lanes occlude far ones via the depth buffer. Material colour = monochrome neon
cyan via `setBaseColorFactor`. Rebuild the meshes each tick as history scrolls
(collect `PerformanceMonitorRepository.history` with
`collectAsStateWithLifecycle`). Exact code is written against the B0-confirmed
API; record it here in this step once B0 fixes the symbols.

- [ ] **Step 2: Place it in the subspace beside the panel**

In `SpatialDashboard`, add `RidgelineSurface()` to the `Subspace`, posed beside
the `SpatialPanel` (offset along X; tune spacing on-device).

- [ ] **Step 3: Build + [needs XR target] verify**

Run: `.\gradlew.bat assembleDebug -q` (Expected: SUCCESSFUL). Then on the XR
target confirm the stacked ridgeline renders, scrolls each 2s tick, and near
lanes occlude far ones.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt
git commit -m "feat(xr): Unknown Pleasures ridgeline via Custom Mesh"
```

### Task B3: Render ridgeline via scaled-box segments  *(SKIPPED - B0 chose Custom Mesh)*

> **SKIPPED.** B0 confirmed Custom Mesh renders on the SM-I610, so the ridgeline
> goes through B2. This branch is retained only as the fallback-of-record.

**Files:**
- Modify: `app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt`
- Modify: `app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt`

- [ ] **Step 1: Build each ridge from thin scaled boxes**

Render each lane as a row of thin box entities (the rock-solid SceneCore
primitive): one box per history point, positioned at
`x = pointIndex * step`, `y = baseY + laneIndex * laneGap`, scaled vertically by
`load * amplitude`, depth-stacked by lane so the depth buffer gives free
occlusion. Reuse a per-lane pool of box entities and just rescale/reposition them
each tick (avoid per-tick entity churn). Colour via the SceneCore material API
confirmed in B0. Write the concrete entity setup here against that confirmed API.

- [ ] **Step 2: Place it in the subspace beside the panel** (same as B2 Step 2)

- [ ] **Step 3: Build + [needs XR target] verify** (same gate as B2 Step 3)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/perfstream/ui/xr/RidgelineSurface.kt app/src/main/java/com/example/perfstream/ui/xr/SpatialDashboard.kt
git commit -m "feat(xr): Unknown Pleasures ridgeline via scaled-box segments"
```

**End of Phase B.** Panel + ridgeline both live on the headset; flat phone build
unchanged throughout.

---

## Phase C (optional, deferred): four live "now" bars

Deferred from v1 per the spec to keep focus on panel + surface. When picked up:
four live CPU/RAM/NET/Disk bars as a SceneCore accent in the same subspace, fed by
`PerformanceMonitorRepository.stats`. Same render-technique decision as B0 applies;
reuse the chosen primitive. Detailed tasks to be written when Phase C is scheduled.

---

## Definition of done (Phases A + B)

- One APK; phones/Folds render the existing dashboard with **zero** visible change
  (verified on a connected Fold).
- On a spatial target: dashboard floats as a grabbable/resizable `SpatialPanel`,
  with the per-core ridgeline beside it scrolling each 2s tick.
- Telemetry stack (`core/`, `data/`, `service/`, `surface/`) untouched.
- No XR `<uses-feature required="true">` that would hide the app from phones.
- Branch-finish: fold durable insight (the spatial gate pattern, the B0
  mesh-technique decision, the lane-fallback rationale) into real docs - update
  `AGENTS.md` §2 (architecture) and add an XR section - then delete this plan.
