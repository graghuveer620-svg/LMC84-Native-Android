@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lmc84.videostudio

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.IOException

/**
 * Frame-by-frame LMC grading effect. Parameters are immutable for the duration of an export.
 * GPU work is bounded to one input/output texture pair; no decoded video is accumulated in RAM.
 */
data class LmcParameters(
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,
    val warmth: Float = 0f,
    val vignette: Float = 0.15f,
    val texture: Float = 0.5f,
    val sharpen: Float = 0.4f,
    val clarity: Float = 0.3f
)

class LmcGlEffect(private val parameters: LmcParameters) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LmcShaderProgram(context, useHdr, parameters)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        parameters.contrast == 1f && parameters.saturation == 1f &&
        parameters.brightness == 0f && parameters.warmth == 0f &&
        parameters.vignette == 0f && parameters.texture == 0f &&
        parameters.sharpen == 0f && parameters.clarity == 0f
}

private class LmcShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val p: LmcParameters
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var texelX = 1f / 1280f
    private var texelY = 1f / 720f

    init {
        try {
            program = GlProgram(
                context,
                androidx.media3.effect.R.raw.vertex_shader_transformation_es2,
                R.raw.fragment_shader_lmc_es2
            )
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            program.setFloatsUniform("uTransformationMatrix", identity)
            program.setFloatsUniform("uTexTransformationMatrix", identity)
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelX = 1f / inputWidth.coerceAtLeast(1)
        texelY = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uTexelX", texelX)
            program.setFloatUniform("uTexelY", texelY)
            program.setFloatUniform("uContrast", p.contrast)
            program.setFloatUniform("uSaturation", p.saturation)
            program.setFloatUniform("uBrightness", p.brightness)
            program.setFloatUniform("uWarmth", p.warmth)
            program.setFloatUniform("uVignette", p.vignette)
            program.setFloatUniform("uTexture", p.texture)
            program.setFloatUniform("uSharpen", p.sharpen)
            program.setFloatUniform("uClarity", p.clarity)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
