package com.ljyh.mei.thor.artwork

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/** Call from Dispatchers.Default with the already decoded Coil image; no request or new loader. */
internal fun extractThorArtworkPalette(bitmap: Bitmap): ThorArtworkPalette {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ThorArtworkPalette.Neutral
    var sample: Bitmap? = null
    return try {
        val scale = minOf(1.0, 64.0 / max(bitmap.width, bitmap.height))
        val width = max(1, (bitmap.width * scale).toInt())
        val height = max(1, (bitmap.height * scale).toInt())
        sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
        extractThorArtworkPalette(pixels, width, height)
    } catch (_: IllegalArgumentException) {
        ThorArtworkPalette.Neutral
    } catch (_: IllegalStateException) {
        ThorArtworkPalette.Neutral
    } finally {
        if (sample !== bitmap) sample?.recycle()
    }
}

/** Theme adaptation belongs at render time, so changing day/night does not decode the cover again. */
fun CaseAmbientPalette.forAmbient(isDark: Boolean): CaseAmbientPalette {
    val mapped = ThorArtworkPalette(body.toArgb(), glow.toArgb(), accent.toArgb()).forAmbient(isDark)
    return CaseAmbientPalette(Color(mapped.dominantArgb), Color(mapped.secondaryArgb), Color(mapped.accentArgb))
}

internal fun ThorArtworkPalette.asCaseAmbientPalette(): CaseAmbientPalette =
    CaseAmbientPalette(Color(dominantArgb), Color(secondaryArgb), Color(accentArgb))
