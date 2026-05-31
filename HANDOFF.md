# PerfStream — Session Handoff Spec

> **Project**: PerfStream — a unified, ad-free Android performance monitor  
> **Location**: `C:\Users\mtsch\AndroidPerfMonitor`  
> **Last Build**: `BUILD SUCCESSFUL` — `app-debug.apk` (11.4 MB) at `app/build/outputs/apk/debug/`  
> **Created**: 2026-05-31  

---

## 1. What This Project Is

A lightweight Android app that replaces multiple competing system-monitor apps (which fight over the notification bar, causing icon flickering) with a **single unified foreground service** that collects CPU, network, memory, and storage metrics and presents them in:

1. **A persistent status bar notification** — one service, one notification, no flickering.
2. **A Jetpack Compose dashboard** — premium dark-themed UI with real-time Canvas-drawn Bézier charts.

The user wants this to be a simple, clean starting point they can evolve over time. No ads, no tracking, no upsells.

---

## 2. Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Min SDK | 24 (Android 7.0) |
| Target/Compile SDK | 36 |
| Build System | Gradle 9.1 (Kotlin DSL), AGP via version catalog |
| Navigation | AndroidX Navigation3 |
| Charts | Custom `Canvas`-drawn (no third-party library) |
| Package ID | `com.example.perfstream` |

**Android CLI tool** is installed at `C:\Users\mtsch\AppData\AndroidCLI\android.exe` and can be used for `android run`, `android docs search`, emulator management, etc.

---

## 3. File Map

All source lives under `app/src/main/java/com/example/perfstream/`:

```
com/example/perfstream/
├── MainActivity.kt              # Entry point, enableEdgeToEdge, hosts theme + nav
├── Navigation.kt                # Navigation3 NavDisplay setup (single Main route)
├── NavigationKeys.kt            # @Serializable data object Main : NavKey
│
├── core/
│   └── StatsCollector.kt        # ★ Reads CPU freq, network bandwidth, RAM, disk
│
├── data/
│   ├── DataRepository.kt        # Scaffolded template (unused, can be deleted)
│   └── PerformanceMonitorRepository.kt  # ★ Singleton StateFlow bridge: live + history
│
├── service/
│   └── PerformanceMonitorService.kt     # ★ ForegroundService: poll loop, notification
│
├── theme/
│   ├── Color.kt                 # Default Material color tokens
│   ├── Theme.kt                 # PerfStreamTheme with dynamic color support
│   └── Type.kt                  # Typography definitions
│
└── ui/
    ├── components/
    │   └── StatChart.kt         # ★ Custom Canvas Bézier chart with gradient fill
    ├── dashboard/
    │   └── DashboardScreen.kt   # ★ Main dashboard: cards, charts, service toggle
    └── main/
        ├── MainScreen.kt        # Thin wrapper delegating to DashboardScreen
        └── MainScreenViewModel.kt  # Scaffolded template (unused, can be deleted)
```

Files marked ★ are the core of the app. Files noted "unused, can be deleted" are scaffolding leftovers from the `android create` template.

### AndroidManifest.xml

Declares:
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` permissions
- `PerformanceMonitorService` with `foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` metadata

---

## 4. How It Works

### Data Collection (`StatsCollector.sample()`)

| Metric | Source | Notes |
|--------|--------|-------|
| CPU core frequencies (MHz) | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` | No root needed. Offline cores return 0. Max freqs read from `scaling_max_freq` to compute %. |
| Network speed (bytes/s) | `TrafficStats.getTotalRxBytes/TxBytes()` | Delta-based: stores previous sample and computes rate. |
| RAM (bytes) | `ActivityManager.getMemoryInfo()` | Total and available; used = total - available. |
| Disk (bytes) | `StatFs(Environment.getDataDirectory())` | Block count × block size. |

> **Important limitation**: True system-wide CPU *percentage* (from `/proc/stat`) is blocked on Android 8+. We use core frequency as a proxy. Adding Shizuku support later could unlock `/proc/stat` without root.

