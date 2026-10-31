package com.ljyh.mei.thor.test

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertTrue

/** Shared hard device gate. Callers retain their separate live/read and mutation opt-ins. */
internal object ThorLiveEnvironment {
    private val physicalRequested: Boolean
        get() = InstrumentationRegistry.getArguments().getString("thor.physical") == "true"

    val reportName: String
        get() = if (physicalRequested) "AYN_Thor_physical" else "thor_agent_dual_api36"

    fun requireRequestedDevice(context: Context) {
        val emulator = Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) || Build.PRODUCT.contains("sdk", ignoreCase = true)
        if (physicalRequested) {
            assertTrue("thor_live_physical_device_required", !emulator)
            val model = Build.MODEL.trim().replace(Regex("[\\s_]+"), " ")
            assertTrue("thor_live_ayn_thor_model_required", model.equals("AYN Thor", ignoreCase = true))
            assertTrue("thor_live_ayn_manufacturer_required", Build.MANUFACTURER.trim().equals("AYN", ignoreCase = true))
        } else {
            // Absence of physical opt-in never silently authorizes a physical device.
            assertTrue("thor_live_emulator_required", emulator)
        }
        val displays = context.getSystemService(DisplayManager::class.java).displays
        // Thor's physical panels have portrait natural modes (1080x1920 / 1080x1240),
        // while the host rotates them to landscape. Match panel dimensions independently of
        // that rotation; actual window orientation remains a separate native UI assertion.
        fun Display.hasPanelSize(long: Int, short: Int): Boolean = mode.let {
            maxOf(it.physicalWidth, it.physicalHeight) == long &&
                minOf(it.physicalWidth, it.physicalHeight) == short
        }
        fun Display.usable() = isValid && flags and Display.FLAG_PRIVATE == 0 &&
            state != Display.STATE_OFF && state != Display.STATE_UNKNOWN
        val upper = displays.filter { it.usable() && it.hasPanelSize(1920, 1080) }
        val lower = displays.filter {
            it.usable() && it.hasPanelSize(1240, 1080) && it.flags and Display.FLAG_PRESENTATION != 0
        }
        assertTrue("thor_live_upper_display_required", upper.isNotEmpty())
        assertTrue("thor_live_lower_display_required", lower.isNotEmpty())
        assertTrue("thor_live_two_distinct_displays_required", upper.any { top -> lower.any { it.displayId != top.displayId } })
    }
}
