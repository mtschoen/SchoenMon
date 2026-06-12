# SchoenMon XR Holotable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the XR per-core terrain experiments with a Myst-imager-style holotable: a dark grabbable slab with a translucent, heat-map-colored, waterfall-scrolling CPU-history hologram floating above it, at coffee-table scale in Full Space.

**Architecture:** Evolve the existing fast SurfaceEntity path (`TerrainSurface.kt` + `TerrainGLRenderer.kt`): a deform-in-place `Shape.TriangleMesh` heightfield (x = time, z = core lanes, y = load, ~1.7ms/tick) whose GL surface texture supplies per-pixel heat-gradient color and translucency. A small opaque CustomMesh slab parented to the same bounds-mesh root sells the projection and acts as the grab handle (grab-only; `resizable()` removed - implicated in the runaway-volume bug). **Gated by M0:** one spike deploy proves the XR compositor honors per-pixel alpha from a SurfaceEntity surface; if it does not, STOP after Phase 0 and re-plan Phases 1-3 on the CustomMesh/MeshEntity path (per-vertex UBYTE4_NORM colors with alpha + `KhronosPbrMaterial(AlphaMode.BLEND)` + raised emissive - partial-alpha BLEND compositing is already proven on-device by the material-hide workaround; accept ~48ms/tick rebuilds moved off the main thread, stepwise scroll, and defer ridge lines).

**Tech Stack:** Kotlin, Jetpack Compose XR (`androidx.xr.compose` 1.0.0-alpha14, `scenecore` 1.0.0-alpha15), OpenGL ES 3.0, Galaxy XR (SM-I610) over adb wifi.

**Reference images (the visual north star):** `docs/superpowers/specs/assets-2026-06-12-holotable/` - `chase-long-long-holotable-7.jpg` (composition), `myst_imager.jpg` (glassy material), `heatmap-heightmap.png` (color language). These outlive the spec; do not delete with the plan.

**Visual requirements (user-locked):** continuous height heat-map (navy idle -> blue -> cyan -> green -> amber -> red pegged); translucent enough to see ridges behind ridges; sideways waterfall motion (oldest left, newest right); glassy fill first (M1), bright ridge lines after (M3, drop if busy); NO high-poly smoothing, NO bloom required.

**Known platform facts that bind this plan** (AGENTS.md section 5): Full Space only; bounds-mesh root pattern for stable grab position; `dispose()` does not visually remove children (material-hide first); `setParent` inaccessible; UBYTE4_NORM is the only working vertex color type; `SurfaceEntity.setShape` is Kotlin-internal and called via reflection (works on alpha15 - pin versions, log loudly if the reflective lookup fails).

**Build/deploy loop used by every deploy step:**
```powershell
.\gradlew.bat assembleDebug; adb install -r app\build\outputs\apk\debug\app-debug.apk; adb shell am force-stop com.sticktoitive.schoenmon; adb shell am start -n com.sticktoitive.schoenmon/.MainActivity
```
(Headset must be awake and adb-wifi connected: `adb devices` shows `adb-R3GYB04E2WB-...`. User then enters Full Space. Logs: `adb logcat -d -s SchoenMon.XR.Terrain:V TerrainGL:V AndroidRuntime:E`.)

---

## Phase 0: M0 - alpha spike gate

### Task 1: Commit the spike baseline

The working tree holds the in-flight Path D spike (already verified rendering on-device this session). Freeze it so every later step diffs cleanly.

**Files:**
- Modify: none (commit only)

- [ ] **Step 1: Commit all current WIP**

```bash
git add AGENTS.md app/build.gradle.kts \
  app/src/main/java/com/sticktoitive/schoenmon/service/PerformanceMonitorService.kt \
  app/src/main/java/com/sticktoitive/schoenmon/ui/xr/SpatialDashboard.kt \
  app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt \
  app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt \
  app/src/main/java/com/sticktoitive/schoenmon/ui/xr/RidgelineSurface.kt
git commit -m "wip(xr): Path D spike baseline - SurfaceEntity TriangleMesh terrain renders on SM-I610 (CCW winding fix, flat UV color pass, 1.7ms/tick)"
```

