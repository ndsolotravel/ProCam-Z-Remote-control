package org.ndsolotravel.apps.camera

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance OpenGL ES 3.0 renderer with OES camera texture sampling,
 * real-time 3D LUT color grading, and zero-copy frame tapping for remote streaming.
 */
class CameraGlRenderer : SurfaceTexture.OnFrameAvailableListener {

    private var oesTextureId = -1
    private var lutTextureId = -1
    private var program = -1

    private var uTexMatrixLoc = -1
    private var uLutIntensityLoc = -1
    private var uUseLutLoc = -1
    private var uSamplerOesLoc = -1
    private var uSamplerLutLoc = -1

    var surfaceTexture: SurfaceTexture? = null
        private set

    private val transformMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false

    private var activeLutData: LutManager.LutData? = null
    var lutIntensity: Float = 1.0f

    // Bounded streaming capture buffer
    private var streamingFrameCallback: ((ByteArray) -> Unit)? = null
    private var lastStreamFrameTimeMs = 0L
    var targetStreamFps = 25
    var streamQualityWidth = 640
    var streamQualityHeight = 360

    private val vertexShaderSource = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderSource = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        precision highp sampler3D;
        
        in vec2 vTexCoord;
        uniform samplerExternalOES sCameraTexture;
        uniform sampler3D sLutTexture;
        uniform float uLutIntensity;
        uniform int uUseLut;
        out vec4 fragColor;
        
        void main() {
            vec4 srcColor = texture(sCameraTexture, vTexCoord);
            if (uUseLut == 1) {
                // Scale RGB coords to cell centers in 3D LUT [0.5/size, 1.0 - 0.5/size]
                float lutSize = 33.0;
                vec3 lutCoord = srcColor.rgb * ((lutSize - 1.0) / lutSize) + (0.5 / lutSize);
                vec4 gradedColor = texture(sLutTexture, lutCoord);
                vec3 blended = mix(srcColor.rgb, gradedColor.rgb, uLutIntensity);
                fragColor = vec4(blended, srcColor.a);
            } else {
                fragColor = srcColor;
            }
        }
    """.trimIndent()

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f
            ))
            position(0)
        }

    fun initGl() {
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        lutTextureId = textures[1]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener(this@CameraGlRenderer)
        }

        program = createProgram(vertexShaderSource, fragmentShaderSource)
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uLutIntensityLoc = GLES30.glGetUniformLocation(program, "uLutIntensity")
        uUseLutLoc = GLES30.glGetUniformLocation(program, "uUseLut")
        uSamplerOesLoc = GLES30.glGetUniformLocation(program, "sCameraTexture")
        uSamplerLutLoc = GLES30.glGetUniformLocation(program, "sLutTexture")

        Log.d("CameraGlRenderer", "GL initialization complete. OES Texture=$oesTextureId, Program=$program")
    }

    fun setLut(lutData: LutManager.LutData?) {
        activeLutData = lutData
        if (lutData == null || lutTextureId == -1) return

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA8,
            lutData.size,
            lutData.size,
            lutData.size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            lutData.buffer
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        Log.d("CameraGlRenderer", "Updated active 3D LUT to '${lutData.name}' in GLES30")
    }

    fun setStreamingCallback(callback: ((ByteArray) -> Unit)?) {
        streamingFrameCallback = callback
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
    }

    fun drawFrame(viewportWidth: Int, viewportHeight: Int) {
        val st = surfaceTexture ?: return
        if (frameAvailable) {
            st.updateTexImage()
            st.getTransformMatrix(transformMatrix)
            frameAvailable = false
        }

        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Bind OES Camera Texture to unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uSamplerOesLoc, 0)

        // Bind 3D LUT Texture to unit 1 if active
        val hasLut = activeLutData != null && lutIntensity > 0.01f
        if (hasLut) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(uSamplerLutLoc, 1)
            GLES30.glUniform1i(uUseLutLoc, 1)
            GLES30.glUniform1f(uLutIntensityLoc, lutIntensity)
        } else {
            GLES30.glUniform1i(uUseLutLoc, 0)
        }

        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, transformMatrix, 0)

        // Pass vertex coordinates
        quadVertices.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, quadVertices)
        GLES30.glEnableVertexAttribArray(0)

        quadVertices.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, quadVertices)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        // Check if monitoring stream frame should be tapped (rate-limited to target FPS)
        val now = System.currentTimeMillis()
        val minInterval = 1000L / targetStreamFps.coerceIn(10, 30)
        if (streamingFrameCallback != null && (now - lastStreamFrameTimeMs >= minInterval)) {
            lastStreamFrameTimeMs = now
            captureStreamingFrame(viewportWidth, viewportHeight)
        }
    }

    private fun captureStreamingFrame(srcWidth: Int, srcHeight: Int) {
        val callback = streamingFrameCallback ?: return
        val w = streamQualityWidth
        val h = streamQualityHeight
        val pixelBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

        // Read pixels from center of viewport
        val startX = ((srcWidth - w) / 2).coerceAtLeast(0)
        val startY = ((srcHeight - h) / 2).coerceAtLeast(0)
        GLES30.glReadPixels(startX, startY, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        pixelBuffer.rewind()
        bitmap.copyPixelsFromBuffer(pixelBuffer)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        bitmap.recycle()

        val jpegBytes = stream.toByteArray()
        callback.invoke(jpegBytes)
    }

    private fun createProgram(vertexSrc: String, fragSrc: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vertexShader)
        GLES30.glAttachShader(prog, fragmentShader)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            val error = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw RuntimeException("Could not link GL program: $error")
        }
        return prog
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $error")
        }
        return shader
    }

    fun release() {
        if (oesTextureId != -1) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = -1
        }
        if (lutTextureId != -1) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = -1
        }
        if (program != -1) {
            GLES30.glDeleteProgram(program)
            program = -1
        }
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
