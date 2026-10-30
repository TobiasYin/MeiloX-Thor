package com.ljyh.mei.thor.artwork

/** Limit placeholder/spine labels without cutting a UTF-16 surrogate pair. Called per artwork. */
internal fun caseLabel(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    val count = text.codePointCount(0, text.length)
    return if (count <= maxCodePoints) text else text.substring(0, text.offsetByCodePoints(0, maxCodePoints))
}
