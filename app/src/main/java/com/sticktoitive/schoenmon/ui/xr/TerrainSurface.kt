package com.sticktoitive.schoenmon.ui.xr

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.xr.compose.subspace.layout.transformingMovable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.ExperimentalCustomMeshApi
import androidx.xr.scenecore.ExperimentalSurfaceEntityPixelDimensionsApi
import androidx.xr.scenecore.KhronosPbrMaterial
import androidx.xr.scenecore.MeshEntity
import androidx.xr.scenecore.MeshSubsetTopology
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.VertexAttribute
import androidx.xr.scenecore.VertexAttributeDescriptor
import androidx.xr.scenecore.VertexAttributeType
import androidx.xr.scenecore.VertexLayout
import com.sticktoitive.schoenmon.BuildConfig
import com.sticktoitive.schoenmon.core.TickProfiler
import com.sticktoitive.schoenmon.data.PerformanceMonitorRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * SurfaceEntity terrain — Path D: TriangleMesh shape + GL texture.
 *
 * Creates a [SurfaceEntity] with a 3D [SurfaceEntity.Shape.TriangleMesh]
 * shape. The GL renderer paints neon lane colours onto the surface texture.
 * Each tick, [setShape] is called with updated Y positions so the 3D mesh
 * deforms to match the latest performance data.
 *
 * The TriangleMesh path is lighter than [CustomMesh.FromMeshDataBuilder]
 * because it only needs positions + texCoords (no normals, no vertex colors,
 * no material). The surface texture provides the colour.
 *
 * MUST be called from inside a `Subspace { }`.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalSurfaceEntityPixelDimensionsApi::class, ExperimentalCustomMeshApi::class)
