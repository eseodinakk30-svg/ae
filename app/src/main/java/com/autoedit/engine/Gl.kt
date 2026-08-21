package com.autoedit.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val EGL_RECORDABLE_ANDROID = 0x3142

/** Контекст EGL: одна штука на весь рендер. */
class EglCore {
    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(s != null && s !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return s
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(s != null && s !== EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nsecs)
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }
}

/** Шейдерная программа с кэшем локаций юниформов. */
class GlProgram(vertexSrc: String, fragmentSrc: String) {
    val id: Int = link(vertexSrc, fragmentSrc)
    private val locations = HashMap<String, Int>()

    val aPosition = GLES20.glGetAttribLocation(id, "aPos")
    val aTexCoord = GLES20.glGetAttribLocation(id, "aTex")

    fun use() = GLES20.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun set(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)
    fun set(name: String, v: Int) = GLES20.glUniform1i(loc(name), v)
    fun set(name: String, x: Float, y: Float) = GLES20.glUniform2f(loc(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float) = GLES20.glUniform3f(loc(name), x, y, z)
    fun setMatrix(name: String, m: FloatArray) = GLES20.glUniformMatrix4fv(loc(name), 1, false, m, 0)
    fun setMatrix3(name: String, m: FloatArray) = GLES20.glUniformMatrix3fv(loc(name), 1, false, m, 0)

    fun bindTexture(name: String, unit: Int, texture: Int, target: Int = GLES20.GL_TEXTURE_2D) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(loc(name), unit)
    }

    fun release() = GLES20.glDeleteProgram(id)

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Не удалось слинковать шейдер: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Ошибка компиляции шейдера: $log")
        }
        return shader
    }
}

/** Оффскрин-буфер (текстура + FBO). */
class Fbo(val width: Int, val height: Int) {
    val texture: Int
    private val framebuffer: Int

    init {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        framebuffer = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO не готов: $status" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun clear(r: Float = 0f, g: Float = 0f, b: Float = 0f, a: Float = 1f) {
        GLES20.glClearColor(r, g, b, a)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** Полноэкранный квад для всех проходов. */
class FullQuad {
    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    fun draw(program: GlProgram) {
        vertices.position(0)
        GLES20.glVertexAttribPointer(program.aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(program.aPosition)
        vertices.position(2)
        GLES20.glVertexAttribPointer(program.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(program.aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(program.aPosition)
        GLES20.glDisableVertexAttribArray(program.aTexCoord)
    }

    private companion object {
        val VERTEX_DATA = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
    }
}

/** Внешняя текстура + SurfaceTexture для вывода декодера. */
class ExternalTexture(frameHandler: android.os.Handler) {
    val textureId: Int
    val surfaceTexture: SurfaceTexture
    val surface: Surface
    /** Сигнал «кадр приехал» от декодера; ёмкость 1 — лишние кадры не копятся. */
    private val frames = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)

    init {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({ frames.offer(true) }, frameHandler)
        surface = Surface(surfaceTexture)
    }

    /** Ждём кадр от декодера и заливаем его в текстуру. */
    fun awaitAndUpdate(timeoutMs: Long = 2500): Boolean {
        val got = try {
            frames.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (got == null) return false
        surfaceTexture.updateTexImage()
        return true
    }

    fun transformMatrix(out: FloatArray) = surfaceTexture.getTransformMatrix(out)

    fun release() {
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}
