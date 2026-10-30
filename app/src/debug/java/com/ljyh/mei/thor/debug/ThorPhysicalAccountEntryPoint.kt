package com.ljyh.mei.thor.debug

import com.ljyh.mei.data.repository.MeloXRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug instrumentation access to the installed application's existing repository and session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThorPhysicalAccountEntryPoint {
    fun meloXRepository(): MeloXRepository
}
