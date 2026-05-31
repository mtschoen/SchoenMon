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
│   └── PerformanceMonitorService.kt    # Foreground service containing the poll loop, dynamic notification, & bitmap icon
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

### Dynamic Small Notification Icons
- **Technique**: The app dynamically draws a custom `Bitmap` displaying the current CPU load percentage, wraps it in `Icon.createWithBitmap()`, and calls `setSmallIcon(Icon)`.
- **OEM Defect**: Certain heavily skinned Android OEM launchers (such as Samsung's One UI or Xiaomi's MIUI) discard custom bitmap icons for notifications, displaying the standard default launcher icon instead.
- **Fallback**: The service includes a fallback to the standard launcher mipmap resource if bitmap icon generation fails or is unsupported.

### Data Repository Lifecycle
- **Scope**: The rolling performance history buffer (capped at 60 entries / 2 minutes) lives purely in memory as a Singleton state in `PerformanceMonitorRepository`.
- **Behavior**: Stopping and starting the service preserves history *as long as the application process stays alive*. Killing the host application process will clear all charts. (In the future, room-based persistence could be introduced to support durable charts).
