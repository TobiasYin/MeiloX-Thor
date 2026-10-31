package com.ljyh.mei.thor.model

/** The environmental background is independent of the material used by controls and the CD case. */
enum class ThorBackgroundMode(val title: String) {
    ArtworkFlow("封面流光"),
    SoftGradient("柔焦渐层"),
    Clean("纯净"),
}

/** Changes color and spatial deformation, without making the animation run faster. */
enum class ThorBackgroundIntensity(val title: String, val multiplier: Float) {
    Low("低", .55f),
    Medium("中", 1f),
    High("高", 1.35f),
}
