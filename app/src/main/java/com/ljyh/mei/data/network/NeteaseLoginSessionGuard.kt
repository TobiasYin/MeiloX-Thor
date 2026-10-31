package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.Preferences
import com.ljyh.mei.constants.CookieKey
import java.io.IOException

/** Also represents signed-out state; a null expected session would skip that protection. */
internal fun Preferences.captureNeteaseLoginSession(): String =
    neteaseSessionFingerprint(this[CookieKey].orEmpty())

/** Call inside the same DataStore edit that commits a verified sign-in. */
internal fun Preferences.requireExpectedNeteaseLoginSession(expected: String) {
    if (captureNeteaseLoginSession() != expected) {
        throw IOException("账号已变更，请重新登录")
    }
}
