package com.ljyh.mei.thor.artwork

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Opaque sRGB environmental colors. These never replace theme text, actions or focus colors. */
data class ThorArtworkPalette(
    val dominantArgb: Int,
    val secondaryArgb: Int,
    val accentArgb: Int,
    val neutral: Boolean = false,
) {
    companion object {
        val Neutral = ThorArtworkPalette(0xFF737373.toInt(), 0xFF969696.toInt(), 0xFFB8B8B8.toInt(), true)
    }
}

/**
 * Bounded, deterministic sampling with no Android/Compose dependency.
 * Transparent edges and near-black/white noise do not outvote meaningful cover colors.
 */
fun extractThorArtworkPalette(pixels: IntArray, width: Int, height: Int): ThorArtworkPalette {
    val count = width.toLong() * height
    if (width <= 0 || height <= 0 || count > pixels.size || count > Int.MAX_VALUE) return ThorArtworkPalette.Neutral
    val columns = min(width, 64)
    val rows = min(height, (4096 / columns).coerceAtLeast(1))
    val histogram = HashMap<Int, PaletteBucket>()
    var opaqueWeight = 0.0
    var opaqueGray = 0.0
    var colorWeight = 0.0
    for (row in 0 until rows) {
        val y = (((row * 2L + 1L) * height) / (rows * 2L)).toInt().coerceAtMost(height - 1)
        for (column in 0 until columns) {
            val x = (((column * 2L + 1L) * width) / (columns * 2L)).toInt().coerceAtMost(width - 1)
            val argb = pixels[y * width + x]
            val alpha = argb ushr 24
            if (alpha < 192) continue
            val r = red(argb); val g = green(argb); val b = blue(argb)
            val weight = alpha / 255.0
            opaqueWeight += weight
            opaqueGray += (r * .2126 + g * .7152 + b * .0722) * weight
            val low = min(r, min(g, b)); val high = max(r, max(g, b))
            if (high <= 24 || (low >= 235 && high - low <= 24)) continue
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            histogram.getOrPut(key) { PaletteBucket() }.add(r.toDouble(), g.toDouble(), b.toDouble(), weight)
            colorWeight += weight
        }
    }
    if (histogram.isEmpty()) return neutralPalette(opaqueGray, opaqueWeight)
    val clusters = ArrayList<PaletteBucket>()
    val sorted = histogram.entries.sortedWith(compareByDescending<Map.Entry<Int, PaletteBucket>> { it.value.weight }.thenBy { it.key })
    for ((_, bucket) in sorted) {
        val lab = bucket.lab()
        val nearest = clusters.minByOrNull { distanceSquared(lab, it.lab()) }
        val distance = nearest?.let { distanceSquared(lab, it.lab()) } ?: Double.MAX_VALUE
        when {
            nearest != null && distance < .075 * .075 -> nearest.add(bucket)
            clusters.size < 64 -> clusters.add(bucket.copy())
            nearest != null && distance < .20 * .20 -> nearest.add(bucket)
        }
    }
    val minWeight = max(colorWeight * .015, min(1.0, colorWeight))
    val eligible = clusters.filter { it.weight >= minWeight }
        .sortedWith(compareByDescending<PaletteBucket> { it.weight }.thenBy { it.argb() })
    if (eligible.isEmpty() || eligible.none { chroma(it.lab()) >= .035 }) return neutralPalette(opaqueGray, opaqueWeight)
    val dominant = eligible.first()
    val secondary = eligible.drop(1).firstOrNull { distanceSquared(dominant.lab(), it.lab()) >= .085 * .085 }
    val accent = eligible.asSequence().filter { it !== dominant && it !== secondary }
        .filter { distanceSquared(dominant.lab(), it.lab()) >= .06 * .06 }
        .maxByOrNull { chroma(it.lab()) * sqrt(it.weight / colorWeight) }
    val dominantColor = dominant.argb()
    val secondaryColor = secondary?.argb() ?: shiftedTone(dominantColor, .12)
    val accentColor = accent?.argb() ?: shiftedTone(
        eligible.maxBy { chroma(it.lab()) }.argb(), .20,
    )
    return ThorArtworkPalette(dominantColor, secondaryColor, accentColor)
}

