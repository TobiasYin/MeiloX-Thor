package com.ljyh.mei.data.network

import android.content.Context
import android.os.IBinder
import android.view.KeyEvent

/** Single-request UI owner; no account data is carried across this boundary. */
class CaptchaUiHost(
    val context: Context,
    val parentWindowToken: IBinder,
    val onControllerKey: (KeyEvent) -> Boolean = { false },
)
