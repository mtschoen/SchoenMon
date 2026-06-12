package com.sticktoitive.schoenmon.ui.xr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Minimal OpenGL ES 3.0 colour-pass renderer for the terrain texture on an
 * Android [Surface] (provided by a [SurfaceEntity]).
 *
 * Renders a flat UV-space image: each texel is the neon lane colour scaled
 * by the heightmap load value (glow). The 3D shape comes entirely from the
 * SurfaceEntity's TriangleMesh geometry; this texture only supplies colour,
 * so there is no camera or projection here. All GPU work happens on a
 * dedicated [HandlerThread] so the main thread is never blocked.
 *
 * Expected per-frame cost on SM-I610: < 2 ms (texture upload + draw + swap).
 */
class TerrainGLRenderer(
    private val surface: Surface,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
) {
    companion object {
        private const val TAG = "TerrainGL"

        // Grid dimensions — must match the data producer
        const val COLS = 60
        const val ROWS = 16
        // Vertex shader: flat UV-space pass (no camera; geometry comes from
        // the SurfaceEntity TriangleMesh, this texture only supplies colour).
        //
        // NDC y is NEGATED on purpose: GL window coordinates are bottom-up,
        // but the SurfaceEntity sampler addresses the queued buffer in Android
        // image convention (texCoord v=0 = top scanline) without applying the
        // GL producer's vertical-flip transform. Rendering the grid top-down
        // makes mesh texCoord v line up with heightmap row, so colour tracks
        // geometry. (Root-caused 2026-06-12: colours were lane-mirrored across
        // the z axis relative to the ridges.)
        private val VERT_SRC = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aGridPos;   // (u, v) in [0,1]
            out vec2 vUV;
            void main() {
                vUV = aGridPos;
                gl_Position = vec4(aGridPos.x * 2.0 - 1.0, 1.0 - aGridPos.y * 2.0, 0.0, 1.0);
            }
        """.trimIndent()

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
                // Texel-center remap: the grid spans [0,1] across COLS-1 x ROWS-1
                // quads (vertex lattice), but texel centers sit at (i+0.5)/N.
                // Without this, lane colours shear by up to half a lane at the
                // near/far edges (sampled texel error = row/(ROWS-1) - 0.5).
                vec2 uv = vUV * vec2(${COLS - 1}.0 / $COLS.0, ${ROWS - 1}.0 / $ROWS.0)
                        + vec2(0.5 / $COLS.0, 0.5 / $ROWS.0);
                float h = texture(uHeightmap, uv).r;
                // Hologram translucency: idle floor stays ghostly, peaks more present.
                float alpha = 0.40 + 0.35 * h;
                fragColor = vec4(heat(h), alpha);
            }
        """.trimIndent()
    }

    // --- GL thread ---
    private val glThread = HandlerThread("TerrainGL").also { it.start() }
    private val glHandler = Handler(glThread.looper)

    // EGL handles (GL-thread only)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var program = 0
    private var uHeightmap = -1
    private var vbo = 0
    private var ibo = 0
    private var heightmapTex = 0
    private var indexCount = 0
    private var initialized = false

    // Heightmap data staging buffer (written from any thread, read on GL thread)
    @Volatile private var pendingHeightmap: FloatArray? = null

    // Monotonic count of heightmap texture uploads (logged once per render).
    private var uploadGeneration = 0L

    /** Initialise EGL + GL resources. Call once after construction. */
    fun initialize() {
        glHandler.post { initGL() }
    }

    /** Queue a new heightmap and trigger a render. Thread-safe. */
    fun updateAndRender(heightmap: FloatArray) {
        pendingHeightmap = heightmap
        glHandler.post { doRender() }
    }

    /** Tear down GL resources and stop the render thread. */
    fun destroy() {
        glHandler.post { destroyGL() }
        glThread.quitSafely()
    }

    // -----------------------------------------------------------------------
    // GL-thread internals
    // -----------------------------------------------------------------------

    private fun initGL() {
        // --- EGL setup ---
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0]!!

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

        val surfAttr = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfAttr, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // --- Shaders ---
        program = createProgram(VERT_SRC, FRAG_SRC)
        uHeightmap = GLES30.glGetUniformLocation(program, "uHeightmap")

        // --- VBO: grid of (u,v) positions ---
        val vertCount = COLS * ROWS
        val verts = FloatBuffer.allocate(vertCount * 2)
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                verts.put(col.toFloat() / (COLS - 1))
                verts.put(row.toFloat() / (ROWS - 1))
            }
        }
        verts.rewind()

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertCount * 2 * 4,
            verts,
            GLES30.GL_STATIC_DRAW,
        )

        // --- IBO: two triangles per quad ---
        val quadCount = (COLS - 1) * (ROWS - 1)
        indexCount = quadCount * 6
        val indices = ShortBuffer.allocate(indexCount)
        for (row in 0 until ROWS - 1) {
            for (col in 0 until COLS - 1) {
                val tl = (row * COLS + col).toShort()
                val tr = (tl + 1).toShort()
                val bl = (tl + COLS).toShort()
                val br = (bl + 1).toShort()
                indices.put(tl).put(bl).put(tr)
                indices.put(tr).put(bl).put(br)
            }
        }
        indices.rewind()

        val iboArr = IntArray(1)
        GLES30.glGenBuffers(1, iboArr, 0)
        ibo = iboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indexCount * 2,
            indices,
            GLES30.GL_STATIC_DRAW,
        )

        // --- Heightmap texture (R32F, 60×16) ---
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        heightmapTex = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightmapTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // Allocate with zeros initially
        val zeros = ByteBuffer.allocateDirect(COLS * ROWS * 4).order(ByteOrder.nativeOrder())
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            COLS, ROWS, 0, GLES30.GL_RED, GLES30.GL_FLOAT, zeros,
        )

        // --- GL state ---
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        initialized = true
        Log.i(TAG, "GL init OK: ${surfaceWidth}×${surfaceHeight}, program=$program")
    }

    private fun doRender() {
        if (!initialized) return

        // Upload pending heightmap
        val hm = pendingHeightmap
        if (hm != null) {
            pendingHeightmap = null
            uploadGeneration++
            val buf = ByteBuffer.allocateDirect(hm.size * 4).order(ByteOrder.nativeOrder())
            buf.asFloatBuffer().put(hm)
            buf.rewind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightmapTex)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0,
                COLS, ROWS, GLES30.GL_RED, GLES30.GL_FLOAT, buf,
            )
        }

        // Clear (not normally visible: the grid spans the full viewport)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Draw terrain
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightmapTex)
        GLES30.glUniform1i(uHeightmap, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        val swapOk = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!swapOk) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        // One line per tick, mirroring the TickProfiler cadence: proves the
        // texture path is live (uploadGeneration advances) and frames land.
        Log.d(TAG, "render gen=$uploadGeneration swap=$swapOk")
    }

    private fun destroyGL() {
        if (!initialized) return
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(ibo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(heightmapTex), 0)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        initialized = false
        Log.i(TAG, "GL destroyed")
    }

    // -----------------------------------------------------------------------
    // Shader helpers
    // -----------------------------------------------------------------------

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            error("Program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed ($type): $log")
        }
        return shader
    }
}
