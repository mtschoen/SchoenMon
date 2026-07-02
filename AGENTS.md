# SchoenMon — Agentic Coding System Spec

This repository is optimized for pair-programming with AI coding assistants (like Antigravity, Claude, and Gemini). This file serves as the core specifications, architecture map, visual conventions, and platform-specific gotchas to enable rapid, safe, and context-informed agentic coding.

---

## 1. Repository Layout

All active source code lives within the `app` Android module under `app/src/main/java/com/sticktoitive/schoenmon/`:

```
com/sticktoitive/schoenmon/
├── MainActivity.kt                  # App entry point, edge-to-edge support, themes & navigation host
├── Navigation.kt                    # Navigation3 NavDisplay configuration (single 'Main' route wrapper)
├── NavigationKeys.kt                # Type-safe @Serializable data keys (e.g., 'object Main : NavKey')
│
├── core/
│   └── StatsCollector.kt            # Reads CPU frequencies (sysfs), network Rx/Tx speed, RAM, and Disk storage
│
├── data/
│   └── PerformanceMonitorRepository.kt # Singleton StateFlow data bus, holds current sample + rolling history
│
├── service/
│   └── PerformanceMonitorService.kt    # Foreground service: poll loop, dynamic resource-based notification icon
│
├── theme/
│   ├── Color.kt                     # Unified Obsidian and neon cyber color variables
│   ├── Theme.kt                     # SchoenMonTheme (forced dark theme, disabled wallpaper-dynamic tinting)
│   └── Type.kt                      # Jetpack Compose typography definitions
│
└── ui/
    ├── components/
    │   └── StatChart.kt             # Custom Canvas-drawn Bézier chart supporting dynamic line/fill gradients
    ├── dashboard/
    │   └── DashboardScreen.kt       # Main UI: performance grids, toggle, progress bars, & custom Canvas charts
    └── main/
        └── MainScreen.kt            # Entry screen wrapper delegating rendering directly to DashboardScreen
```

---

## 2. Architecture & Data Flow

SchoenMon operates on a periodic, reactive unidirectional data flow. The main telemetry loop runs inside a dedicated Android `ForegroundService` and distributes stats to the Jetpack Compose dashboard:

```text
       +-----------------------+
       |   StatsCollector      |  <-- Reads hardware details every 2 seconds
       +-----------+-----------+
                   |
                   | (PerformanceStats object)
                   v
       +-----------------------+
       | PerformanceMonitor-   |  <-- Updates persistent status bar notification with
       | Service (Foreground)  |      live metrics and a custom dynamic bitmap icon
       +-----------+-----------+
                   |
                   | (Repository.updateStats())
                   v
       +-----------------------+
       | PerformanceMonitor-   |  <-- Kotlin process-wide Singleton object
       | Repository            |      Houses MutableStateFlows for live + rolling 60-sample history
       +-----------+-----------+
                   |
                   | (collectAsStateWithLifecycle())
                   v
       +-----------------------+
       | Jetpack Compose UI    |  <-- Renders dynamic dashboard with Canvas-drawn
       | (DashboardScreen)     |      Bézier charts, neon gradients, and status switches
       +-----------------------+
```

---

## 3. Visual & Design Aesthetics

SchoenMon is built as a premium, ad-free cyberpunk hardware monitor. Visual guidelines:
- **Palette**: True obsidian dark background (`0xFF08080C`), dark slate cards (`0xFF12121A`), and high-contrast neon accents.
- **Accents**: 
  - **Cyber Cyan** (`0xFF00E5FF`) for CPU Metrics.
  - **Neo Green** (`0xFF00E676`) for Network Bandwidth.
  - **Electric Pink** (`0xFFD500F9`) for Memory Utilization.
  - **Vivid Amber** (`0xFFFFAB00`) for Storage Space.
- **Aesthetic Rules**:
  - Always enforce dark theme by default (`darkTheme = true` in `Theme.kt`).
  - Keep `dynamicColor = false` to prevent Android wallpaper/Material You color extraction from washing out or mutating our high-contrast cyberpunk identity.
  - Utilize gradient borders, subtle micro-animations (like the pulse on the `LIVE` status dot), and premium typography.

---

## 4. Build, Run & Deploy

Gradle and Android CLI options:

### Build from Terminal
```powershell
# Assemble Debug APK
.\gradlew.bat assembleDebug
```
*Outputs compiled debug APK (approx. 11.4 MB) under `app/build/outputs/apk/debug/app-debug.apk`.*

### Deploy to Emulator or USB-Connected Device
```powershell
# Installs and launches on the active target using the Android CLI tool
C:\Users\mtsch\AppData\AndroidCLI\android.exe run --activity=com.sticktoitive.schoenmon.MainActivity
```

### Local Testing on Emulator
For rapid local testing using the pre-configured `Medium_Phone_API_36.1` emulator:
1. **VS Code Run & Debug (Play Button)**: In the Run & Debug panel on the left (or press `F5`), select and run **"Launch on Emulator"**. This will execute the pre-launch task to boot the emulator, wait for it to be ready, and then deploy and start the application dynamically in the terminal.
2. **VS Code Tasks**: Run the task `Start Emulator and Run App` (`Ctrl+Shift+B` or through the Tasks panel). This boots the emulator, waits for it to become ready, and then deploys/runs SchoenMon.
3. **Standalone Script**: Double-click or run [run-on-emulator.bat](file:///c:/Users/mtsch/SchoenMon/run-on-emulator.bat) from any terminal:
   ```cmd
   .\run-on-emulator.bat
   ```

---

## 5. Hardware Folklore & Platform Gotchas

Keep these constraints in mind to avoid regressions or unexpected behavior during development:

### CPU Frequencies on Emulators
- **Limitation**: The Android kernel paths under `/sys/devices/system/cpu/cpu*/cpufreq/` (e.g. `scaling_cur_freq` and `scaling_max_freq`) **do not exist** on Android Emulators.
- **Handling**: `StatsCollector` catches listing and reading exceptions gracefully and falls back to empty/0 values.
- **Behavior**: On emulators, the RAM, Network, and Disk cards will populate perfectly, but the CPU Load card will display `0%` with no active cores. Do not mistake this for a code failure when testing on emulators.

### Foreground Service Special Use (Target SDK 36)
- **API Guardrails**: Modern Android OS targets (specifically Android 14+ / SDK 34-36) enforce extremely strict foreground service type rules.
- **Compliance**: We declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions in the manifest, configure `PerformanceMonitorService` as `foregroundServiceType="specialUse"`, and add the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` metadata referencing performance telemetry.
- **Rule**: Do not change this FGS configuration or remove permissions from `AndroidManifest.xml` without cross-referencing Android Play Store compliance guidelines, as it will trigger an immediate crash at startup.

### Dynamic Small Notification Icons (Samsung One UI Constraints)

**Working approach:** The service uses 11 pre-rendered vector drawable resources (`ic_stat_bars_0.xml` through `ic_stat_bars_10.xml`) and switches between them by resource ID every 2 seconds based on CPU load. Only **one notification** is posted. All stats are packed into the notification title/text.

**Why only one notification:** Samsung One UI auto-groups 2+ notifications from the same app under a system-generated group summary notification. That summary uses the app's adaptive launcher mipmap icon (resource `0x7f080000`), which appears in the status bar as the Android robot head, overriding the individual notification icons. This was confirmed via `dumpsys notification` showing `icon=Icon(typ=RESOURCE pkg=... id=0x7f080000)` on the auto-generated `Aggregate_NormalNotificationSection` record.

**Approaches tried and failed (do NOT re-attempt these):**

| Approach | Why it fails on Samsung One UI |
|---|---|
| `Icon.createWithBitmap()` with `ARGB_8888` | Samsung's `AppIconSolution` intercepts bitmap-backed icons and replaces them with the adaptive launcher icon. Logcat shows: `AppIconSolution: return adaptive icon for com.sticktoitive.schoenmon` |
| `Icon.createWithBitmap()` with `ALPHA_8` | `Canvas` cannot draw colored content onto `ALPHA_8` bitmaps; produces blank icons that fall back to launcher icon |
| Two notifications (separate IDs, separate channels) | Android auto-groups them → group summary uses launcher mipmap → robot icon replaces both |
| Two notifications + explicit `.setGroup(GROUP_KEY)` + custom `.setGroupSummary(true)` | Samsung shows only one icon per group in the status bar; icons alternate every few seconds |
| Two notifications + separate group keys (each its own summary) | Samsung still auto-groups across groups from the same package; falls back to launcher icon |
| `LevelListDrawable` via `setSmallIcon(R.drawable.ic_stat_bars, level)` | Not tested in isolation; was always combined with multi-notification approaches that failed for other reasons. May work but direct resource switching is proven safe. |

**What works:** A single notification with `setSmallIcon(R.drawable.ic_stat_bars_N)` using a direct resource ID from the `BAR_ICONS` array. This survives Samsung's `AppIconSolution` interception because it's a standard resource-backed icon with no bitmap indirection.

> **UPDATE 2026-05-31 - the "bitmap icons get rewritten to the robot" claim is STALE on One UI 8.5.**
> Re-tested on a physical Galaxy Z Fold 6 (One UI 8.5, SDK 36): a dynamically drawn
> `Icon.createWithBitmap()` 96x96 ARGB_8888 small icon renders **correctly, in full color, with
> live text**, in the Samsung status bar. The `AppIconSolution: return adaptive icon for ...` line
> still appears in logcat but is a **red herring** - it does NOT actually replace our notification's
> small icon (visually confirmed by the user: green numeric speed shows, no robot). So:
> - **Bitmap small icons are the way to get COLOR and NUMBERS into the Samsung status bar.**
>   Resource/vector icons are force-tinted monochrome; bitmaps keep their own colors.
> - The app now ships two such bitmap icons (see `surface/SpeedIcon.kt` = numeric ↓rate stacked
>   number-over-unit, and `surface/GraphIcon.kt` = CPU/RAM sparklines). Technique mirrors the
>   open-source NetSpeed Indicator (96x96, ARGB_8888, condensed-bold number + smaller unit).
> - The earlier all-bitmap failures were likely the *multi-notification* auto-group issue, not the
>   bitmap itself. One bitmap-icon notification is fine.
> - Caveat: only verified on One UI 8.5 so far; older One UI builds may differ - keep the static
>   vector arrow (`ic_stat_net_speed`) around as a fallback if a build regresses.

### Two side-by-side status bar icons from one app: NOT POSSIBLE (settled 2026-05-31)

Definitively confirmed against the live OS (not lore). Modern Android (verified on the
stock `Medium_Phone_API_36.1` emulator, SDK 36) **auto-groups** two ungrouped notifications
from the same package under a system `AUTOGROUP_SUMMARY` record, collapsing them to a single
status bar slot whose icon is the adaptive launcher icon (`0x7f080000`, the robot head).
`dumpsys notification` proof: the summary record carries
`flags=...|GROUP_SUMMARY|AUTOGROUP_SUMMARY` and `tag=...g:Aggregate_SilentSection`. So the old
"stock Android shows separate icons per notification" advice is **stale** (true only pre-Android-7,
before auto-grouping). It fails on stock AND Samsung. Do not reopen this; design ONE expressive
icon instead. (Samsung additionally hard-caps the status bar to 3 icons total in One UI 7+.)

### Glanceable surfaces beyond the status bar icon (added 2026-05-31)

The notification status-bar icon is no longer the only way to expose live stats. Three additional
surfaces now exist under `com.sticktoitive.schoenmon.surface.*`, all fed by one fan-out call,
`PerfSurfaces.refreshAll(context, stats)`, invoked from the service sampling loop each 2s tick.
All read from the shared `PerformanceMonitorRepository` singleton.

| Surface | Class | What it is | Honest limitation |
|---|---|---|---|
| Quick Settings tiles | `PerfTileService` (`CpuTileService`/`RamTileService`/`NetTileService`) | 3 active QS tiles showing CPU% / RAM / Net in the One UI quick panel | Only repaints while the shade is open; active tiles get 1 update per listen cycle. Pull-to-glance, not always-on. Verified rendering in QS panel on emulator. |
| Home widget (3x1) | `PerfWidgetProvider` + `res/layout/widget_perf.xml` | RemoteViews 3x1 strip (left sparkline + inline CPU/RAM/NET values), repainted every tick by the service | Only visible where placed; not an overlay over other apps. Declares `widgetCategory="home_screen\|keyguard"` but Samsung's native lock-screen picker excludes it - home screen only on One UI (see lock-screen finding below). |
| Now Bar Live Update | `LiveUpdateController` | Android 16 `ProgressStyle` promoted-ongoing notification → surfaces in Samsung Now Bar (status bar chip + lock pill) on One UI 8+ | Still a notification (appears in shade). SDK 36+ only (runtime-guarded). Verified `flags=...\|PROMOTED_ONGOING` + `android.template=ProgressStyle` granted by OS on emulator. |

Live Update gotchas (researched + verified):
- Promotion has **no public setter** on the platform `Notification.Builder`. The `NotificationCompat.Builder#setRequestPromotedOngoing` method exists, but when using platform `Notification.Builder` (required for `ProgressStyle`), set the extra directly: `extras.putBoolean("android.requestPromotedOngoing", true)` (the `EXTRA_REQUEST_PROMOTED_ONGOING` constant is `@hide`; the string value is documented/stable).
- Requires `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />` (non-runtime).
- Channel importance must be `> IMPORTANCE_MIN` (we use `IMPORTANCE_DEFAULT`).
- Must NOT use `setCustomContentView`/`RemoteViews` or `setColorized(true)` on the Live Update notification.

### Lock screen widgets: Samsung's native picker is curated, third-party AppWidgets excluded (settled 2026-06-01)

Confirmed empirically on the physical Galaxy Z Fold 6 (One UI 8.5, Android 16, SDK 36, build
`F956U1UEU3DZDP`). Our widget is already lock-screen-correct: `widget_perf_info.xml` declares
`widgetCategory="home_screen|keyguard"`, which is the entire AOSP requirement. On a Pixel running
Android 16 QPR2 it would appear in the lock-screen widget picker with zero extra code.

Samsung does NOT honor this in their native lock-screen widget picker on One UI 8.5: the picker
lists ONLY first-party Samsung widgets (Clock, Weather, Battery, etc.). The user confirmed our app
(and every other third-party app) is absent from it. The 2025 "One UI 8 to support third-party
lock-screen widgets" press was forward-looking and does not match this build's user-facing flow.
No code change on our side can enter a picker that structurally excludes all third-party
AppWidgets - do NOT re-attempt a Jetpack Glance rewrite or a lock-specific layout hoping to appear
there.

The ONLY route onto a Samsung lock screen is Good Lock -> LockStar (a manual, user-installed
customization module; not present on the test device). It consumes the existing home widget as-is,
so still zero app code. Decision: keep the `keyguard` category (free; future-proofs for Pixel/AOSP
and for LockStar) and do NOT build a dedicated lock-screen surface.

### Android XR (Galaxy XR) spatial build (added 2026-06-02)

SchoenMon ships a spatial build from the SAME module/APK (code under `ui/xr/`),
gated so phones/Folds are byte-for-byte unchanged. Phase A is complete and verified
on a physical Galaxy XR (SM-I610, SDK 34 / Android 14-based, connected via adb-TLS).
Active work + resume point: `docs/superpowers/plans/2026-06-12-xr-holotable.md`
(holotable redesign; Task 1 baseline already committed. The older
`2026-06-02-schoenmon-xr.md` plan's platform findings remain valid but its
visual direction is superseded - its retirement is that new plan's Task 10).

Verified API facts (Jetpack XR Developer Preview; `androidx.xr.compose`+`runtime`
`1.0.0-alpha14`, `scenecore` `1.0.0-alpha15`):
- **Gate:** `LocalSpatialCapabilities.current.isSpatialUiEnabled` - package
  `androidx.xr.compose.platform` (NOT `.spatial`). True only in Full Space.
  `SchoenMonRoot` branches: spatial -> `SpatialDashboard`, else flat `MainNavigation`.
- **Grabbable panel:** `SpatialPanel(SubspaceModifier...transformingMovable().resizable())`
  in a `Subspace`. `movable()` is NOT the grab modifier; `transformingMovable()` is.
- **Enter full space:** `LocalSpatialConfiguration.current.requestFullSpaceMode()`,
  shown only when `hasXrSpatialFeature` so phones never render it.
- **Manifest:** `<uses-feature android:name="android.software.xr.api.spatial" android:required="false">`
  + `<property android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE" android:value="XR_ACTIVITY_START_MODE_HOME_SPACE_MANAGED">`. Both ignored on non-XR.

Hard limitations (settled empirically - do NOT re-litigate):
- **No 3D content in home space** - it requires Full Space. No visionOS-style
  shared-space volume; the device's "auto-spatialization" only fakes a 2D stereo
  inset. See `~/.claude/notes/spike_xr-homespace-volume.md`.
- **No stable cube/box primitive** - SceneCore entities are `GltfModelEntity` /
  `PanelEntity` / `SurfaceEntity` only; procedural geometry only via the
  experimental `CustomMesh`/`MeshEntity`. The per-core "Unknown Pleasures" ridgeline
  needs `CustomMesh` (stacked panels can't do the occlusion without unreliable
  per-pixel panel transparency).

### Battery: sampling pauses while the screen is off (added 2026-06-02)

`PerformanceMonitorService` registers an `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`
receiver and fully cancels the 2s sampling loop while the display is off (every
glanceable surface is invisible then), resuming on screen-on with a network-counter
re-baseline (`StatsCollector.resetNetworkBaseline()`). The foreground service stays
foreground; only the poll loop pauses. This was the primary battery-drain fix -
verified on the Fold 3 (notification freezes across a sleep, resumes on wake).

> **XR EXCEPTION (added 2026-06-03):** Android XR headsets (SM-I610, SDK 34)
> report `PowerManager.isInteractive` = `false` even while the user is actively
> wearing the device (`dumpsys power` shows `mWakefulness=Asleep`). The
> `ACTION_SCREEN_ON` broadcast never fires. This silently prevented the sampling
> loop from starting on the headset. **Fix:** `isScreenOn()` now checks
> `packageManager.hasSystemFeature("android.software.xr.api.spatial")` and
> returns `true` unconditionally on XR devices. The OS kills the foreground
> service when the headset truly sleeps, so no battery drain risk.

### CustomMesh / MeshEntity lifecycle gotchas (added 2026-06-02)

Empirically verified on Galaxy XR (SM-I610) running Jetpack XR `scenecore`
`1.0.0-alpha15`. These are NOT documented — they were discovered through crash-iterate
cycles on the physical headset.

**`UBYTE4_NORM` is the only working vertex color type.** `FLOAT4` crashes at
`VertexAttributeDescriptor` creation with `IllegalArgumentException: Incompatible
type FLOAT4 for attribute COLOR`. Always use `VertexAttributeType.UBYTE4_NORM` (4
bytes per vertex, each 0–255, GPU normalizes to 0.0–1.0).

**`dispose()` does NOT remove child MeshEntity instances from the scene.** Calling
`childEntity.dispose()` on a MeshEntity parented to a root entity does NOT visually
remove it from the scene graph — it keeps rendering. Confirmed: accumulating children
produces visible z-fighting / overlapping geometry. **Working pattern:** swap the old
child's material to a fully transparent `KhronosPbrMaterial` (AlphaMode.BLEND,
baseColorFactor = (0,0,0,0)) via `setMaterial()` before disposing. Delayed disposal
(dispose on the NEXT tick, not the current one) prevents race conditions.

**`setParent()` is `@RestrictTo(LIBRARY)` — inaccessible from app code.** Even
though `Entity.setParent(Entity?)` is public in the interface, it's restricted at the
Kotlin compiler level. Casting to `Entity` doesn't help. Do not attempt to detach
children via `setParent(null)`.

**SceneCoreEntity volume bounds come from the root entity's mesh.** A degenerate
zero-area mesh at origin (all verts at (0,0,0)) causes the SceneCoreEntity to compute
zero-extent volume, which clips or collapses all child geometry. **Fix:** use a
"bounds mesh" — two zero-area triangles placed at opposite corners of the maximum
intended volume. The triangles render nothing (zero area) but define the bounding box
correctly. See `buildBoundsMesh()` in `RidgelineSurface.kt`.

**PBR material wash-out.** `KhronosPbrMaterial` with default settings (metallicFactor=1,
roughnessFactor=0) washes out vertex colors to near-white. For visible vertex colors:
`setBaseColorFactor(1.5, 1.5, 1.5, 1)`, `setMetallicFactor(0)`, `setRoughnessFactor(1)`,
`setEmissiveFactor(0.03, 0.03, 0.03)`. This lets vertex colors show through the PBR
diffuse without saturating.

**Sampling rate is 500ms for XR, 2000ms everywhere else.** The service delay was
reduced from 2000ms to 500ms to support fluid terrain extrusion in the XR
visualization. On XR the 60-sample history buffer covers 30 seconds instead of 2
minutes.

> **UPDATE 2026-07-02 - the 500ms cadence originally leaked to phones/Folds.**
> `PerformanceMonitorService.startSamplingLoop()` called `delay(500)` unconditionally,
> so non-XR devices also ticked 4x faster than the original 2s design (quadrupling
> background CPU-sysfs reads, notification IPC, QS-tile IPC, and widget/Live-Update
> refreshes any time the screen was on, whether or not the dashboard was even open).
> Found via static analysis while investigating a battery-drain report; on-device
> `dumpsys batterystats` couldn't confirm/deny because the phone had just died and
> reset its stats. Fixed by gating the delay on `isXrDevice` - phones are back to
> 2000ms (60-sample history back to a 2-minute window on phones; still 30s on XR).
> `BootReceiver` unconditionally starting the service on every boot (no user toggle)
> is intended behavior, not a bug - the notification is supposed to run in the
> background continuously; it just needs to be cheap while doing it.

### SurfaceEntity per-pixel alpha IS honored by the XR compositor (M0 verdict, 2026-06-12)

Spike-verified on the physical Galaxy XR (SM-I610, `scenecore` 1.0.0-alpha15): a
`SurfaceEntity` whose GL surface writes fragment alpha verbatim (0.5 on the terrain
body, 0.0 clear; `GL_BLEND` disabled, `EGL_ALPHA_SIZE 8` config, no `glColorMask`)
renders visibly see-through in Full Space - the passthrough room reads through the
colored surface. User-confirmed on-device. Per-pixel translucency for the holotable
hologram therefore comes straight from the surface texture; the CustomMesh
per-vertex-alpha fallback path is NOT needed.

### SurfaceEntity GL surface is sampled v-FLIPPED relative to GL conventions (found 2026-06-12)

GL renders bottom-up (NDC y=-1 = row 0 = bottom scanline of the queued buffer); the XR
compositor's Surface consumer samples top-down (v=0 = top scanline) and applies mesh
texCoords without the producer's flip transform. Net effect: the GL-rendered color
texture appears mirrored across the lane (z) axis relative to the TriangleMesh
geometry. Fix: render the grid top-down in the vertex shader
(`gl_Position` y = `1.0 - aGridPos.y * 2.0` in `TerrainGLRenderer.VERT_SRC`).
Status: v-flip fix user-confirmed (colors track geometry). A residual mirror/offset on
the OTHER axis (time flip or half-lane offset) was still under probe at session end -
see the holotable plan's resume block before treating orientation as settled.

### Per-tick reflective `setShape` does NOT recreate the surface (verified 2026-06-12)

Decompiled `SurfaceFeatureImpl.setShape` (scenecore-spatial-rendering 1.0.0-alpha15):
`setShape(CustomMesh)` only calls `ImpressApi.setStereoSurfaceEntityCanvasShapeCustomMesh`
on the same Impress node - no Surface/BufferQueue teardown. Confirmed live: 428/428
`eglSwapBuffers` succeeded (EGL_SUCCESS) across a ~4.5-minute capture with `setShape`
firing every 500ms tick. Deform-in-place via reflection is safe at tick rate.

### Galaxy XR adb-wifi dozes within minutes of doffing (workflow gotcha, 2026-06-12)

When the headset comes off the head it sleeps quickly, the Wireless-debugging socket
dies, and the mDNS advertisement goes STALE ("connection refused" on the advertised
port; sometimes no advertisement at all). Waking alone does not re-publish. Revival
procedure: wake the headset, toggle Wireless debugging off/on in Developer options
(re-publishes on a NEW port), `adb mdns services` to find it, `adb connect ip:port`.
A stale duplicate can linger in `adb devices` and break every adb call with "more than
one device" - `adb disconnect <stale-serial>` clears it. Plan headset-in-the-loop work
so the user keeps the device worn/awake through deploy-then-look iterations.

### Data Repository Lifecycle
- **Scope**: The rolling performance history buffer (capped at 60 entries / 30 seconds at 500ms sampling) lives purely in memory as a Singleton state in `PerformanceMonitorRepository`.
- **Behavior**: Stopping and starting the service preserves history *as long as the application process stays alive*. Killing the host application process will clear all charts. (In the future, room-based persistence could be introduced to support durable charts).