### Task 2: Alpha spike - does the compositor honor surface alpha?

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt`

- [ ] **Step 1: Write fragment alpha + disable blending so alpha lands in the buffer verbatim**

In `FRAG_SRC`, change the output line to a hard half-transparent output:

```glsl
fragColor = vec4(color * glow, 0.5);
```

In `initGL()`, replace the blend enable with a disable (we WRITE alpha, we do not blend against the cleared buffer):

```kotlin
GLES30.glDisable(GLES30.GL_BLEND)
```

In `doRender()`, change the clear to fully transparent so empty texels are a second data point:

```kotlin
GLES30.glClearColor(0f, 0f, 0f, 0f)
```

- [ ] **Step 2: Deploy and observe on-device**

Run the build/deploy loop. User enters Full Space and answers ONE question while looking at the terrain: **can you see the room (passthrough) through the colored surface?**
- Surface visibly see-through (room texture/edges behind it) = ALPHA WORKS.
- Surface solid (colors dimmed or not, but opaque) = ALPHA IGNORED.

- [ ] **Step 3: Record the verdict in this plan and in AGENTS.md**

Tick exactly one:
- [ ] **ALPHA WORKS** - proceed to Phase 1.
- [ ] **ALPHA IGNORED** - STOP. Re-invoke superpowers:writing-plans to rewrite Phases 1-3 against the Approach 2 description in this plan's Architecture header (translucent CustomMesh). Phase 0 and all requirements/references above stay valid.

Either way, append the finding to AGENTS.md section 5 (it is a new platform fact, in the style of the existing entries: what was tested, on what device/build, what happened).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt AGENTS.md docs/superpowers/plans/2026-06-12-xr-holotable.md
git commit -m "spike(xr): SurfaceEntity per-pixel alpha verdict on SM-I610"
```

---

## Phase 1: M1 - glassy holotable (assumes ALPHA WORKS)

### Task 3: Holotable scale, grab-only interaction, right-sized bounds mesh

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt`

- [ ] **Step 1: Coffee-table mesh extents**

In `TerrainMeshState.companion`, set:

```kotlin
const val COLS = 60
const val ROWS = 16
const val EXTENT_X = 1.30f   // metres, time axis
const val EXTENT_Z = 0.60f   // metres, core lanes
const val AMPLITUDE = 0.30f  // metres, load 0..1
```

- [ ] **Step 2: Parameterized bounds mesh, local to TerrainSurface**

`buildBoundsMesh` in RidgelineSurface.kt is sized for the OLD extents (0.885 x 0.25 x 0.45m); the new hologram + slab would be volume-clipped. Add this to `TerrainSurface.kt` (same imports RidgelineSurface uses for its mesh builders: `ByteBufferRegion`, `CustomMesh`, `VertexAttribute`, `VertexAttributeDescriptor`, `VertexAttributeType`, `VertexLayout`, `MeshSubsetTopology`, `androidx.xr.runtime.Session`):

```kotlin
/**
 * Invisible bounds mesh: two zero-area triangles at opposite corners of the
 * holotable volume (hologram + slab). Defines SceneCoreEntity extents without
 * rendering anything. Pattern documented in AGENTS.md section 5.
 */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildHolotableBoundsMesh(session: Session): CustomMesh {
    val hw = 0.75f   // half-width  > EXTENT_X/2 + slab margin
    val hh = 0.25f   // half-height > AMPLITUDE/2 + slab drop
    val hd = 0.40f   // half-depth  > EXTENT_Z/2 + slab margin

    val posBuf = ByteBuffer.allocateDirect(6 * 12).order(ByteOrder.nativeOrder())
    val normBuf = ByteBuffer.allocateDirect(6 * 12).order(ByteOrder.nativeOrder())
    val colBuf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
    val idxBuf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
    repeat(3) {
        posBuf.putFloat(-hw).putFloat(-hh).putFloat(-hd)
        normBuf.putFloat(0f).putFloat(1f).putFloat(0f)
        colBuf.put(0).put(0).put(0).put(0)
    }
    repeat(3) {
        posBuf.putFloat(hw).putFloat(hh).putFloat(hd)
        normBuf.putFloat(0f).putFloat(1f).putFloat(0f)
        colBuf.put(0).put(0).put(0).put(0)
    }
    idxBuf.putInt(0).putInt(1).putInt(2)
    idxBuf.putInt(3).putInt(4).putInt(5)
    posBuf.rewind(); normBuf.rewind(); colBuf.rewind(); idxBuf.rewind()

    val layout = VertexLayout(
        listOf(
            VertexAttributeDescriptor(VertexAttribute.POSITION, VertexAttributeType.FLOAT3, 0),
            VertexAttributeDescriptor(VertexAttribute.NORMAL, VertexAttributeType.FLOAT3, 1),
            VertexAttributeDescriptor(VertexAttribute.COLOR, VertexAttributeType.UBYTE4_NORM, 2),
        ),
    )
    return CustomMesh.FromMeshDataBuilder(session, layout)
        .addVertexData(ByteBufferRegion(posBuf, 0, posBuf.capacity()))
        .addVertexData(ByteBufferRegion(normBuf, 0, normBuf.capacity()))
        .addVertexData(ByteBufferRegion(colBuf, 0, colBuf.capacity()))
        .addIndexData(ByteBufferRegion(idxBuf, 0, idxBuf.capacity()))
        .addSubset(intArrayOf(0))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}