/**
 * Safe opaque environmental tones for the existing day/night theme.
 * Linear-light retiming preserves hue without allowing bright cover pixels behind night lyrics.
 */
fun ThorArtworkPalette.forAmbient(isDark: Boolean): ThorArtworkPalette = copy(
    dominantArgb = retone(desaturate(dominantArgb, .28), if (isDark) .035 else .86),
    secondaryArgb = retone(desaturate(secondaryArgb, .22), if (isDark) .060 else .78),
    accentArgb = retone(desaturate(accentArgb, .14), if (isDark) .085 else .70),
)

/** Relative luminance, also useful when verifying an environmental renderer's own composition. */
fun thorArtworkLuminance(argb: Int): Double =
    linear(red(argb)) * .2126 + linear(green(argb)) * .7152 + linear(blue(argb)) * .0722

private data class PaletteBucket(
    var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0, var weight: Double = 0.0,
) {
    private var cachedLab: PaletteLab? = null
    fun add(red: Double, green: Double, blue: Double, amount: Double) {
        cachedLab = null
        r += red * amount; g += green * amount; b += blue * amount; weight += amount
    }
    fun add(other: PaletteBucket) { cachedLab = null; r += other.r; g += other.g; b += other.b; weight += other.weight }
    fun argb(): Int = opaque((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
    fun lab(): PaletteLab = cachedLab ?: oklab(argb()).also { cachedLab = it }
}

private data class PaletteLab(val l: Double, val a: Double, val b: Double)
private fun oklab(argb: Int): PaletteLab {
    val r = linear(red(argb)); val g = linear(green(argb)); val b = linear(blue(argb))
    val l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
    val m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
    val s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
    return PaletteLab(.2104542553 * l + .7936177850 * m - .0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
        .0259040371 * l + .7827717662 * m - .8086757660 * s)
}
private fun distanceSquared(a: PaletteLab, b: PaletteLab): Double =
    (a.l - b.l).pow(2) + (a.a - b.a).pow(2) + (a.b - b.b).pow(2)
private fun chroma(lab: PaletteLab): Double = sqrt(lab.a * lab.a + lab.b * lab.b)
private fun red(argb: Int) = (argb ushr 16) and 255
private fun green(argb: Int) = (argb ushr 8) and 255
private fun blue(argb: Int) = argb and 255
private fun opaque(r: Int, g: Int, b: Int) = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
private fun linear(channel: Int): Double = (channel / 255.0).let { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
private fun encoded(channel: Double): Int = (channel.coerceIn(0.0, 1.0).let { if (it <= .0031308) it * 12.92 else 1.055 * it.pow(1.0 / 2.4) - .055 } * 255 + .5).toInt()
private fun fromLinear(r: Double, g: Double, b: Double): Int = opaque(encoded(r), encoded(g), encoded(b))
private fun desaturate(argb: Int, amount: Double): Int {
    val gray = thorArtworkLuminance(argb)
    return fromLinear(linear(red(argb)) * (1 - amount) + gray * amount,
        linear(green(argb)) * (1 - amount) + gray * amount,
        linear(blue(argb)) * (1 - amount) + gray * amount)
}
private fun retone(argb: Int, target: Double): Int {
    val luminance = thorArtworkLuminance(argb)
    val lighter = target > luminance
    val fraction = if (lighter) (target - luminance) / (1 - luminance).coerceAtLeast(.0001)
        else (1 - target / luminance.coerceAtLeast(.0001))
    val endpoint = if (lighter) 1.0 else 0.0
    fun channel(value: Int) = linear(value) * (1 - fraction) + endpoint * fraction
    return fromLinear(channel(red(argb)), channel(green(argb)), channel(blue(argb)))
}
private fun shiftedTone(argb: Int, amount: Double): Int {
    val luminance = thorArtworkLuminance(argb)
    return retone(argb, (luminance + if (luminance < .5) amount else -amount).coerceIn(.04, .90))
}
private fun neutralPalette(graySum: Double, weight: Double): ThorArtworkPalette {
    if (weight <= 0) return ThorArtworkPalette.Neutral
    val gray = (graySum / weight).toInt().coerceIn(88, 164)
    return ThorArtworkPalette(opaque(gray, gray, gray), opaque(gray + 30, gray + 30, gray + 30),
        opaque(gray - 28, gray - 28, gray - 28), true)
}
