package dev.hybrid.scanner

import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class CameraRenderer(
    private val rotation: () -> Int,
    private val onFrame: (Frame) -> Unit,
    private val onError: (Exception) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    private var configured: Session? = null
    private var texture = 0
    private var program = 0
    private var width = 1
    private var height = 1
    private var lastRotation = -1
    private var positionAttr = 0
    private var uvAttr = 0
    private val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = FloatArray(8)
    private val position = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(quad); rewind() }
    private val coords = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        configured = null
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        texture = ids[0]
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val vertex = shader(GL_VERTEX_SHADER,
            "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.);uv=t;}")
        val fragment = shader(GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 uv; uniform samplerExternalOES tex; void main(){gl_FragColor=texture2D(tex,uv);}")
        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        positionAttr = glGetAttribLocation(program, "p")
        uvAttr = glGetAttribLocation(program, "t")
    }

    private fun shader(type: Int, source: String): Int = glCreateShader(type).also {
        glShaderSource(it, source)
        glCompileShader(it)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        lastRotation = -1
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClearColor(.035f, .06f, .085f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val ar = session ?: return
        try {
            if (ar !== configured) {
                ar.setCameraTextureName(texture)
                configured = ar
                lastRotation = -1
            }
            val display = rotation()
            if (lastRotation != display) {
                ar.setDisplayGeometry(display, width, height)
                lastRotation = display
            }
            val frame = ar.update()
            if (frame.timestamp == 0L) return
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quad, Coordinates2d.TEXTURE_NORMALIZED, uv)
            coords.clear()
            coords.put(uv)
            coords.rewind()
            glUseProgram(program)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture)
            glUniform1i(glGetUniformLocation(program, "tex"), 0)
            glEnableVertexAttribArray(positionAttr)
            glEnableVertexAttribArray(uvAttr)
            glVertexAttribPointer(positionAttr, 2, GL_FLOAT, false, 0, position)
            glVertexAttribPointer(uvAttr, 2, GL_FLOAT, false, 0, coords)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            glDisableVertexAttribArray(positionAttr)
            glDisableVertexAttribArray(uvAttr)
            onFrame(frame)
        } catch (failure: Exception) {
            session = null
            onError(failure)
        }
    }
}