```

(Copy `.addSubset(...)`/builder-call details from the existing `buildBoundsMesh` at `RidgelineSurface.kt:182` if its exact chain differs - the existing call compiles against alpha15 and is authoritative.)

In the `rootEntity` remember block, call `buildHolotableBoundsMesh(session)` instead of `buildBoundsMesh(session)`.

- [ ] **Step 3: Grab-only volume at table placement**

Replace the SceneCoreEntity modifier chain (1 metre is approximately 1151.86dp in Jetpack XR; values are starting points to tune on-device):

```kotlin
SceneCoreEntity(
    factory = { rootEntity },
    modifier = SubspaceModifier
        // Waist height, slightly in front of the dashboard panel.
        .offset(x = 0.dp, y = (-500).dp, z = 350.dp)
        .width(1500.dp)
        .height(580.dp)
        .depth(800.dp)
        .transformingMovable(),   // grab-to-reposition ONLY; resizable() removed
)
```

Remove the now-unused `resizable` import. Keep `initialPose` as `Pose(Vector3(0f,0f,0f), Quaternion.Identity)` (layout owns placement).

- [ ] **Step 4: Build, deploy, sanity-check on-device**

Run the build/deploy loop. Expected: terrain renders at the new size, below and in front of the panel, grabbable but not resizable, and the grab no longer flies away (if it still does, file it as the next debugging task - do not redesign here).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt
git commit -m "feat(xr): holotable scale + grab-only volume + right-sized bounds mesh"
```

