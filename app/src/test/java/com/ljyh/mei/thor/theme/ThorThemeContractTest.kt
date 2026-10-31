package com.ljyh.mei.thor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParser
import com.ljyh.mei.thor.model.ThorThemePack
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class ThorThemeContractTest {
    private fun designFile(name: String): String {
        val path = listOf(Path.of("docs/thor/$name"), Path.of("../docs/thor/$name"))
            .first { Files.isRegularFile(it) }
        return Files.readString(path)
    }

    @Test fun allSixPalettesAndMaterialGeometryMatchTheApprovedJson() {
        val themes = JsonParser.parseString(designFile("theme-tokens.json")).asJsonObject["themes"].asJsonObject
        for (pack in ThorThemePack.entries) for (night in listOf(false, true)) {
            val source = themes[pack.name.lowercase()].asJsonObject
            val expected = source["appearances"].asJsonObject[if (night) "night" else "day"].asJsonObject
            val actual = thorThemeTokens(pack, night)
            val colors = mapOf(
                "canvas" to actual.background, "surface" to actual.surface, "inset" to actual.inset,
                "ink" to actual.text, "muted" to actual.secondary, "line" to actual.outline, "edge" to actual.edge,
                "accent" to actual.accent, "onAccent" to actual.onAccent, "selected" to actual.selected,
                "selectedInk" to actual.selectedText, "focus" to actual.focus, "raisedTop" to actual.raisedTop,
                "raisedBottom" to actual.raisedBottom, "primaryTop" to actual.primaryTop, "primaryBottom" to actual.primaryBottom,
                "highlight" to actual.highlight, "contact" to actual.contact, "shadow" to actual.shadow,
                "primaryHighlight" to actual.primaryHighlight, "primaryLowlight" to actual.primaryLowlight,
                "paneGlint" to actual.paneGlint, "grain" to actual.grain, "ambient" to actual.ambient, "bezel" to actual.bezel,
                "caseEdge" to actual.caseEdge, "caseBase" to actual.caseBase, "caseHighlight" to actual.caseHighlight,
                "caseReflection" to actual.caseReflection, "caseSpineLight" to actual.caseSpineLight,
                "caseSpineDark" to actual.caseSpineDark, "caseInk" to actual.caseText, "caseShadow" to actual.caseShadow,
            )
            assertEquals(expected.keySet(), colors.keys)
            colors.forEach { (role, color) ->
                val rgba = expected[role].asString.removePrefix("#")
                val argb = if (rgba.length == 6) "FF$rgba" else rgba.takeLast(2) + rgba.take(6)
                assertEquals("${pack.name}/$night/$role", argb.toLong(16).toInt(), color.toArgb())
            }
            assertEquals(actual.raisedTop, actual.elevated)
            val shape = source["shape"].asJsonObject
            assertEquals(shape["control"].asInt.dp, actual.controlRadius)
            assertEquals(shape["card"].asInt.dp, actual.cardRadius)
            assertEquals(shape["small"].asInt.dp, actual.smallRadius)
            assertEquals(shape["deck"].asInt.dp, actual.deckRadius)
            val light = source["lighting"].asJsonObject
            assertEquals(light["shadowBlur"].asInt.dp, actual.shadowBlur)
            assertEquals(light["shadowLift"].asInt.dp, actual.shadowLift)
            assertEquals(light["glintAngle"].asFloat, actual.glintAngle)
            val lyrics = JsonParser.parseString(designFile("theme-tokens.json")).asJsonObject["foundation"].asJsonObject["lyricLayout"].asJsonObject
            assertEquals(lyrics["rowGap"].asInt.dp, actual.lyricLayout.rowGap)
            assertEquals(lyrics["rowVerticalInset"].asInt.dp, actual.lyricLayout.rowVerticalInset)
            assertEquals(lyrics["auxiliaryTopGap"].asInt.dp, actual.lyricLayout.auxiliaryTopGap)
            assertEquals(lyrics["auxiliaryGap"].asInt.dp, actual.lyricLayout.auxiliaryGap)
            assertEquals(lyrics["lineHeightEm"].asFloat.em, actual.lyricLayout.lineHeight)
            assertEquals(lyrics["edgeFadeTop"].asInt.dp, actual.lyricLayout.edgeFadeTop)
            assertEquals(lyrics["edgeFadeBottom"].asInt.dp, actual.lyricLayout.edgeFadeBottom)
            assertEquals(lyrics["followAnchorFraction"].asFloat, actual.lyricLayout.followAnchorFraction, 0f)
            assertEquals(lyrics["viewportVerticalInsetFraction"].asFloat, actual.lyricLayout.viewportVerticalInsetFraction, 0f)
            assertEquals(lyrics["titleGap"].asInt.dp, actual.lyricLayout.titleGap)
            val typography = JsonParser.parseString(designFile("theme-tokens.json")).asJsonObject["foundation"].asJsonObject["typography"].asJsonObject
            assertEquals(typography["lyricCurrent"].asJsonObject["size"].asInt.sp, actual.typography.lyricCurrent)
            assertEquals(typography["lyricAdjacent"].asJsonObject["size"].asInt.sp, actual.typography.lyricAdjacent)
        }
    }

    @Test fun prototypeAndJsonShareTheSameSourceValues() {
        val html = designFile("prototype.fragment.html")
        val json = Regex("const themeSystem = (\\{[^\\n]+});").find(html)!!.groupValues[1]
        assertEquals(JsonParser.parseString(designFile("theme-tokens.json")), JsonParser.parseString(json))
    }

    @Test fun reducedOptionsHaveIndependentImmediateFallbacksAndExactTimings() {
        val normal = thorThemeTokens(ThorThemePack.Glass, true)
        assertEquals(120, normal.pressMs); assertEquals(180, normal.surfaceMs)
        assertEquals(320, normal.caseMs); assertEquals(170, normal.menuEnterMs)
        assertEquals(1.5.dp, normal.selectionStroke); assertEquals(.45f, normal.disabledOpacity)
        assertEquals(normal.background.copy(alpha = .58f), normal.scrim)
        val opaque = thorThemeTokens(ThorThemePack.Glass, true, reduceTransparency = true)
        assertEquals(Color.Transparent, opaque.paneGlint); assertEquals(Color.Transparent, opaque.grain)
        assertEquals(Color.Transparent, opaque.caseReflection); assertEquals(normal.surface, opaque.surface)
        assertEquals(120, opaque.pressMs)
        val still = thorThemeTokens(ThorThemePack.Glass, true, reduceMotion = true)
        assertEquals(0, still.pressMs); assertEquals(0, still.surfaceMs); assertEquals(normal.paneGlint, still.paneGlint)
    }
}
