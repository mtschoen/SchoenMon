# SchoenMon XR Holotable - Design Spec

**Date:** 2026-06-12
**Status:** Approved direction, pending written-spec review
**Supersedes:** the visual goals of the Phase B terrain box in
`docs/superpowers/plans/2026-06-02-schoenmon-xr.md` (its platform findings and
infrastructure remain valid and load-bearing)

## Vision

A **holotable**: a dark table slab floating at waist height in Full Space, with a
translucent, emissive, heat-map-colored terrain hologram projected above it. The
terrain is the live per-core CPU history. The user looks at it from above at a
natural standing angle, sees all core rows at once, and can see *through* front
ridges to the ridges behind - a hologram, not a solid object.

Reference images (committed beside this spec in `assets-2026-06-12-holotable/`):

- `chase-long-long-holotable-7.jpg` - sci-fi holotable: glowing cyan topography
  over a dark slab, hot accent highlights. Closest overall composition target.
- `myst_imager.jpg` + `hq720.jpg` - Myst's imager: glassy translucent volume
  with the geometry readable through itself. Material/feel target.
- `heatmap-heightmap.png` - 3D spectral-waterfall surface. Color-language and
  flow target.

## Data mapping

One continuous heightfield surface (not separate fins/slices):

| Axis | Meaning |
|---|---|
| X (left-right) | Time. New samples enter at one edge and scroll sideways (waterfall). 60-sample history = 30s at the 500ms tick. |
| Z (depth, away from viewer) | CPU core index, one lane per core, surface filled continuously between lanes. |
| Y (up) | Load (0-100%). |

Color is a **continuous height gradient**, not per-lane colors:
navy (idle) -> blue -> cyan -> green -> amber -> red (pegged). Existing neon
palette anchors stay (CyberCyan 0x00E5FF, VividAmber 0xFFAB00, etc.).

## Must-have qualities (user-selected)

1. **Continuous height heat-map** coloring (B in the attribute screen).
2. **Translucent layered depth** - see through ridges to ridges behind (C).
3. **Waterfall motion** - history visibly flows sideways through the surface (E).

Explicit non-requirements: high-poly smoothness and bloom/glow post-processing
were offered and NOT selected. The 60x(coreCount) sample grid is acceptable
geometry resolution.

## Material treatment

Target is **glassy fill + bright ridge lines** ("C" on the material screen):
translucent heat-gradient body with crisp glowing contour lines tracing each
core's ridge crest. **Build the glassy fill first** ("B") as its own milestone;
add ridge lines only once the fill looks right. If ridge lines make it busy,
glassy fill alone is an acceptable end state.

## Scene composition and interaction

- **Slab:** a dark (obsidian 0xFF08080C family) thin rounded slab under the
  hologram. Sells the projection, gives a stable ground plane, and is the grab
  affordance. Faint grid lines on the slab are a nice-to-have.
- **Scale:** coffee-table - roughly 1.2-1.5m wide (time axis), 0.5-0.7m deep
  (core lanes), hologram height ~0.25-0.35m above the slab. Floats at waist
  height, placed beside/below the dashboard panel by default.
- **Interaction: grab-to-reposition ONLY.** No resize. `resizable()` is removed
  from the volume (it is implicated in the "table shoots off into the distance"
  bug from the current build). If fly-away persists with `transformingMovable()`
  alone, that becomes a dedicated debugging task, not a design change.
- The dashboard `SpatialPanel` is unchanged.

## Architecture - decided by a spike, not by taste

Two viable rendering paths exist on this device. The decision hinges on one
empirically checkable unknown, so implementation starts with a gate:

### Step zero (gate): SurfaceEntity alpha spike

Put a half-transparent gradient into the current SurfaceEntity GL texture and
look through the headset. Question: does the XR compositor honor per-pixel
alpha from a SurfaceEntity surface (it is a video-playback API and may
composite opaque)? Timebox: one deploy.

### If alpha works -> Approach 1: evolve the SurfaceEntity spike

- Geometry: deform-in-place `SurfaceEntity.Shape.TriangleMesh` heightfield
  (proven, ~1.7ms/tick, 28x faster than the CustomMesh rebuild path).
- All color in the GL texture: per-pixel heat gradient from the heightmap,
  ridge contour lines drawn in-texture (cheap path to material target C),
  smooth waterfall scrolling at framerate by sliding texture/heightmap phase
  between 500ms data ticks.
- Slab: small opaque MeshEntity parented to the same bounds-mesh root.
- Known wart to carry: `setShape` is Kotlin-internal and called via reflection
  (works on scenecore 1.0.0-alpha15; document it, pin the version, and add a
  startup log if the reflective lookup ever fails).

### If alpha fails -> Approach 2: translucent CustomMesh terrain

- The committed RidgelineSurface path with per-vertex heat colors INCLUDING
  alpha (UBYTE4_NORM) and `KhronosPbrMaterial(AlphaMode.BLEND)` with raised
  emissive. Partial-alpha compositing via BLEND materials is already proven on
  this device (the material-hide workaround depends on it).
- Costs accepted on this branch: ~48ms/tick rebuild (move off the main thread;
  budget is 500ms), entity-churn workarounds for broken `dispose()`, stepwise
  rather than smooth scroll, and ridge lines need real geometry (defer them).

### Fallback (rejected unless both fail)

Fully self-rendered side-by-side stereo hologram on a flat surface. Total
visual control but fixed viewpoint - no parallax when leaning around the
table, which breaks the holotable illusion.

## Milestones

1. **M0 - alpha spike** (gate; decides Approach 1 vs 2).
2. **M1 - glassy holotable**: slab + translucent heat-map heightfield + grab-only
   placement beside the panel. This is the "is the soul right?" checkpoint -
   evaluate on-device before more polish.
3. **M2 - waterfall polish**: sideways scroll reads as motion (smooth scroll on
   Approach 1; honest stepping on Approach 2).
4. **M3 - ridge lines** (material target C), only if M1/M2 look clean.

## Performance and platform constraints (carried forward)

- 500ms sampling tick; 60-sample history ring buffer; repository singleton -
  all unchanged.
- All CustomMesh/MeshEntity lifecycle gotchas in AGENTS.md section 5 remain binding
  (UBYTE4_NORM colors, bounds-mesh root, material-hide before dispose,
  no setParent).
- Full Space only - no home-space 3D (settled platform limitation).
- XR screen-state exception in the service (`isScreenOn` returns true on XR
  hardware) remains required for sampling to run at all.

## Cleanup riding along with this work

- Retire the in-flight "Path D" visual experiments that this design replaces:
  the perspective-render-onto-mesh double-projection is already deleted from
  `TerrainGLRenderer`; the flat-color variant becomes the base for Approach 1's
  texture work.
- `RidgelineSurface` stays as the Approach 2 foundation until the M0 gate
  decides; whichever branch loses gets deleted (not commented out) during M1.
- The 45-degree tilt pose and `.offset(500.dp, -200.dp, 100.dp)` placement
  numbers from RidgelineSurface are starting points for the holotable's
  default pose, not constraints.

## Open questions deferred to implementation

- Blend-sorting artifacts when looking through multiple translucent ridges of a
  single mesh (Approach 2) or surface (Approach 1) - acceptable ghostliness vs
  objectionable popping; judge on-device at M1.
- Whether the slab carries faint grid lines and a subtle "projection" gradient
  where the hologram meets it (nice-to-haves, M2/M3 era).
- Exact default pose relative to the dashboard panel; tune on-device.