### Task 4: Glassy heat-map shader

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt`

- [ ] **Step 1: Replace `FRAG_SRC` with the heat-gradient translucent shader**

```kotlin
// Fragment shader: continuous height heat-map + hologram translucency.
// Height is sampled per-fragment from the heightmap so the gradient is
// smooth across the coarse 60x16 vertex grid.
private val FRAG_SRC = """
    #version 300 es
    precision mediump float;
    in vec2 vUV;
    out vec4 fragColor;
    uniform sampler2D uHeightmap;

    vec3 heat(float h) {
        vec3 navy  = vec3(0.063, 0.110, 0.306);
        vec3 blue  = vec3(0.161, 0.384, 1.000);
        vec3 cyan  = vec3(0.000, 0.898, 1.000);
        vec3 green = vec3(0.000, 0.902, 0.463);
        vec3 amber = vec3(1.000, 0.671, 0.000);
        vec3 red   = vec3(1.000, 0.090, 0.267);
        if (h < 0.35) return mix(navy,  blue,  h / 0.35);
        if (h < 0.60) return mix(blue,  cyan,  (h - 0.35) / 0.25);
        if (h < 0.75) return mix(cyan,  green, (h - 0.60) / 0.15);
        if (h < 0.88) return mix(green, amber, (h - 0.75) / 0.13);
        return mix(amber, red, (h - 0.88) / 0.12);
    }

    void main() {
        float h = texture(uHeightmap, vUV).r;
        // Hologram translucency: idle floor stays ghostly, peaks more present.
        float alpha = 0.40 + 0.35 * h;
        fragColor = vec4(heat(h), alpha);
    }
""".trimIndent()
```

- [ ] **Step 2: Simplify the vertex shader (height no longer needed per-vertex)**

```kotlin
private val VERT_SRC = """
    #version 300 es
    precision highp float;
    layout(location = 0) in vec2 aGridPos;   // (u, v) in [0,1]
    out vec2 vUV;
    void main() {
        vUV = aGridPos;
        gl_Position = vec4(aGridPos.x * 2.0 - 1.0, aGridPos.y * 2.0 - 1.0, 0.0, 1.0);
    }
""".trimIndent()
```

Keep `GLES30.glDisable(GLES30.GL_BLEND)` and the `(0,0,0,0)` clear from Task 2.

- [ ] **Step 3: Deploy and verify on-device**

Run the build/deploy loop. Expected: terrain body shows the navy-to-red gradient by height and the room reads through it; tick logs (`SchoenMon.XR.Terrain: TICK`) still ~2ms.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt
git commit -m "feat(xr): glassy heat-map hologram shader (per-fragment gradient + alpha)"
```

### Task 5: The slab

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt`

- [ ] **Step 1: Slab mesh builder (opaque dark box, 1.40 x 0.025 x 0.70m)**

Add to `TerrainSurface.kt`:

```kotlin
/** Opaque obsidian slab the hologram floats above. 8 verts, 12 tris. */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildSlabMesh(session: Session): CustomMesh {
    val hw = 0.70f; val hh = 0.0125f; val hd = 0.35f
    val corners = listOf(
        floatArrayOf(-hw, -hh, -hd), floatArrayOf(hw, -hh, -hd),
        floatArrayOf(hw, -hh, hd),  floatArrayOf(-hw, -hh, hd),
        floatArrayOf(-hw, hh, -hd),  floatArrayOf(hw, hh, -hd),
        floatArrayOf(hw, hh, hd),   floatArrayOf(-hw, hh, hd),
    )
    val posBuf = ByteBuffer.allocateDirect(8 * 12).order(ByteOrder.nativeOrder())
    val normBuf = ByteBuffer.allocateDirect(8 * 12).order(ByteOrder.nativeOrder())
    val colBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
    corners.forEach { c ->
        posBuf.putFloat(c[0]).putFloat(c[1]).putFloat(c[2])
        normBuf.putFloat(0f).putFloat(1f).putFloat(0f)
        // dark slate, fully opaque (0x12121A-ish)
        colBuf.put(18).put(18).put(26).put(255.toByte())
    }
    // 12 triangles, CCW as seen from OUTSIDE each face (compositor backface-culls)
    val tris = intArrayOf(
        4,6,5, 4,7,6,    // top (+Y)
        0,1,2, 0,2,3,    // bottom (-Y)
        3,2,6, 3,6,7,    // front (+Z)
        1,0,4, 1,4,5,    // back (-Z)
        0,3,7, 0,7,4,    // left (-X)
        2,1,5, 2,5,6,    // right (+X)
    )
    val idxBuf = ByteBuffer.allocateDirect(tris.size * 4).order(ByteOrder.nativeOrder())
    tris.forEach { idxBuf.putInt(it) }
    posBuf.rewind(); normBuf.rewind(); colBuf.rewind(); idxBuf.rewind()

    val layout = VertexLayout(
        listOf(
            VertexAttributeDescriptor(VertexAttribute.POSITION, VertexAttributeType.FLOAT3, 0),
            VertexAttributeDescriptor(VertexAttribute.NORMAL, VertexAttributeType.FLOAT3, 1),
            VertexAttributeDescriptor(VertexAttribute.COLOR, VertexAttributeType.UBYTE4_NORM, 2),
        ),
    )
    return CustomMesh.FromMeshDataBuilder(session, layout)
        .addVertexData(ByteBufferRegion(posBuf, 0, posBuf.capacity()))
        .addVertexData(ByteBufferRegion(normBuf, 0, normBuf.capacity()))
        .addVertexData(ByteBufferRegion(colBuf, 0, colBuf.capacity()))
        .addIndexData(ByteBufferRegion(idxBuf, 0, idxBuf.capacity()))
        .addSubset(intArrayOf(0))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}
