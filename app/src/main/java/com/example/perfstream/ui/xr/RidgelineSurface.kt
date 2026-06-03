package com.example.perfstream.ui.xr

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.depth
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.transformingMovable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.ExperimentalCustomMeshApi
import androidx.xr.scenecore.KhronosPbrMaterial
import androidx.xr.scenecore.MeshEntity
import androidx.xr.scenecore.MeshSubsetTopology
import androidx.xr.scenecore.VertexAttribute
import androidx.xr.scenecore.VertexAttributeDescriptor
import androidx.xr.scenecore.VertexAttributeType
import androidx.xr.scenecore.VertexLayout
import com.example.perfstream.data.PerformanceMonitorRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D terrain cube showing live performance data (plan Task B2).
 *
 * The mesh is a closed box whose **top face** is a grid-terrain displaced
 * vertically (Y-up) by each metric's load value, heat-map-coloured per vertex.
 * The four side walls and bottom face are closed with a dark obsidian colour,
 * giving a solid "holographic pedestal" look.
 *
 * Grid axes:
 *   X  →  time (history samples, newest right)
 *   Z  →  metric lane (CPU avg% / RAM% / NET / Disk — the 4-channel fallback)
 *   Y  →  load amplitude (0 = idle, AMPLITUDE = fully pegged)
 *
 * MUST be called from inside a `Subspace { }`.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalCustomMeshApi::class)
