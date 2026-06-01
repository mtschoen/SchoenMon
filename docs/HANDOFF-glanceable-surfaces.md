# Handoff: glanceable perf surfaces - next-session exploration

Branch: `feature/glanceable-surfaces` (3 commits, no upstream yet, NOT merged to main).
Context: this session abandoned the impossible "two status-bar icons" goal and built
glanceable surfaces instead. See AGENTS.md for the settled findings. All work verified
on a physical Galaxy Z Fold 6 (One UI 8.5, SDK 36) - the user's primary target.

## What shipped this session (working, user-approved)

- **Network status-bar icon** = live numeric meter (green number stacked over unit,
  e.g. `1.1` / `M/s`), `surface/SpeedIcon.kt`. Bitmap icon - confirmed renders in color
  on One UI 8.5 (overturned the old "Samsung rewrites bitmaps to robot" lore).
- **CPU Live Update status-bar icon** = filled CPU(cyan)/RAM(pink) bars, `surface/BarsIcon.kt`.
  Promoted ProgressStyle notification -> surfaces in the Now Bar on One UI 8+.
- **Home/lock widget** = CPU+RAM sparkline graph, `surface/GraphIcon.kt` + `widget_perf.xml`.
- **Quick Settings tiles** (CPU/RAM/Net), `surface/PerfTileService.kt`.
- All fed by one `PerfSurfaces.refreshAll()` fan-out per 2s service tick.

## Deferred threads to explore next session

### 1. Inner-screen graph icon (HIGH interest - user "thinks it has merit")
The user noticed status-bar icons look BIGGER on the Fold's inner screen and thinks the
sparkline graph (`GraphIcon`) might actually be legible there as a status-bar icon, where
it was too tiny/smudgy on the cover screen (which is why we switched to bars).
- `GraphIcon.forHistory(history, width, height)` already exists and renders to any bitmap size.
- Open question: can we detect inner-vs-cover display per-notification to swap graph<->bars
  automatically? Research needed - likely via `Display`/`Configuration` density or
  `DisplayManager`, but unclear if a notification can know which screen it's rendering on
  (the same notification shows on both). May not be cleanly detectable.
- Cheap first step: build a graph-in-statusbar variant and let the user A/B it on the inner
  screen directly (swap `BarsIcon.forLoads(...)` back to `GraphIcon` in `LiveUpdateController`).
- RESEARCH FIRST (per research-first): Fold inner vs cover status-bar icon dp/density,
  and whether per-display notification icon variants are possible.

### 2. Dynamic app launcher icon (researched this session - constrained)
Confirmed possible in 2026 ONLY via `activity-alias` swapping
(`PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`), Play-compliant.
- NOT viable for a live 2s meter: launchers cache/animate the swap, swapping can trigger
  app/task restarts, and you need one alias+icon per discrete state.
- ONLY viable as a COARSE, SLOW indicator (e.g. app icon tints red under sustained heavy
  load, changing at most every minute or two).
- User is interested in exploring this as the slow "system stress" glow, NOT live stats.
- Sources: github.com/simonchius/DynamicLauncherIcon, dev.to activity-alias guide,
  issuetracker.google.com/issues/279505991 (restart flakiness).

### 3. Widget layout bug (small, known)
The home-screen widget does not use its bottom half - `widget_perf.xml`'s `LinearLayout`
isn't distributing vertical space, so content clusters at the top. Fix: give the metric
rows / graph `layout_weight` or change the root to distribute space. User said "looks good
though, I dig" - low priority but worth a quick fix.

## Notes
- Emulator (`Medium_Phone_API_36.1`) shows CPU 0% (documented emulator limitation) and the
  Live Update promotes correctly there too. But icon LEGIBILITY must be judged on the real
  Fold - remote screenshots of the folded device come back black (cover screen off).
- Both Folds connected this session: SM-F926U (Fold 3, One UI/SDK 35 - Live Update suppressed,
  correct) and SM-F956U1 (Fold 6, One UI 8.5/SDK 36 - full support). Fold 6 is over wifi adb
  (`192.168.50.167:41561`).