```

- [ ] **Step 2: Create the slab entity under the same root**

After `rootEntity` is created (and with a slab material from `produceState`, same pattern as `boundsMaterial`):

```kotlin
val slabMaterial by produceState<KhronosPbrMaterial?>(null, session) {
    value = KhronosPbrMaterial.create(session, AlphaMode.OPAQUE).apply {
        setBaseColorFactor(Vector4(0.9f, 0.9f, 1.1f, 1f))  // lets vertex slate read dark-cool
        setMetallicFactor(0f)
        setRoughnessFactor(1f)
        setEmissiveFactor(Vector3(0.01f, 0.01f, 0.015f))
    }
}
val sMat = slabMaterial ?: return

val slabEntity = remember(rootEntity, sMat) {
    val slabPose = Pose(Vector3(0f, -(TerrainMeshState.AMPLITUDE / 2f) - 0.035f, 0f), Quaternion.Identity)
    MeshEntity.create(session, buildSlabMesh(session), listOf(sMat), 0, slabPose, rootEntity)
}
```

Dispose it in the existing `DisposableEffect` (`slabEntity.dispose()` after the surface entity; per AGENTS.md, on teardown of the whole root this is acceptable - the material-hide dance is only needed for per-tick churn, which the slab never does).

- [ ] **Step 3: Deploy and verify on-device**

Run the build/deploy loop. Expected: dark slab floating under the hologram, hologram hovering just above it, both move together when grabbed.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt
git commit -m "feat(xr): holotable slab entity under the hologram"
```

### Task 6: Delete the losing branch

**Files:**
- Delete: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/RidgelineSurface.kt`
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/SpatialDashboard.kt`

- [ ] **Step 1: Remove RidgelineSurface and its dashboard remnants**

Keep `RidgelineData.kt` (pure data shaping; `ridgelineLanes` feeds the holotable). In `SpatialDashboard.kt` replace the OLD/NEW comment block + call with:

```kotlin
        // The holotable: translucent per-core CPU heightfield over a grabbable
        // slab. A subspace composable, so it lives inside the Subspace DSL.
        TerrainSurface()
```

Run `git rm` via the Bash tool (PowerShell tool false-positives on `git rm` chains):

```bash
git rm app/src/main/java/com/sticktoitive/schoenmon/ui/xr/RidgelineSurface.kt
```

- [ ] **Step 2: Build to prove nothing else referenced it**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. If `buildBoundsMesh`/constants are missed anywhere, the compiler will say so - fix by moving the missing piece into `TerrainSurface.kt`, not by resurrecting the file.

- [ ] **Step 3: Commit**

```bash
git add -A app/src/main/java/com/sticktoitive/schoenmon/ui/xr
git commit -m "refactor(xr): delete RidgelineSurface (CustomMesh branch lost the M0 gate)"
```

### Task 7: M1 checkpoint - the soul check

- [ ] **Step 1: Deploy, user evaluates on-device against the reference images**

