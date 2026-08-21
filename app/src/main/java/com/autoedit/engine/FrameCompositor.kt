package com.autoedit.engine

import android.opengl.GLES11Ext
import android.opengl.GLES20
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Матрица 3x3 в column-major (как ждёт GLSL). */
object Mat3 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun translate(x: Float, y: Float) = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, x, y, 1f)

    fun scale(x: Float, y: Float) = floatArrayOf(x, 0f, 0f, 0f, y, 0f, 0f, 0f, 1f)

    fun rotate(radians: Float): FloatArray {
        val c = cos(radians)
        val s = sin(radians)
        return floatArrayOf(c, s, 0f, -s, c, 0f, 0f, 0f, 1f)
    }

    /** a * b (сначала действует b). */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (col in 0 until 3) {
            for (row in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += a[k * 3 + row] * b[col * 3 + k]
                out[col * 3 + row] = sum
            }
        }
        return out
    }

    fun mul(vararg m: FloatArray): FloatArray {
        var acc = m[0]
        for (i in 1 until m.size) acc = mul(acc, m[i])
        return acc
    }
}

/** Параметры одного выходного кадра — заполняются рендером покадрово. */
class FrameParams {
    var zoom = 1f
    var shakeX = 0f
    var shakeY = 0f
    var tilt = 0f
    var mirror = false
    var shock = 0f
    var shockPhase = 0f

    var rgbSplit = 0f
    var echo = 0f
    var trailAlpha = 0.35f
    var glow = 0f
    var saturation = 1f
    var contrast = 1f
    var shadowTint = floatArrayOf(0f, 0f, 0f)
    var highTint = floatArrayOf(0f, 0f, 0f)
    var gradeAmount = 1f
    var vignette = 0f
    var grain = 0f
    var flash = 0f
    var invert = 0f
    var time = 0f

    var transitionType = 0
    var transitionProgress = 1f
    var transitionSeed = 0f
    var resetTrail = false
}

/**
 * Вся GL-цепочка: геометрия -> шлейфы -> свечение -> цвет -> переход -> экран.
 */
class FrameCompositor(private val width: Int, private val height: Int) {

    private val quad = FullQuad()
    private val progGeometry = GlProgram(Shaders.VERTEX, Shaders.GEOMETRY)
    private val progCopy = GlProgram(Shaders.VERTEX, Shaders.COPY)
    private val progBright = GlProgram(Shaders.VERTEX, Shaders.BRIGHT)
    private val progBlur = GlProgram(Shaders.VERTEX, Shaders.BLUR)
    private val progLook = GlProgram(Shaders.VERTEX, Shaders.LOOK)
    private val progTransition = GlProgram(Shaders.VERTEX, Shaders.TRANSITION)

    private val fboGeo = Fbo(width, height)
    private val fboTrail = Fbo(width, height)
    private val fboLook = Fbo(width, height)
    private val fboPrev = Fbo(width, height)
    private val bloomW = max(64, width / 4)
    private val bloomH = max(64, height / 4)
    private val fboBright = Fbo(bloomW, bloomH)
    private val fboBlurA = Fbo(bloomW, bloomH)
    private val fboBlurB = Fbo(bloomW, bloomH)

    private val stMatrix = FloatArray(16)
    private val outAspect = width.toFloat() / height