@Suppress("DEPRECATION")
@Composable
fun TerrainSurface() {
    val session = LocalSession.current ?: return

    val profiler = remember {
        if (BuildConfig.DEBUG) TickProfiler("SchoenMon.XR.Terrain") else null
    }

    // We need a material for the root MeshEntity (invisible bounds mesh).
    val boundsMaterial by produceState<KhronosPbrMaterial?>(null, session) {
        value = KhronosPbrMaterial.create(session, AlphaMode.BLEND).apply {
            setBaseColorFactor(Vector4(0f, 0f, 0f, 0f))
        }
    }

    val bMat = boundsMaterial ?: return

    val initialPose = remember {
        Pose(Vector3(0f, 0f, 0f), Quaternion.Identity)
    }

    // Root entity: invisible bounds mesh for SceneCoreEntity.
    val rootEntity = remember(bMat) {
        android.util.Log.i("SchoenMon.XR.Terrain", "Creating root MeshEntity...")
        val boundsMesh = buildHolotableBoundsMesh(session)
        MeshEntity.create(session, boundsMesh, listOf(bMat), 0, initialPose)
    }

    // Pre-allocate mesh buffers for the terrain grid.
    val meshState = remember { TerrainMeshState() }

    // Create the SurfaceEntity with an initial flat TriangleMesh, parented to root.
    val surfaceEntity = remember(rootEntity) {
        try {
            val triMesh = meshState.buildTriangleMesh()
            val shape = SurfaceEntity.Shape.CustomMesh(
                triMesh, triMesh, SurfaceEntity.DrawMode.TRIANGLES,
            )
            android.util.Log.i("SchoenMon.XR.Terrain",
                "Creating SurfaceEntity with TriangleMesh: ${meshState.vertexCount} verts, ${meshState.indexCount} indices")
            SurfaceEntity.create(
                session,
                Pose.Identity,  // relative to root
                shape,
                SurfaceEntity.StereoMode.MONO,
                SurfaceEntity.SuperSampling.NONE,
                SurfaceEntity.SurfaceProtection.NONE,
                rootEntity,
            ).also {
                android.util.Log.i("SchoenMon.XR.Terrain", "SurfaceEntity TriangleMesh created: $it")
            }
        } catch (e: Exception) {
            android.util.Log.e("SchoenMon.XR.Terrain", "SurfaceEntity.create() FAILED", e)
            null
        }
    }

    // Create the GL renderer for surface texture colouring.
    val renderer = remember(surfaceEntity) {
        if (surfaceEntity == null) {
            android.util.Log.e("SchoenMon.XR.Terrain", "surfaceEntity is null, skipping renderer")
            null
        } else {
            try {
                android.util.Log.i("SchoenMon.XR.Terrain", "Setting pixel dims + creating renderer...")
                surfaceEntity.setSurfacePixelDimensions(
                    androidx.xr.runtime.math.IntSize2d(720, 450),
                )
                val s = surfaceEntity.getSurface()
                android.util.Log.i("SchoenMon.XR.Terrain", "Got surface: $s valid=${s.isValid}")
                TerrainGLRenderer(
                    surface = s,
                    surfaceWidth = 720,
                    surfaceHeight = 450,
                ).also { it.initialize() }
            } catch (e: Exception) {
                android.util.Log.e("SchoenMon.XR.Terrain", "Renderer init FAILED", e)
                null
            }
        }
    }

    // Collect performance history, update mesh shape + GL texture each tick.
    val tick by produceState(0L, session, surfaceEntity) {
        val entity = surfaceEntity ?: return@produceState
        var tickCount = 0L
        PerformanceMonitorRepository.history.collect { history ->
            profiler?.beginTick()

            val coreCount = history.lastOrNull()?.cpuMaxFreqs?.size ?: 0
            val lanes = ridgelineLanes(history, coreCount)
            profiler?.markPhase("laneCalc")

            // Update vertex Y positions and rebuild the TriangleMesh shape.
            meshState.updateHeights(lanes)
            val triMesh = meshState.buildTriangleMesh()
            profiler?.markPhase("meshBuild")

            // setShape is Kotlin-internal to scenecore. Call via reflection.
            val newShape = SurfaceEntity.Shape.CustomMesh(
                triMesh, triMesh, SurfaceEntity.DrawMode.TRIANGLES,
            )
            entity.javaClass.getMethod("setShape", SurfaceEntity.Shape::class.java)
                .invoke(entity, newShape)
            profiler?.markPhase("setShape")

            // Update GL texture for lane colouring.
            val heightmap = lanesToHeightmap(lanes)
            renderer?.updateAndRender(heightmap)
            profiler?.markPhase("glRender")

            profiler?.endTick()
            tickCount++
            value = tickCount
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    tick

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

    DisposableEffect(Unit) {
        onDispose {
            renderer?.destroy()
            @Suppress("DEPRECATION")
            surfaceEntity?.dispose()
            @Suppress("DEPRECATION")
            rootEntity.dispose()
        }
    }
}

// ---------------------------------------------------------------------------
// Holotable bounds mesh
// ---------------------------------------------------------------------------

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
        .setIndexData(ByteBufferRegion(idxBuf, 0, idxBuf.capacity()))
        .setTopology(MeshSubsetTopology.TRIANGLES)
        .build()
}

// ---------------------------------------------------------------------------
// Terrain mesh state — pre-allocated buffers for TriangleMesh
// ---------------------------------------------------------------------------

/**
 * Holds pre-allocated [FloatBuffer]s and [IntBuffer] for the terrain grid.
 * The grid is [COLS] × [ROWS] vertices with UV-mapped tex coords.
 * Only the Y positions change each tick; topology is fixed.
 */
private class TerrainMeshState {
    companion object {
        const val COLS = 60
        const val ROWS = 16
        const val EXTENT_X = 1.30f   // metres, time axis
        const val EXTENT_Z = 0.60f   // metres, core lanes
        const val AMPLITUDE = 0.30f  // metres, load 0..1
    }

    val vertexCount = COLS * ROWS
    val indexCount = (COLS - 1) * (ROWS - 1) * 6

    // positions: x, y, z per vertex
    private val posArray = FloatArray(vertexCount * 3)
    // texCoords: u, v per vertex
    private val uvArray = FloatArray(vertexCount * 2)
    // indices: fixed topology
    private val idxArray = IntArray(indexCount)

    init {
        // Pre-compute UVs and initial flat positions + index buffer (never change).
        val xStep = EXTENT_X / (COLS - 1)
        val zStep = EXTENT_Z / (ROWS - 1)
        val xOff = EXTENT_X / 2f
        val zOff = EXTENT_Z / 2f

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val vi = row * COLS + col
                val pi = vi * 3
                posArray[pi] = col * xStep - xOff
                posArray[pi + 1] = 0f  // Y — updated each tick
                posArray[pi + 2] = zOff - row * zStep
                uvArray[vi * 2] = col.toFloat() / (COLS - 1)
                uvArray[vi * 2 + 1] = row.toFloat() / (ROWS - 1)
            }
        }

        var idx = 0
        for (row in 0 until ROWS - 1) {
            for (col in 0 until COLS - 1) {
                val tl = row * COLS + col
                val tr = tl + 1
                val bl = tl + COLS
                val br = bl + 1
                // CCW from +Y so normals face up - the XR compositor backface-culls,
                // and the proven RidgelineSurface box uses this same winding.
                idxArray[idx++] = tl
                idxArray[idx++] = tr
                idxArray[idx++] = bl
                idxArray[idx++] = tr
                idxArray[idx++] = br
                idxArray[idx++] = bl
            }
        }
    }

    /** Update Y positions from lane data. */
    fun updateHeights(lanes: List<List<Float>>) {
        val yOff = -AMPLITUDE / 2f
        for (row in 0 until ROWS) {
            val lane = lanes.getOrNull(row) ?: emptyList()
            for (col in 0 until COLS) {
                val load = lane.getOrNull(col) ?: 0f
                posArray[(row * COLS + col) * 3 + 1] = load * AMPLITUDE + yOff
            }
        }
    }

    /** Build a TriangleMesh from current state. */
    fun buildTriangleMesh(): SurfaceEntity.Shape.TriangleMesh {
        val posBuf = ByteBuffer.allocateDirect(posArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        posBuf.put(posArray).rewind()

        val uvBuf = ByteBuffer.allocateDirect(uvArray.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        uvBuf.put(uvArray).rewind()

        val idxBuf = ByteBuffer.allocateDirect(idxArray.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        idxBuf.put(idxArray).rewind()

        return SurfaceEntity.Shape.TriangleMesh(posBuf, uvBuf, idxBuf)
    }
}

// ---------------------------------------------------------------------------
// Heightmap encoding (for GL texture)
// ---------------------------------------------------------------------------

private fun lanesToHeightmap(lanes: List<List<Float>>): FloatArray {
    val cols = TerrainGLRenderer.COLS
    val rows = TerrainGLRenderer.ROWS
    val data = FloatArray(cols * rows)
    for (row in 0 until rows) {
        val lane = lanes.getOrNull(row) ?: emptyList()
        for (col in 0 until cols) {
            data[row * cols + col] = lane.getOrNull(col) ?: 0f
        }
    }
    return data
}
