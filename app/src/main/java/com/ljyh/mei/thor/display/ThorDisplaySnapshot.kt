package com.ljyh.mei.thor.display

/** Hardware pixels identify the two supported profiles; they are never used as Compose dp. */
enum class ThorDeviceProfile(val title: String, val lowerWidth: Int, val lowerHeight: Int) {
    Thor("AYN Thor", 1240, 1080),
    PocketDuo("Retroid Pocket Duo", 1280, 960),
}

data class ThorDisplayDescriptor(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val state: Int,
    val presentationCapable: Boolean,
) {
    val landscapeWidth get() = maxOf(width, height)
    val landscapeHeight get() = minOf(width, height)
}

data class ThorDisplaySnapshot(
    val displays: List<ThorDisplayDescriptor> = emptyList(),
    val profile: ThorDeviceProfile? = null,
    val upperDisplayId: Int? = null,
    val lowerDisplayId: Int? = null,
    val lowerPresented: Boolean = false,
    val generation: Long = 0,
    val problem: String? = null,
) {
    val hasSupportedPair get() = profile != null && upperDisplayId != null && lowerDisplayId != null
    val isDualScreen get() = hasSupportedPair && lowerPresented
}

/** Prefer the host's actual upper display; do not accidentally choose an attached monitor. */
internal fun resolveThorDisplays(
    displays: List<ThorDisplayDescriptor>,
    hostDisplayId: Int?,
): ThorDisplaySnapshot {
    val ordered = displays.sortedBy { it.id }
    val upper = ordered.firstOrNull {
        it.id == hostDisplayId && it.landscapeWidth == 1920 && it.landscapeHeight == 1080
    } ?: return ThorDisplaySnapshot(displays = ordered, problem = "当前窗口不在受支持的上屏")
    for (profile in ThorDeviceProfile.entries) {
        val lower = ordered.firstOrNull {
            it.id != upper.id && it.landscapeWidth == profile.lowerWidth && it.landscapeHeight == profile.lowerHeight
        } ?: continue
        return ThorDisplaySnapshot(
            displays = ordered, profile = profile, upperDisplayId = upper.id, lowerDisplayId = lower.id,
            problem = if (lower.presentationCapable) null else "下屏未开放应用展示窗口",
        )
    }
    return ThorDisplaySnapshot(displays = ordered, upperDisplayId = upper.id, problem = "未连接受支持的下屏")
}