    init {
        fboTrail.bind()
        fboTrail.clear()
        fboPrev.bind()
        fboPrev.clear()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * @param srcWidth/[srcHeight] размеры декодированного кадра
     * @param srcRotation поворот исходного видео в градусах
     */
    fun render(
        source: ExternalTexture,
        srcWidth: Int,
        srcHeight: Int,
        srcRotation: Int,
        p: FrameParams,
    ) {
        source.transformMatrix(stMatrix)

        // --- 1. Геометрия: кроп, зум, тряска, наклон, поворот исходника.
        fboGeo.bind()
        progGeometry.use()
        progGeometry.bindTexture(
            "uTex", 0, source.textureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        )
        progGeometry.setMatrix("uStMatrix", stMatrix)
        progGeometry.setMatrix3("uUvMatrix", uvMatrix(srcWidth, srcHeight, srcRotation, p))
        progGeometry.set("uOutAspect", outAspect)
        progGeometry.set("uMirror", if (p.mirror) 1f else 0f)
        progGeometry.set("uShock", p.shock)
        progGeometry.set("uShockPhase", p.shockPhase)
        quad.draw(progGeometry)

        // --- 2. Шлейфы: подмешиваем кадр в накопитель с затуханием.
        fboTrail.bind()
        if (p.resetTrail) fboTrail.clear()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        progCopy.use()
        progCopy.bindTexture("uTex", 0, fboGeo.texture)
        progCopy.set("uAlpha", p.trailAlpha)
        quad.draw(progCopy)
        GLES20.glDisable(GLES20.GL_BLEND)

        // --- 3. Свечение: яркие участки -> размытие в два прохода.
        if (p.glow > 0.001f) {
            fboBright.bind()
            progBright.use()
            progBright.bindTexture("uTex", 0, fboGeo.texture)
            progBright.set("uThreshold", 0.62f)
            quad.draw(progBright)

            fboBlurA.bind()
            progBlur.use()
            progBlur.bindTexture("uTex", 0, fboBright.texture)
            progBlur.set("uStep", 1f / bloomW, 0f)
            quad.draw(progBlur)

            fboBlurB.bind()
            progBlur.use()
            progBlur.bindTexture("uTex", 0, fboBlurA.texture)
            progBlur.set("uStep", 0f, 1f / bloomH)
            quad.draw(progBlur)
        }

        // --- 4. Цвет и вспышки.
        fboLook.bind()
        progLook.use()
        progLook.bindTexture("uTex", 0, fboGeo.texture)
        progLook.bindTexture("uTrail", 1, fboTrail.texture)
        progLook.bindTexture("uBloom", 2, if (p.glow > 0.001f) fboBlurB.texture else fboGeo.texture)
        progLook.set("uAspect", outAspect)
        progLook.set("uRgb", p.rgbSplit)
        progLook.set("uEcho", p.echo)
        progLook.set("uGlow", p.glow)
        progLook.set("uSat", p.saturation)
        progLook.set("uContrast", p.contrast)
        progLook.set("uShadowTint", p.shadowTint[0], p.shadowTint[1], p.shadowTint[2])
        progLook.set("uHighTint", p.highTint[0], p.highTint[1], p.highTint[2])
        progLook.set("uGradeAmount", p.gradeAmount)
        progLook.set("uVignette", p.vignette)
        progLook.set("uGrain", p.grain)
        progLook.set("uFlash", p.flash)
        progLook.set("uInvert", p.invert)
        progLook.set("uTime", p.time)
        quad.draw(progLook)

        // --- 5. Вывод в кодировщик: переход либо просто кадр.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        if (p.transitionProgress < 1f && p.transitionType != 0) {
            progTransition.use()
            progTransition.bindTexture("uPrev", 0, fboPrev.texture)
            progTransition.bindTexture("uCur", 1, fboLook.texture)
            progTransition.set("uType", p.transitionType)
            progTransition.set("uProgress", p.transitionProgress)
            progTransition.set("uSeed", p.transitionSeed)
            quad.draw(progTransition)
        } else {
            progCopy.use()
            progCopy.bindTexture("uTex", 0, fboLook.texture)
            progCopy.set("uAlpha", 1f)
            quad.draw(progCopy)
        }

        // --- 6. Запоминаем кадр для следующего перехода.
        fboPrev.bind()
        progCopy.use()
        progCopy.bindTexture("uTex", 0, fboLook.texture)
        progCopy.set("uAlpha", 1f)
        quad.draw(progCopy)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
    }

    /**
     * Матрица «точка кадра -> координата в исходнике».
     * Кадр покрывается исходником целиком (center-crop).
     */
    private fun uvMatrix(srcW: Int, srcH: Int, rotation: Int, p: FrameParams): FloatArray {
        val rot = ((rotation % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val dispW = if (swapped) srcH else srcW
        val dispH = if (swapped) srcW else srcH
        val srcAspect = if (dispW > 0 && dispH > 0) dispW.toFloat() / dispH else outAspect

        // k — во сколько раз высота исходника больше высоты кадра при покрытии.
        val k = max(outAspect / srcAspect, 1f)
        val toUv = Mat3.mul(
            Mat3.translate(0.5f, 0.5f),
            Mat3.scale(1f / (srcAspect * k), 1f / k),
        )
        val rotateUv = Mat3.mul(
            Mat3.translate(0.5f, 0.5f),
            Mat3.rotate(Math.toRadians(-rot.toDouble()).toFloat()),
            Mat3.translate(-0.5f, -0.5f),
        )
        return Mat3.mul(
            rotateUv,
            toUv,
            Mat3.translate(p.shakeX, p.shakeY),
            Mat3.scale(1f / p.zoom, 1f / p.zoom),
            Mat3.rotate(-p.tilt),
        )
    }

    fun release() {
        progGeometry.release()
        progCopy.release()
        progBright.release()
        progBlur.release()
        progLook.release()
        progTransition.release()
        fboGeo.release()
        fboTrail.release()
        fboLook.release()
        fboPrev.release()
        fboBright.release()
        fboBlurA.release()
        fboBlurB.release()
    }
}
