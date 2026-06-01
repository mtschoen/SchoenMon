# PerfStream — Agentic Coding System Spec

This repository is optimized for pair-programming with AI coding assistants (like Antigravity, Claude, and Gemini). This file serves as the core specifications, architecture map, visual conventions, and platform-specific gotchas to enable rapid, safe, and context-informed agentic coding.

---

## 1. Repository Layout

All active source code lives within the `app` Android module under `app/src/main/java/com/example/perfstream/`:

```
com/example/perfstream/
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
│   ├── Theme.kt                     # PerfStreamTheme (forced dark theme, disabled wallpaper-dynamic tinting)
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

PerfStream operates on a periodic, reactive unidirectional data flow. The main telemetry loop runs inside a dedicated Android `ForegroundService` and distributes stats to the Jetpack Compose dashboard:

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

PerfStream is built as a premium, ad-free cyberpunk hardware monitor. Visual guidelines:
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
C:\Users\mtsch\AppData\AndroidCLI\android.exe run --activity=com.example.perfstream.MainActivity
```

### Local Testing on Emulator
For rapid local testing using the pre-configured `Medium_Phone_API_36.1` emulator:
1. **VS Code Run & Debug (Play Button)**: In the Run & Debug panel on the left (or press `F5`), select and run **"Launch on Emulator"**. This will execute the pre-launch task to boot the emulator, wait for it to be ready, and then deploy and start the application dynamically in the terminal.
2. **VS Code Tasks**: Run the task `Start Emulator and Run App` (`Ctrl+Shift+B` or through the Tasks panel). This boots the emulator, waits for it to become ready, and then deploys/runs PerfStream.
3. **Standalone Script**: Double-click or run [run-on-emulator.bat](file:///c:/Users/mtsch/AndroidPerfMonitor/run-on-emulator.bat) from any terminal:
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
surfaces now exist under `com.example.perfstream.surface.*`, all fed by one fan-out call,
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

### Data Repository Lifecycle
- **Scope**: The rolling performance history buffer (capped at 60 entries / 2 minutes) lives purely in memory as a Singleton state in `PerformanceMonitorRepository`.
- **Behavior**: Stopping and starting the service preserves history *as long as the application process stays alive*. Killing the host application process will clear all charts. (In the future, room-based persistence could be introduced to support durable charts).
