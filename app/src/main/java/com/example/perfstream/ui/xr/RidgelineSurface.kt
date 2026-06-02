package com.example.perfstream.ui.xr

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
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
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.ExperimentalCustomMeshApi
import androidx.xr.scenecore.GroupEntity
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
 * near ridges occlude far ones via the depth buffer - the Joy Division effect.
 *
 * Architecture (see `~/.claude/notes/spike_xr_custom_mesh.md`):
 * - A persistent [GroupEntity] is wrapped in a [SceneCoreEntity] volume, so the
 *   whole ridgeline carries grab/move/resize chrome and the user's pose survives
 *   data updates.
 * - [CustomMesh] is immutable, so each 2s history tick rebuilds the combined
 *   multi-lane mesh and swaps it as a child [MeshEntity] under the group (dispose
 *   old, create new). Child swaps do not disturb the parent's user-set pose.
 *
 * MUST be called from inside a `Subspace { }`. CustomMesh is experimental
 * ([ExperimentalCustomMeshApi]); the material is self-lit emissive cyan so it reads
 * as flat neon regardless of scene lighting / absent vertex normals.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalCustomMeshApi::class)
@Suppress("DEPRECATION") // Entity.dispose() is the SYNCHRONOUS teardown we need (see swap below)
@Composable
fun RidgelineSurface() {
    val session = LocalSession.current ?: return

    // Persistent parent: this is what the volume chrome grabs; pose survives ticks.
    val group = remember(session) { GroupEntity.create(session, "ridgeline") }

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

    SceneCoreEntity(
        factory = { group },
        modifier = SubspaceModifier
            .offset(x = 620.dp, y = 0.dp, z = 0.dp) // right of the panel; grab to move
            .width(1000.dp)  // ~1 m wide ridgeline - chrome bounds for the contentless group
            .height(520.dp)
            .depth(360.dp)
            .transformingMovable()
            .resizable(),
    )

    // Rebuild the lane geometry each history tick and swap it under the group.
    material?.let { readyMaterial ->
        LaunchedEffect(group, readyMaterial) {
            var previousEntity: MeshEntity? = null
            try {
                PerformanceMonitorRepository.history.collect { history ->
                    val coreCount = history.lastOrNull()?.cpuMaxFreqs?.size ?: 0
                    val lanes = ridgelineLanes(history, coreCount)
                    val mesh = buildRidgelineMesh(session, lanes) ?: return@collect
                    val entity = MeshEntity.create(
                        session, mesh, listOf(readyMaterial), 0, Pose.Identity, group,
                    )
                    previousEntity?.dispose() // stop rendering the old ridge
                    previousEntity = entity
                    // We deliberately never close() the replaced CustomMesh. It is a
                    // pure manual AutoCloseable (no finalizer), and a disposed entity
                    // releases its mesh borrow only on its own (non-deterministic) GC.
                    // close()ing before that GC aborts natively ("owned_ptr: released
                    // with outstanding borrowed objects"), and the release is not
                    // observable, so no deferral is reliable. Net: each replaced mesh
                    // leaks (~tens of KB / 2s tick) until process death. KNOWN
                    // LIMITATION - revisit if SceneCore gains an updatable vertex
                    // buffer or an observable entity-disposal signal.
                }
            } finally {
                previousEntity?.dispose()
            }
        }
    }
}

/**
 * Bake every lane into ONE mesh: each lane is a filled ridge (a waving top edge with
 * an opaque skirt to the lane baseline), positioned across X by history index,
 * stepped up in Y and back in Z by lane index so near lanes occlude far ones.
 * A single TRIANGLES subset over all lanes' quads - one material, depth-buffer
 * occlusion (all opaque). Returns null when there is not yet enough history to draw.
 */
@OptIn(ExperimentalCustomMeshApi::class)
private fun buildRidgelineMesh(session: Session, lanes: List<List<Float>>): CustomMesh? {
    val pointCount = lanes.firstOrNull()?.size ?: 0
    if (lanes.isEmpty() || pointCount < 2) return null

    val vertexBuffer = ByteBuffer
        .allocateDirect(lanes.size * pointCount * 2 * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    // Index count: per lane, (pointCount - 1) quads * 2 triangles * 3 indices.
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

private const val WIDTH = 1.0f      // ridgeline width in meters
private const val AMPLITUDE = 0.18f // peak ridge height per lane
private const val LANE_RISE = 0.06f // each lane stepped up in Y
private const val LANE_DEPTH = 0.05f // each lane stepped back in Z (near occludes far)