### Service Loop

`PerformanceMonitorService` starts as a `ForegroundService`, creates a coroutine that calls `StatsCollector.sample()` every **2 seconds**, pushes the result into `PerformanceMonitorRepository` (a singleton with `MutableStateFlow`), and updates the notification text + dynamic bitmap icon.

### UI Binding

`DashboardScreen` collects `PerformanceMonitorRepository.stats` and `.history` via `collectAsStateWithLifecycle()`. The history buffer is capped at 60 entries (2 minutes of data). Each metric card has its own `StatChart` showing the rolling history.

---

## 5. Build & Run

```powershell
# Build
cd C:\Users\mtsch\AndroidPerfMonitor
.\gradlew.bat assembleDebug

# Deploy to connected device (USB debugging must be enabled)
C:\Users\mtsch\AppData\AndroidCLI\android.exe run --activity=com.example.perfstream.MainActivity
```

The Gradle wrapper is already bootstrapped (Gradle 9.1.0). First build downloads dependencies; subsequent builds use the configuration cache.

---

## 6. Known Issues & Rough Edges

These should be addressed in follow-up work:

1. **`DataRepository.kt` and `MainScreenViewModel.kt` are dead code** — leftover from the template scaffold. They should be deleted to avoid confusion.

2. **Dynamic bitmap icon may not render on all OEMs** — `Icon.createWithBitmap()` for `setSmallIcon` works on stock Android but some OEM launchers (Samsung One UI, MIUI) may ignore bitmap icons for notifications. Fallback to the standard launcher icon is in place, but testing on the user's actual device will confirm.

3. **No settings/preferences screen** — the sampling interval (2s) is hardcoded. Adding a DataStore-backed settings screen with configurable interval would be a natural next step.

4. **Theme is still using the scaffold's purple color tokens** — `Color.kt` and `Theme.kt` define default Material purple/pink colors. The dashboard itself overrides with custom dark colors (0xFF08080C background, neon accent colors), but the Material theme wrapper should be updated to match for consistency in dialogs, system bars, etc.

5. **No `git init`** — the project doesn't have a git repository yet.

6. **Package ID is `com.example.perfstream`** — should be changed to something real if publishing (e.g., `com.mtsch.perfstream`). Requires updating `namespace` and `applicationId` in `app/build.gradle.kts` and the package declarations in every `.kt` file.

7. **History buffer resets on service restart** — `PerformanceMonitorRepository` is a Kotlin `object` (process singleton). If the service is stopped and restarted, history survives within the same process, but killing the app clears it. Persisting history to Room or a file would enable longer-term charts.

8. **No emulator testing done** — the CPU frequency paths under `/sys/` won't exist on emulators. The code handles this gracefully (returns empty lists), but the CPU card will show 0% / no cores. Network, RAM, and Disk should work fine on emulators.

---

## 7. Suggested Next Steps (Prioritized)

### Quick Wins
- [ ] Delete dead scaffolding files (`DataRepository.kt`, `MainScreenViewModel.kt`)
- [ ] `git init` + initial commit
- [ ] Test deploy to the user's physical device and verify all metric cards populate
- [ ] Update Material theme colors to match the dashboard's dark palette

### Medium Effort
- [ ] Add a Settings screen (sampling interval, which metrics to show in notification, notification icon style)
- [ ] Add battery stats (temperature, current draw via `BatteryManager`)
- [ ] Persist history to local storage for longer chart windows (5 min, 30 min, 1 hr)
- [ ] Add a quick-tile (Android Quick Settings Tile API) to toggle the service

### Larger Features
- [ ] Add Shizuku integration for true system-wide CPU % on Android 8+
- [ ] Add per-app network usage breakdown via `NetworkStatsManager`
- [ ] Add GPU frequency monitoring (device-specific, Qualcomm/Mali paths differ)
- [ ] Widget for home screen showing live mini-charts
- [ ] Change package ID from `com.example.perfstream` to a real one and prepare for release signing
