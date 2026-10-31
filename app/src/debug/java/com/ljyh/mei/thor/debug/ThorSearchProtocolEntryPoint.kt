package com.ljyh.mei.thor.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/** Reuses the installed client's actual session interceptor for opt-in read diagnostics. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThorSearchProtocolEntryPoint {
    fun okHttpClient(): OkHttpClient
}
