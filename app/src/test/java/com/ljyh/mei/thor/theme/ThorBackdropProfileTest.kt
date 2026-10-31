package com.ljyh.mei.thor.theme

import com.ljyh.mei.thor.model.ThorBackgroundIntensity
import com.ljyh.mei.thor.model.ThorBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorBackdropProfileTest {
    @Test fun serializedSelectionsAndIntensityScaleStayStable() {
        assertEquals(listOf("ArtworkFlow", "SoftGradient", "Clean"), ThorBackgroundMode.entries.map { it.name })
        assertEquals(listOf("Low", "Medium", "High"), ThorBackgroundIntensity.entries.map { it.name })
        assertEquals(.55f, ThorBackgroundIntensity.Low.multiplier, 0f)
        assertEquals(1f, ThorBackgroundIntensity.Medium.multiplier, 0f)
        assertEquals(1.35f, ThorBackgroundIntensity.High.multiplier, 0f)
        assertTrue(ThorBackgroundMode.entries.all { it.title.isNotBlank() })
        assertTrue(ThorBackgroundIntensity.entries.all { it.title.isNotBlank() })
    }

    @Test fun intensityIncreasesColorAndDeformationWithoutIncreasingSpeed() {
        for (mode in listOf(ThorBackgroundMode.ArtworkFlow, ThorBackgroundMode.SoftGradient)) {
            for (dark in listOf(false, true)) {
                val low = thorBackdropProfile(mode, ThorBackgroundIntensity.Low, dark)
                val medium = thorBackdropProfile(mode, ThorBackgroundIntensity.Medium, dark)
                val high = thorBackdropProfile(mode, ThorBackgroundIntensity.High, dark)
                assertTrue(low.colorStrength < medium.colorStrength)
                assertTrue(medium.colorStrength < high.colorStrength)
                assertTrue(low.deformationStrength < medium.deformationStrength)
                assertTrue(medium.deformationStrength < high.deformationStrength)
                assertEquals(low.speed, medium.speed, 0f)
                assertEquals(medium.speed, high.speed, 0f)
                assertEquals(low.primaryField, high.primaryField)
                assertEquals(low.secondaryField, high.secondaryField)
                assertEquals(low.quietRetention, high.quietRetention, 0f)
            }
        }
    }

    @Test fun dayAndNightHaveDifferentOpticalWeightsWithoutMovingTheFields() {
        for (mode in listOf(ThorBackgroundMode.ArtworkFlow, ThorBackgroundMode.SoftGradient)) {
            val day = thorBackdropProfile(mode, ThorBackgroundIntensity.Medium, false)
            val night = thorBackdropProfile(mode, ThorBackgroundIntensity.Medium, true)
            assertTrue(night.colorStrength > day.colorStrength)
            assertTrue(night.quietRetention > day.quietRetention)
            assertEquals(day.primaryField, night.primaryField)
            assertEquals(day.secondaryField, night.secondaryField)
            assertEquals(day.speed, night.speed, 0f)
        }
    }

    @Test fun reducedMotionRetainsTheSelectedStaticAppearance() {
        for (mode in ThorBackgroundMode.entries) for (intensity in ThorBackgroundIntensity.entries) {
            for (dark in listOf(false, true)) {
                val moving = thorBackdropProfile(mode, intensity, dark)
                val static = thorBackdropProfile(mode, intensity, dark, reduceMotion = true)
                assertEquals(moving.copy(speed = 0f, animate = false), static)
            }
        }
    }

    @Test fun inactiveAndPausedBackgroundsKeepTheirColorWithoutRequestingMotion() {
        for (mode in ThorBackgroundMode.entries) {
            val moving = thorBackdropProfile(mode, ThorBackgroundIntensity.High, true)
            val still = moving.copy(speed = 0f, animate = false)
            assertEquals(still, thorBackdropProfile(mode, ThorBackgroundIntensity.High, true, active = false))
            assertEquals(still, thorBackdropProfile(mode, ThorBackgroundIntensity.High, true, playing = false))
            assertEquals(still, thorBackdropProfile(mode, ThorBackgroundIntensity.High, true, active = false, playing = false))
        }
    }

    @Test fun reducedTransparencyAttenuatesEnvironmentalColorAndRemovesHighlights() {
        for (mode in ThorBackgroundMode.entries) for (intensity in ThorBackgroundIntensity.entries) {
            val normal = thorBackdropProfile(mode, intensity, false)
            val reduced = thorBackdropProfile(mode, intensity, false, reduceTransparency = true)
            assertEquals(normal.copy(colorStrength = normal.colorStrength * .70f, highlights = false), reduced)
        }
    }

    @Test fun cleanModeIsEmptyAtEveryIntensityAndAccessibilityCombination() {
        for (intensity in ThorBackgroundIntensity.entries) for (dark in listOf(false, true)) {
            for (motion in listOf(false, true)) for (transparency in listOf(false, true)) {
                val profile = thorBackdropProfile(ThorBackgroundMode.Clean, intensity, dark, motion, transparency)
                assertFalse(profile.visible)
                assertFalse(profile.animate)
                assertFalse(profile.highlights)
                assertEquals(0f, profile.colorStrength, 0f)
                assertEquals(0f, profile.deformationStrength, 0f)
                assertEquals(0f, profile.speed, 0f)
                assertEquals(0f, profile.artworkColorWeight, 0f)
            }
        }
    }

    @Test fun fieldsAreFullScreenAndTheLyricSideRetainsAQuietNonzeroColorField() {
        for (mode in listOf(ThorBackgroundMode.ArtworkFlow, ThorBackgroundMode.SoftGradient)) {
            for (dark in listOf(false, true)) {
                val profile = thorBackdropProfile(mode, ThorBackgroundIntensity.High, dark)
                val field = profile.primaryField
                assertTrue(field.centerX - field.radiusX < 0f)
                assertTrue(field.centerX + field.radiusX > 1f)
                assertTrue(field.centerY - field.radiusY < 0f)
                assertTrue(field.centerY + field.radiusY > 1f)
                assertTrue(profile.quietStartX in 0f..1f)
                assertTrue(profile.quietEndX in 0f..1f)
                assertTrue(profile.quietStartX < .52f)
                assertTrue(profile.quietEndX > .52f)
                assertTrue("The reading side must keep a broad visible color field", profile.quietEndX in .80f.. .95f)
                assertTrue("The old 6.5-10% retention erased the visible cover environment",
                    profile.quietRetention in .50f.. .70f)
            }
        }
    }

    @Test fun defaultArtworkFlowRetainsVisibleColorEvenWithReducedTransparency() {
        for (dark in listOf(false, true)) {
            val normal = thorBackdropProfile(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.Medium, dark)
            val opaque = thorBackdropProfile(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.Medium, dark,
                reduceTransparency = true)
            assertTrue(normal.colorStrength >= .9f)
            assertTrue(opaque.colorStrength >= .6f)
            assertTrue(opaque.quietRetention >= .5f)
            assertFalse(opaque.highlights)
        }
    }

    @Test fun softGradientKeepsCoverColorWithoutRequestingAFrameClock() {
        assertEquals(1f, thorBackdropProfile(ThorBackgroundMode.ArtworkFlow, ThorBackgroundIntensity.Medium, false).artworkColorWeight, 0f)
        assertEquals(0f, thorBackdropProfile(ThorBackgroundMode.Clean, ThorBackgroundIntensity.Medium, false).artworkColorWeight, 0f)
        for (dark in listOf(false, true)) for (intensity in ThorBackgroundIntensity.entries) {
            for (motion in listOf(false, true)) for (transparency in listOf(false, true)) {
                val gradient = thorBackdropProfile(ThorBackgroundMode.SoftGradient, intensity, dark, motion, transparency)
                assertEquals(.8f, gradient.artworkColorWeight, 0f)
                assertFalse(gradient.animate)
                assertEquals(0f, gradient.speed, 0f)
                assertTrue(gradient.colorStrength > 0f)
                assertTrue(gradient.visible)
            }
        }
    }
}
