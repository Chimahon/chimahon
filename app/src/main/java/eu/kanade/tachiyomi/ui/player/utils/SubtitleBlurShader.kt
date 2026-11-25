package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.util.Locale

data class SubtitleBlurConfig(
    val enabled: Boolean,
    val heightPercent: Int,
    val positionPercent: Int,
    val widthPercent: Int,
    val blurStrength: Int,
    val dimOpacity: Int,
)

object SubtitleBlurShader {
    @Volatile
    private var activePath: String? = null
    private var useSlotA: Boolean = true

    fun configFromPreferences(preferences: SubtitlePreferences): SubtitleBlurConfig {
        return SubtitleBlurConfig(
            enabled = preferences.subtitleBlurEnabled().get(),
            heightPercent = preferences.subtitleBlurHeight().get(),
            positionPercent = preferences.subtitleBlurPosition().get(),
            widthPercent = preferences.subtitleBlurWidth().get(),
            blurStrength = preferences.subtitleBlurStrength().get(),
            dimOpacity = preferences.subtitleBlurDimOpacity().get(),
        )
    }

    fun buildShaderSource(config: SubtitleBlurConfig): String {
        val h = config.heightPercent.coerceIn(
            SubtitlePreferences.BLUR_HEIGHT_MIN,
            SubtitlePreferences.BLUR_HEIGHT_MAX,
        ) / 100f
        val w = config.widthPercent.coerceIn(
            SubtitlePreferences.BLUR_WIDTH_MIN,
            SubtitlePreferences.BLUR_WIDTH_MAX,
        ) / 100f
        val centerY = config.positionPercent.coerceIn(
            SubtitlePreferences.BLUR_POSITION_MIN,
            SubtitlePreferences.BLUR_POSITION_MAX,
        ) / 100f
        val top = (centerY - h / 2f).coerceIn(0f, 1f - h)
        val bottom = (top + h).coerceIn(top, 1f)
        val left = ((1f - w) / 2f).coerceIn(0f, 0.5f)
        val right = (1f - left).coerceIn(left, 1f)
        val radY = (
            config.blurStrength.coerceIn(
                SubtitlePreferences.BLUR_STRENGTH_MIN,
                SubtitlePreferences.BLUR_STRENGTH_MAX,
            ) / 100f
            ) * 0.032f
        val dim = config.dimOpacity.coerceIn(
            SubtitlePreferences.BLUR_DIM_MIN,
            SubtitlePreferences.BLUR_DIM_MAX,
        ) / 100f

        val topPad = (top - radY).coerceIn(0f, 1f)
        val bottomPad = (bottom + radY).coerceIn(0f, 1f)

        val l = fmt(left)
        val r = fmt(right)
        val t = fmt(top)
        val b = fmt(bottom)
        val tp = fmt(topPad)
        val bp = fmt(bottomPad)
        val ry = fmt(radY)
        val d = fmt(dim)

        return """
            //!DESC Subtitle Blur H
            //!HOOK MAIN
            //!BIND HOOKED
            //!SAVE SUBBLUR_H

            vec4 hook() {
                vec2 p = HOOKED_pos;
                if (p.x < $l || p.x > $r || p.y < $tp || p.y > $bp || $ry <= 0.0001) {
                    return HOOKED_tex(p);
                }
                float radX = $ry * (HOOKED_size.y * HOOKED_pt.x);
                vec4 sum = vec4(0.0);
                float wsum = 0.0;
                for (int i = -8; i <= 8; i++) {
                    float f = float(i) / 8.0;
                    float w = exp(-2.5 * f * f);
                    sum += HOOKED_tex(vec2(clamp(p.x + f * radX, 0.0, 1.0), p.y)) * w;
                    wsum += w;
                }
                return sum / wsum;
            }

            //!DESC Subtitle Blur V
            //!HOOK MAIN
            //!BIND HOOKED
            //!BIND SUBBLUR_H

            vec4 hook() {
                vec2 p = HOOKED_pos;
                if (p.x < $l || p.x > $r || p.y < $t || p.y > $b) {
                    return HOOKED_tex(p);
                }
                vec4 blurred;
                if ($ry > 0.0001) {
                    vec4 sum = vec4(0.0);
                    float wsum = 0.0;
                    for (int i = -8; i <= 8; i++) {
                        float f = float(i) / 8.0;
                        float w = exp(-2.5 * f * f);
                        sum += SUBBLUR_H_tex(vec2(p.x, clamp(p.y + f * $ry, 0.0, 1.0))) * w;
                        wsum += w;
                    }
                    blurred = sum / wsum;
                } else {
                    blurred = HOOKED_tex(p);
                }
                vec3 tinted = mix(blurred.rgb, vec3(0.0), $d);
                return vec4(tinted, blurred.a);
            }
        """.trimIndent()
    }

    @Synchronized
    fun apply(context: Context, config: SubtitleBlurConfig) {
        if (!config.enabled) {
            val prev = activePath
            if (prev != null) {
                runCatching {
                    MPVLib.command(arrayOf("change-list", "glsl-shaders", "remove", prev))
                }.onFailure {
                    logcat(LogPriority.WARN) { "Failed to remove subtitle blur shader\n${it.asLog()}" }
                }
                File(prev).delete()
                activePath = null
            }
            File(context.cacheDir, SLOT_A).delete()
            File(context.cacheDir, SLOT_B).delete()
            return
        }

        val slotName = if (useSlotA) SLOT_A else SLOT_B
        useSlotA = !useSlotA
        val nextFile = File(context.cacheDir, slotName)
        runCatching {
            nextFile.writeText(buildShaderSource(config))
        }.onFailure {
            logcat(LogPriority.WARN) { "Failed to write subtitle blur shader\n${it.asLog()}" }
            return
        }

        val prev = activePath
        if (prev != null) {
            runCatching {
                MPVLib.command(arrayOf("change-list", "glsl-shaders", "remove", prev))
            }.onFailure {
                logcat(LogPriority.WARN) { "Failed to remove previous subtitle blur shader\n${it.asLog()}" }
            }
        }
        runCatching {
            MPVLib.command(arrayOf("change-list", "glsl-shaders", "append", nextFile.absolutePath))
            activePath = nextFile.absolutePath
        }.onFailure {
            logcat(LogPriority.WARN) { "Failed to append subtitle blur shader\n${it.asLog()}" }
        }
        if (prev != null && prev != nextFile.absolutePath) {
            File(prev).delete()
        }
    }

    fun applyFromPreferences(context: Context, preferences: SubtitlePreferences) {
        apply(context, configFromPreferences(preferences))
    }

    @Synchronized
    fun resetSession() {
        activePath = null
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.5f", value)

    private const val SLOT_A = "subtitle_blur_a.glsl"
    private const val SLOT_B = "subtitle_blur_b.glsl"
}
