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
        // the SurfaceEntity TriangleMesh, this texture only supplies colour)
        private val VERT_SRC = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aGridPos;   // (u, v) in [0,1]
            uniform sampler2D uHeightmap;
            out vec2 vUV;
            out float vHeight;
            void main() {
                vUV = aGridPos;
                vHeight = texture(uHeightmap, aGridPos).r;
                gl_Position = vec4(aGridPos.x * 2.0 - 1.0, aGridPos.y * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        // Fragment shader: neon lane colouring + height glow
        private val FRAG_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 vUV;
            in float vHeight;
            out vec4 fragColor;
            void main() {
                float v = vUV.y;
                // SchoenMon neon palette gradient
                vec3 cyan  = vec3(0.0, 0.898, 1.0);
                vec3 pink  = vec3(0.835, 0.0, 0.976);
                vec3 green = vec3(0.0, 0.902, 0.463);
                vec3 amber = vec3(1.0, 0.671, 0.0);
                vec3 color;
                if (v < 0.333) {
                    color = mix(cyan, pink, v / 0.333);
                } else if (v < 0.666) {
                    color = mix(pink, green, (v - 0.333) / 0.333);
                } else {
                    color = mix(green, amber, (v - 0.666) / 0.334);
                }
                float glow = 0.15 + vHeight * 0.85;
                fragColor = vec4(color * glow, 0.5);
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

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