Run the build/deploy loop. The user looks for: glassy translucent heat terrain, slab grounding it, believable hologram, stable grab. Capture a screenshot on-device (pull from `/sdcard/DCIM/Screenshots/`).

- [ ] **Step 2: Decision**

- [ ] Soul is right - proceed to Phase 2.
- [ ] Off in a fixable way (translucency level, palette balance, scale) - tune constants (`alpha` ramp in FRAG_SRC, extents, offset) and redeploy; repeat.
- [ ] Fundamentally off - STOP, discuss before more code.

---

## Phase 2: M2 - waterfall motion

### Task 8: Sub-tick scroll interpolation

The surface currently snaps once per 500ms tick. Make history visibly FLOW: render at ~30fps, sliding the sample window left by an interpolation phase so peaks travel rather than wobble in place.

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt`

- [ ] **Step 1: Phase-aware height sampling in `TerrainMeshState`**

```kotlin
/**
 * Update Y positions from lane data, slid left by [phase] in [0,1) of one
 * sample step. height(col) = lerp(lane[col], lane[col+1], phase) so the
 * whole surface travels one sample per tick instead of snapping.
 */
fun updateHeights(lanes: List<List<Float>>, phase: Float) {
    val yOff = -AMPLITUDE / 2f
    for (row in 0 until ROWS) {
        val lane = lanes.getOrNull(row) ?: emptyList()
        for (col in 0 until COLS) {
            val a = lane.getOrNull(col) ?: 0f
            val b = lane.getOrNull(col + 1) ?: a
            posArray[(row * COLS + col) * 3 + 1] = (a + (b - a) * phase) * AMPLITUDE + yOff
        }
    }
}
```

Mirror the same lerp in `lanesToHeightmap(lanes, phase)` so texture color and geometry agree:

```kotlin
private fun lanesToHeightmap(lanes: List<List<Float>>, phase: Float): FloatArray {
    val cols = TerrainGLRenderer.COLS
    val rows = TerrainGLRenderer.ROWS
    val data = FloatArray(cols * rows)
    for (row in 0 until rows) {
        val lane = lanes.getOrNull(row) ?: emptyList()
        for (col in 0 until cols) {
            val a = lane.getOrNull(col) ?: 0f
            val b = lane.getOrNull(col + 1) ?: a
            data[row * cols + col] = a + (b - a) * phase
        }
    }
    return data
}
```

- [ ] **Step 2: Replace the collect-only loop with collector + 30fps animator**

Replace the `produceState` block with a `LaunchedEffect` running two coroutines: one collects history into a holder with a timestamp; one renders every 33ms with `phase = elapsed / 500ms` (clamped):

```kotlin
val latest = remember { java.util.concurrent.atomic.AtomicReference<Pair<List<List<Float>>, Long>>(Pair(emptyList(), 0L)) }

LaunchedEffect(session, surfaceEntity) {
    val entity = surfaceEntity ?: return@LaunchedEffect
    launch {
        PerformanceMonitorRepository.history.collect { history ->
            val coreCount = history.lastOrNull()?.cpuMaxFreqs?.size ?: 0
            latest.set(Pair(ridgelineLanes(history, coreCount), android.os.SystemClock.uptimeMillis()))
        }
    }
    while (isActive) {
        val (lanes, tickAt) = latest.get()
        if (lanes.isNotEmpty()) {
            profiler?.beginTick()
            val phase = ((android.os.SystemClock.uptimeMillis() - tickAt) / 500f).coerceIn(0f, 1f)
            meshState.updateHeights(lanes, phase)
            val triMesh = meshState.buildTriangleMesh()
            profiler?.markPhase("meshBuild")
            val newShape = SurfaceEntity.Shape.CustomMesh(triMesh, triMesh, SurfaceEntity.DrawMode.TRIANGLES)
            entity.javaClass.getMethod("setShape", SurfaceEntity.Shape::class.java).invoke(entity, newShape)
            profiler?.markPhase("setShape")
            renderer?.updateAndRender(lanesToHeightmap(lanes, phase))
            profiler?.markPhase("glRender")
            profiler?.endTick()
        }
        delay(33)
    }
}
```

(Imports: `kotlinx.coroutines.launch`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.isActive`. Remove the old `tick` produceState and its `@Suppress` usage.)