@Suppress("DEPRECATION")
@Composable
fun RidgelineSurface() {
    val session = LocalSession.current ?: return

    val material by produceState<KhronosPbrMaterial?>(null, session) {
        value = KhronosPbrMaterial.create(session, AlphaMode.OPAQUE).apply {
            // Moderate base color — enough to let vertex colors show through
            // PBR diffuse without saturating to white. Near-zero emissive
            // prevents uniform wash-out.
            setBaseColorFactor(Vector4(1.5f, 1.5f, 1.5f, 1f))
            setMetallicFactor(0f)
            setRoughnessFactor(1f)
            setEmissiveFactor(Vector3(0.03f, 0.03f, 0.03f))
        }
    }

    // Fully transparent material for hiding old children. dispose() alone
    // does NOT remove child entities from the scene graph on this platform,
    // so we swap to this material to make them invisible before disposal.
    val hideMaterial by produceState<KhronosPbrMaterial?>(null, session) {
        value = KhronosPbrMaterial.create(session, AlphaMode.BLEND).apply {
            setBaseColorFactor(Vector4(0f, 0f, 0f, 0f))
        }
    }

    val meshTick by produceState<RidgelineTick?>(null, session) {
        var ticksSinceLastBuild = REBUILD_EVERY_N_TICKS - 1
        var meshCount = 0
        PerformanceMonitorRepository.history.collect { history ->
            if (meshCount >= MAX_MESH_REBUILDS) return@collect
            ticksSinceLastBuild++
            if (ticksSinceLastBuild < REBUILD_EVERY_N_TICKS) return@collect
            ticksSinceLastBuild = 0

            val coreCount = history.lastOrNull()?.cpuMaxFreqs?.size ?: 0
            val lanes = ridgelineLanes(history, coreCount)
            val mesh = buildTerrainMesh(session, lanes) ?: return@collect
            meshCount++
            value = RidgelineTick(mesh, meshCount.toLong())
        }
    }

    val retainedMeshes = remember { mutableListOf<CustomMesh>() }

    val tick = meshTick
    val mat = material
    val hideMat = hideMaterial
    if (tick != null && mat != null && hideMat != null) {
        // Tilt 45° toward the viewer so all grid rows (back lanes) are visible.
        val tiltedPose = remember {
            Pose(
                Vector3(0f, 0f, 0f),
                Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), 45f),
            )
        }

        // STABLE root entity with a BOUNDS mesh: two zero-area triangles at
        // opposite corners of the maximum possible volume. This gives the
        // SceneCoreEntity correct bounding-box dimensions while rendering
        // nothing visible. The root is never destroyed, so the compositor
        // position (user grabs / resizes) is preserved across mesh updates.
        val rootEntity = remember {
            val boundsMesh = buildBoundsMesh(session)
            retainedMeshes.add(boundsMesh)
            MeshEntity.create(session, boundsMesh, listOf(mat), 0, tiltedPose)
        }

        // Double-ref: current visible child + previous (hidden, pending dispose)
        val childRef = remember { mutableListOf<MeshEntity?>(null) }
        val prevRef = remember { mutableListOf<MeshEntity?>(null) }

        // Every tick:
        //  1. Dispose the PREVIOUS old child (hidden last tick, safe to remove)
        //  2. Hide the current child via transparent material
        //  3. Create the new visible child
        LaunchedEffect(tick.id) {
            // Step 1: dispose the already-hidden entity from the PREVIOUS tick
            prevRef[0]?.dispose()

            // Step 2: hide the current child (will be disposed next tick)
            childRef[0]?.setMaterial(hideMat)
            prevRef[0] = childRef[0]

            // Step 3: create the new visible child
            retainedMeshes.add(tick.mesh)
            val newChild = MeshEntity.create(
                session, tick.mesh, listOf(mat), 0, Pose.Identity, rootEntity,
            )
            childRef[0] = newChild
        }

        SceneCoreEntity(
            factory = { rootEntity },
            modifier = SubspaceModifier
                .offset(x = 500.dp, y = (-200).dp, z = 100.dp)
                .width(1000.dp)
                .height(600.dp)
                .depth(600.dp)
                .transformingMovable()
                .resizable(),
        )
        DisposableEffect(Unit) {
            onDispose {
                prevRef[0]?.dispose()
                childRef[0]?.dispose()
                rootEntity.dispose()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bounds mesh (invisible root for stable SceneCoreEntity positioning)
// ---------------------------------------------------------------------------

/**
 * Create an invisible mesh that spans the maximum possible volume.
 *
 * Two zero-area triangles (3 verts each at the same point) are placed at
 * opposite corners of the max bounding box. The triangles render nothing
 * (zero area) but they tell the SceneCoreEntity the correct spatial extent
 * so child MeshEntities aren’t volume-clipped.
 */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildBoundsMesh(session: Session): CustomMesh {
    // Max extent: 60 samples * 0.015m/sample = 0.885m wide, 0.25m tall, 3*0.15 = 0.45m deep
    val hw = MAX_SAMPLES * SAMPLE_STEP / 2f   // half-width
    val hh = AMPLITUDE / 2f                    // half-height
    val hd = 3 * LANE_SPACING / 2f             // half-depth (4 lanes)

    // 6 vertices: 3 at min corner, 3 at max corner (each forms a degenerate triangle)
    val posBuf = ByteBuffer.allocateDirect(6 * 12).order(ByteOrder.nativeOrder())
    val normBuf = ByteBuffer.allocateDirect(6 * 12).order(ByteOrder.nativeOrder())
    val colBuf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
    val idxBuf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())

    // Min corner triangle (zero area — all 3 verts at the same point)
    repeat(3) {
        posBuf.putFloat(-hw).putFloat(-hh).putFloat(-hd)
        normBuf.putFloat(0f).putFloat(1f).putFloat(0f)
        colBuf.put(0).put(0).put(0).put(0)
    }
    // Max corner triangle
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
        .setIndexData(ByteBufferRegion(idxBuf, 0, idxBuf.capacity()))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}

// ---------------------------------------------------------------------------
// Terrain-box mesh construction
// ---------------------------------------------------------------------------

/**
 * Build a closed box mesh whose top face is a height-displaced terrain grid.
 *
 * Vertex data is split into 3 separate buffers (one per attribute):
 *   bufferIndex 0 → POSITION (FLOAT3)
 *   bufferIndex 1 → NORMAL   (FLOAT3)
 *   bufferIndex 2 → COLOR    (UBYTE4_NORM)
 *
 * The mesh has 6 faces:
 *   • Top:    S×L grid, Y = load * AMPLITUDE, heat-map coloured, normal +Y
 *   • Bottom: 4-corner quad at Y = 0, dark, normal -Y
 *   • Walls:  Ribbons connecting terrain edge to Y = 0, heat-map coloured
 *             (bright at top, darkened at bottom)
 *
 * Load = 0 maps to Y = 0 (bottom of box); load = 1 maps to Y = AMPLITUDE.
 */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildTerrainMesh(session: Session, lanes: List<List<Float>>): CustomMesh? {
    val L = lanes.size                        // lane count (z-axis)
    val S = lanes.firstOrNull()?.size ?: 0    // sample count (x-axis)
    if (L < 2 || S < 2) return null

    val zMax = (L - 1) * LANE_SPACING
    val totalWidth = (S - 1) * SAMPLE_STEP   // grows as history fills

    // Centre the mesh at origin so it sits inside the SceneCoreEntity volume.
    val yOff = -AMPLITUDE / 2f    // bottom at -AMP/2, peak at +AMP/2
    val zOff = zMax / 2f          // front at +zMax/2, back at -zMax/2

    // --- Vertex / index counts ---
    val topV = S * L;       val topI = (S - 1) * (L - 1) * 6
    val bottomV = 4;        val bottomI = 6
    val frontV = S * 2;     val frontI = (S - 1) * 6
    val backV = S * 2;      val backI = (S - 1) * 6
    val leftV = L * 2;      val leftI = (L - 1) * 6
    val rightV = L * 2;     val rightI = (L - 1) * 6
    val totalV = topV + bottomV + frontV + backV + leftV + rightV
    val totalI = topI + bottomI + frontI + backI + leftI + rightI

    val posBuf = ByteBuffer.allocateDirect(totalV * 3 * 4).order(ByteOrder.nativeOrder())
    val normBuf = ByteBuffer.allocateDirect(totalV * 3 * 4).order(ByteOrder.nativeOrder())
    val colBuf = ByteBuffer.allocateDirect(totalV * 4).order(ByteOrder.nativeOrder())
    val idxBuf = ByteBuffer.allocateDirect(totalI * 4).order(ByteOrder.nativeOrder())

    var v = 0 // running vertex offset

    // ===================== TOP FACE (terrain) =====================
    val topBase = v
    for (l in 0 until L) {
        for (s in 0 until S) {
            val x = s * SAMPLE_STEP - totalWidth / 2f
            val z = zOff - l * LANE_SPACING
            val load = lanes[l][s]
            posBuf.putFloat(x).putFloat(load * AMPLITUDE + yOff).putFloat(z)
            normBuf.putFloat(0f).putFloat(1f).putFloat(0f)
            putHeatColor(colBuf, load, 1f)
            v++
        }
    }
    for (l in 0 until L - 1) {
        for (s in 0 until S - 1) {
            val tl = topBase + l * S + s
            val tr = tl + 1
            val bl = tl + S
            val br = bl + 1
            // CCW from +Y → normal points up
            idxBuf.putInt(tl).putInt(tr).putInt(bl)
            idxBuf.putInt(tr).putInt(br).putInt(bl)
        }
    }

    // ===================== BOTTOM FACE =====================
    val bBase = v
    val hw = totalWidth / 2f
    // 4 corners at Y = yOff (load=0 level), centered on Z
    for ((cx, cz) in listOf(-hw to zOff, hw to zOff, -hw to (zOff - zMax), hw to (zOff - zMax))) {
        posBuf.putFloat(cx).putFloat(yOff).putFloat(cz)
        normBuf.putFloat(0f).putFloat(-1f).putFloat(0f)
        putDarkColor(colBuf)
        v++
    }
    // CCW from -Y → normal points down
    idxBuf.putInt(bBase).putInt(bBase + 2).putInt(bBase + 1)
    idxBuf.putInt(bBase + 1).putInt(bBase + 2).putInt(bBase + 3)

    // ===================== FRONT WALL (z = +zOff, lane 0) =====================
    val fBase = v
    for (s in 0 until S) {
        val x = s * SAMPLE_STEP - totalWidth / 2f
        val load = lanes[0][s]
        val topY = load * AMPLITUDE + yOff
        posBuf.putFloat(x).putFloat(topY).putFloat(zOff)
        normBuf.putFloat(0f).putFloat(0f).putFloat(1f)
        putHeatColor(colBuf, load, 1f); v++
        posBuf.putFloat(x).putFloat(yOff).putFloat(zOff)
        normBuf.putFloat(0f).putFloat(0f).putFloat(1f)
        putHeatColor(colBuf, load, 0.12f); v++
    }
    for (s in 0 until S - 1) {
        val t = fBase + s * 2;  val b = t + 1;  val tN = t + 2;  val bN = t + 3
        // CCW from +Z
        idxBuf.putInt(b).putInt(bN).putInt(t)
        idxBuf.putInt(bN).putInt(tN).putInt(t)
    }

    // ===================== BACK WALL (z = zOff - zMax, last lane) =====================
    val bkBase = v
    val zBack = zOff - zMax
    for (s in 0 until S) {
        val x = s * SAMPLE_STEP - totalWidth / 2f
        val load = lanes[L - 1][s]
        val topY = load * AMPLITUDE + yOff
        posBuf.putFloat(x).putFloat(topY).putFloat(zBack)
        normBuf.putFloat(0f).putFloat(0f).putFloat(-1f)
        putHeatColor(colBuf, load, 1f); v++
        posBuf.putFloat(x).putFloat(yOff).putFloat(zBack)
        normBuf.putFloat(0f).putFloat(0f).putFloat(-1f)
        putHeatColor(colBuf, load, 0.12f); v++
    }
    for (s in 0 until S - 1) {
        val t = bkBase + s * 2;  val b = t + 1;  val tN = t + 2;  val bN = t + 3
        // CCW from -Z (reversed from front)
        idxBuf.putInt(t).putInt(tN).putInt(b)
        idxBuf.putInt(tN).putInt(bN).putInt(b)
    }

    // ===================== LEFT WALL (x = -W/2, sample 0) =====================
    val lBase = v
    for (l in 0 until L) {
        val z = zOff - l * LANE_SPACING
        val load = lanes[l][0]
        val topY = load * AMPLITUDE + yOff
        posBuf.putFloat(-hw).putFloat(topY).putFloat(z)
        normBuf.putFloat(-1f).putFloat(0f).putFloat(0f)
        putHeatColor(colBuf, load, 1f); v++
        posBuf.putFloat(-hw).putFloat(yOff).putFloat(z)
        normBuf.putFloat(-1f).putFloat(0f).putFloat(0f)
        putHeatColor(colBuf, load, 0.12f); v++
    }
    for (l in 0 until L - 1) {
        val t = lBase + l * 2;  val b = t + 1;  val tN = t + 2;  val bN = t + 3
        // CCW from -X
        idxBuf.putInt(t).putInt(tN).putInt(b)
        idxBuf.putInt(tN).putInt(bN).putInt(b)
    }

    // ===================== RIGHT WALL (x = +W/2, last sample) =====================
    val rBase = v
    for (l in 0 until L) {
        val z = zOff - l * LANE_SPACING
        val load = lanes[l][S - 1]
        val topY = load * AMPLITUDE + yOff
        posBuf.putFloat(hw).putFloat(topY).putFloat(z)
        normBuf.putFloat(1f).putFloat(0f).putFloat(0f)
        putHeatColor(colBuf, load, 1f); v++
        posBuf.putFloat(hw).putFloat(yOff).putFloat(z)
        normBuf.putFloat(1f).putFloat(0f).putFloat(0f)
        putHeatColor(colBuf, load, 0.12f); v++
    }
    for (l in 0 until L - 1) {
        val t = rBase + l * 2;  val b = t + 1;  val tN = t + 2;  val bN = t + 3
        // CCW from +X (reversed from left)
        idxBuf.putInt(b).putInt(bN).putInt(t)
        idxBuf.putInt(bN).putInt(tN).putInt(t)
    }

    // ===================== Build =====================
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
        .setIndexData(ByteBufferRegion(idxBuf, 0, idxBuf.capacity()))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}

// ---------------------------------------------------------------------------
// Vertex colour helpers
// ---------------------------------------------------------------------------

/**
 * Write a heat-map colour as 4 unsigned bytes (UBYTE4_NORM) into [buf].
 *
 * ```
 * 0.00  deep blue   (0.05, 0.05, 0.40)   — idle / low
 * 0.33  cyber cyan  (0.00, 0.90, 1.00)   — light load
 * 0.66  vivid amber (1.00, 0.75, 0.00)   — moderate
 * 1.00  hot pink    (1.00, 0.10, 0.30)   — maxed out
 * ```
 */
private fun putHeatColor(buf: ByteBuffer, load: Float, brightness: Float) {
    val v = load.coerceIn(0f, 1f)
    val r: Float; val g: Float; val b: Float
    when {
        v < 0.33f -> {
            val t = v / 0.33f
            r = 0.05f - 0.05f * t
            g = 0.05f + 0.85f * t
            b = 0.40f + 0.60f * t
        }
        v < 0.66f -> {
            val t = (v - 0.33f) / 0.33f
            r = t
            g = 0.90f - 0.15f * t
            b = 1.00f - 1.00f * t
        }
        else -> {
            val t = (v - 0.66f) / 0.34f
            r = 1.00f
            g = 0.75f - 0.65f * t
            b = 0.30f * t
        }
    }
    buf.put((r * brightness * 255f).toInt().coerceIn(0, 255).toByte())
    buf.put((g * brightness * 255f).toInt().coerceIn(0, 255).toByte())
    buf.put((b * brightness * 255f).toInt().coerceIn(0, 255).toByte())
    buf.put(255.toByte())
}

/** Very dark obsidian for walls/bottom — stays dark even with PBR amplification. */
private fun putDarkColor(buf: ByteBuffer) {
    buf.put(3.toByte())    // R
    buf.put(3.toByte())    // G
    buf.put(5.toByte())    // B
    buf.put(255.toByte())  // A
}

// ---------------------------------------------------------------------------
// Internal types + constants
// ---------------------------------------------------------------------------

@OptIn(ExperimentalCustomMeshApi::class)
private class RidgelineTick(val mesh: CustomMesh, val id: Long)

private const val SAMPLE_STEP = 0.015f   // metres per sample along X (≈0.9m at 60 samples)
private const val MAX_SAMPLES = 60       // max history entries (for bounding-box pre-allocation)
private const val AMPLITUDE = 0.25f      // full box height = load range (Y axis)
private const val LANE_SPACING = 0.15f   // Z gap between metric lanes

private const val REBUILD_EVERY_N_TICKS = 1  // rebuild on every sample
private const val MAX_MESH_REBUILDS = 600
