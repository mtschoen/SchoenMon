package com.example.perfstream.ui.xr

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * The per-core "Unknown Pleasures" ridgeline (plan Task B2).
 *
 * One lane per CPU core (or the 4-channel CPU/RAM/NET/Disk fallback when per-core
 * frequencies are unavailable, e.g. on XR/emulator sysfs), each lane a filled ridge
 * of that signal's rolling history. Lanes are stacked in depth (and stepped up) so
 * near ridges occlude far ones via the depth buffer — the Joy Division effect.
 *
 * Architecture (see `~/.claude/notes/spike_xr_custom_mesh.md`):
 * - Each history tick builds a new combined multi-lane [CustomMesh] and wraps it in
 *   a [MeshEntity] managed directly by [SceneCoreEntity]. This is the ONLY pattern
 *   confirmed to render on the SM-I610: wrapping the [MeshEntity] itself, not a
 *   contentless [GroupEntity][androidx.xr.scenecore.GroupEntity] (which produced no
 *   visible output — the group has no intrinsic geometry, so the volume collapsed).
 * - The [SceneCoreEntity] is key-swapped on each mesh rebuild so the factory always
 *   receives the current [MeshEntity]. This resets the user's grab pose to the
 *   default offset — a known v1 trade-off for reliable rendering. The ridgeline's
 *   default position (beside the panel) reapplies each tick, so it appears
 *   stationary unless the user has actively grabbed it.
 * - Rebuilds are throttled to every [REBUILD_EVERY_N_TICKS] history emissions (4 s
 *   at the default 2 s poll) and capped at [MAX_MESH_REBUILDS] total to bound the
 *   [CustomMesh] leak. We never call [CustomMesh.close] to avoid the native
 *   borrow-abort (see spike notes). After the cap the last good frame freezes.
 *
 * MUST be called from inside a `Subspace { }`. CustomMesh is experimental
 * ([ExperimentalCustomMeshApi]); the material is self-lit emissive cyan so it reads
 * as flat neon regardless of scene lighting / absent vertex normals.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalCustomMeshApi::class)