- [ ] **Step 3: Deploy and verify on-device**

Run the build/deploy loop. Expected: the landscape glides continuously leftward; `TICK` profiler lines now appear ~30x/s - confirm total stays under ~5ms so the 30fps loop is comfortably affordable. If it is not, drop the animator to 15fps (`delay(66)`) and note it in AGENTS.md.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt
git commit -m "feat(xr): waterfall scroll - 30fps sub-tick interpolation of the heightfield"
```

---

## Phase 3: M3 - ridge lines (material target C; drop if busy)

### Task 9: Per-core crest lines in the fragment shader

**Files:**
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt`
- Modify: `app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt` (pass lane count)

- [ ] **Step 1: Lane-count plumbing**

`TerrainGLRenderer`: add `@Volatile private var laneCount = 4` + setter `fun setLaneCount(n: Int) { laneCount = n.coerceAtLeast(1) }`, a `uLanes` uniform (`glGetUniformLocation` in `initGL`, `glUniform1f(uLanes, laneCount.toFloat())` in `doRender`). `TerrainSurface`: call `renderer?.setLaneCount(lanes.size)` in the collector.

- [ ] **Step 2: Crest lines in `FRAG_SRC` (insert before the final `fragColor` line)**

```glsl
// Bright contour line along each core lane's center.
float lanePos = fract(vUV.y * uLanes);
float line = 1.0 - smoothstep(0.04, 0.10, abs(lanePos - 0.5));
vec3 lineColor = vec3(0.0, 0.898, 1.0) * 1.4;
vec3 body = heat(h);
float alpha = 0.40 + 0.35 * h;
fragColor = vec4(mix(body, lineColor, line * 0.85), max(alpha, line * 0.9));
```

- [ ] **Step 3: Deploy, evaluate, decide**

Run the build/deploy loop. User judges legibility vs busy-ness against `chase-long-long-holotable-7.jpg`.
- [ ] Keep - commit as below.
- [ ] Too busy - `git checkout -- <both files>` and record the decision in AGENTS.md (glassy fill is the end state).

- [ ] **Step 4: Commit (if kept)**

```bash
git add app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainGLRenderer.kt app/src/main/java/com/sticktoitive/schoenmon/ui/xr/TerrainSurface.kt
git commit -m "feat(xr): per-core crest lines on the holotable hologram"
```

---

## Phase 4: Documentation and wrap

### Task 10: Update docs that this work made stale

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/2026-06-02-schoenmon-xr.md`

- [ ] **Step 1: AGENTS.md**

In the Android XR section: replace the ridgeline/terrain-box description with the holotable (axis mapping, translucency approach per the M0 verdict, grab-only interaction, slab). Add new platform facts discovered: the M0 alpha verdict, the CCW-from-+Y winding requirement (compositor backface-culls TriangleMesh geometry; discovered 2026-06-12), and the `setShape`-via-reflection note with pinned versions.

- [ ] **Step 2: Retire the old plan's resume block**

Edit the top "RESUME HERE" block of `docs/superpowers/plans/2026-06-02-schoenmon-xr.md` to a one-paragraph pointer: Phase A/B findings remain valid; visual direction superseded by `2026-06-12-xr-holotable.md` (and after THIS plan dies, by AGENTS.md + git history).

- [ ] **Step 3: Run the docs-update check** (README, CLAUDE.md/AGENTS.md, inline docs) and fix any other drift.

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md docs/superpowers/plans/2026-06-02-schoenmon-xr.md
git commit -m "docs(xr): holotable design + M0 alpha finding + winding gotcha; retire old resume block"
```