@Suppress("DEPRECATION") // Entity.dispose() is the SYNCHRONOUS teardown we need
@Composable
fun RidgelineSurface() {
    val session = LocalSession.current ?: return

    // KhronosPbrMaterial.create is suspend; build + configure it off-composition.
    val material by produceState<KhronosPbrMaterial?>(null, session) {
        value = KhronosPbrMaterial.create(session, AlphaMode.OPAQUE).apply {
            // Self-lit flat neon: suppress the PBR lit terms, let emissive carry it.
            setBaseColorFactor(Vector4(0f, 0f, 0f, 1f))
            setMetallicFactor(0f)
            setRoughnessFactor(1f)
            setEmissiveFactor(Vector3(0f, 0.9f, 1.1f))
        }
    }

    // Reactive mesh from the history flow. Each emission is a new mesh + unique tick
    // ID for key-swapping the SceneCoreEntity. Throttled + capped to bound the leak.
    val meshTick by produceState<RidgelineTick?>(null, session) {
        var ticksSinceLastBuild = REBUILD_EVERY_N_TICKS - 1 // so first emission builds
        var meshCount = 0
        PerformanceMonitorRepository.history.collect { history ->
            if (meshCount >= MAX_MESH_REBUILDS) return@collect // freeze
            ticksSinceLastBuild++
            if (ticksSinceLastBuild < REBUILD_EVERY_N_TICKS) return@collect // throttle
            ticksSinceLastBuild = 0

            val coreCount = history.lastOrNull()?.cpuMaxFreqs?.size ?: 0
            val lanes = ridgelineLanes(history, coreCount)
            val mesh = buildRidgelineMesh(session, lanes) ?: return@collect
            meshCount++
            value = RidgelineTick(mesh, meshCount.toLong())
        }
    }

    // Retain all CustomMesh objects for the composable's lifetime. The runtime
    // CustomMesh impl has a GC destructor (lambda$new$0) that calls
    // nDestroyCustomMesh. If a disposed MeshEntity hasn't released its native
    // borrow when that destructor fires, the process aborts with:
    //   "OwnedPtr of type imp::Mesh released with 1 outstanding borrowed objects"
    // Holding strong refs here prevents GC from collecting old meshes while any
    // entity might still borrow them. Cost = the same intentional leak we already
    // accept (bounded by MAX_MESH_REBUILDS), but now structurally safe.
    val retainedMeshes = remember { mutableListOf<CustomMesh>() }

    val tick = meshTick
    val mat = material
    if (tick != null && mat != null) {
        // key-swap: each new tick ID destroys the old SceneCoreEntity (disposing the
        // old MeshEntity via DisposableEffect) and recreates with the new mesh. This
        // is the ONLY pattern confirmed to render on the SM-I610 — wrapping the
        // MeshEntity directly in SceneCoreEntity, not a GroupEntity intermediary.
        key(tick.id) {
            // Park this mesh in the retained list BEFORE it could become unreferenced.
            remember { retainedMeshes.add(tick.mesh) }

            val entity = remember {
                MeshEntity.create(session, tick.mesh, listOf(mat), 0, Pose.Identity)
            }
            SceneCoreEntity(
                factory = { entity },
                modifier = SubspaceModifier
                    .offset(x = 620.dp, y = 0.dp, z = 0.dp) // right of the panel
                    .width(1000.dp)
                    .height(520.dp)
                    .depth(360.dp)
                    .transformingMovable()
                    .resizable(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    entity.dispose()
                    // Do NOT close() the CustomMesh or remove it from retainedMeshes.
                    // The entity's native borrow is released on its own GC (non-
                    // deterministic), and the mesh's GC destructor would abort if it
                    // fires first. Retaining prevents that race entirely.
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mesh construction
// ---------------------------------------------------------------------------

/**
 * Bake every lane into ONE mesh: each lane is a filled ridge (a waving top edge with
 * an opaque skirt to the lane baseline), positioned across X by history index,
 * stepped up in Y and back in Z by lane index so near lanes occlude far ones.
 * A single TRIANGLES subset over all lanes' quads — one material, depth-buffer
 * occlusion (all opaque). Returns null when there is not yet enough history to draw.
 */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildRidgelineMesh(session: Session, lanes: List<List<Float>>): CustomMesh? {
    val pointCount = lanes.firstOrNull()?.size ?: 0
    if (lanes.isEmpty() || pointCount < 2) return null

    val vertexBuffer = ByteBuffer
        .allocateDirect(lanes.size * pointCount * 2 * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    // Index count: per lane, (pointCount - 1) quads × 2 triangles × 3 indices.
    val indexBuffer = ByteBuffer
        .allocateDirect(lanes.size * (pointCount - 1) * 6 * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    lanes.forEachIndexed { laneIndex, lane ->
        val laneBaseY = laneIndex * LANE_RISE
        val laneZ = -laneIndex * LANE_DEPTH
        val vertexBase = laneIndex * pointCount * 2
        for (i in 0 until pointCount) {
            val t = i.toFloat() / (pointCount - 1)
            val x = (t - 0.5f) * WIDTH
            val crest = laneBaseY + lane[i] * AMPLITUDE
            vertexBuffer.putFloat(x).putFloat(crest).putFloat(laneZ)    // top (ridge)
            vertexBuffer.putFloat(x).putFloat(laneBaseY).putFloat(laneZ) // bottom (skirt)
        }
        for (i in 0 until pointCount - 1) {
            val top = vertexBase + 2 * i
            val bottom = top + 1
            val topNext = top + 2
            val bottomNext = top + 3
            indexBuffer.putInt(top).putInt(bottom).putInt(topNext)
            indexBuffer.putInt(bottom).putInt(bottomNext).putInt(topNext)
        }
    }
    vertexBuffer.rewind()
    indexBuffer.rewind()

    val layout = VertexLayout(
        listOf(VertexAttributeDescriptor(VertexAttribute.POSITION, VertexAttributeType.FLOAT3, 0)),
    )
    return CustomMesh.FromMeshDataBuilder(session, layout)
        .addVertexData(ByteBufferRegion(vertexBuffer, 0, vertexBuffer.capacity()))
        .setIndexData(ByteBufferRegion(indexBuffer, 0, indexBuffer.capacity()))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}

// ---------------------------------------------------------------------------
// Internal types + constants
// ---------------------------------------------------------------------------

/** Identifies a single ridgeline frame for key-swapping the [SceneCoreEntity]. */
@OptIn(ExperimentalCustomMeshApi::class)
private class RidgelineTick(val mesh: CustomMesh, val id: Long)

private const val WIDTH = 1.0f        // ridgeline width in metres
private const val AMPLITUDE = 0.18f   // peak ridge height per lane
private const val LANE_RISE = 0.06f   // each lane stepped up in Y
private const val LANE_DEPTH = 0.05f  // each lane stepped back in Z (near occludes far)

/** Rebuild every Nth history emission (history ticks at 2 s → 4 s per rebuild). */
private const val REBUILD_EVERY_N_TICKS = 2

/**
 * Cap total mesh rebuilds to bound the [CustomMesh] leak. At [REBUILD_EVERY_N_TICKS]
 * = 2 (4 s/rebuild), 300 rebuilds ≈ 20 min before the last frame freezes.
 * TODO(mesh-leak): remove this cap once SceneCore ships an updatable vertex buffer
 *  or an observable entity-disposal signal that lets us close() retired meshes safely.
 */
private const val MAX_MESH_REBUILDS = 300
