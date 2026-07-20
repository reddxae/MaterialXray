package com.material.xray.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ServerSelectionCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withSelectionLock(block: suspend () -> T): T = mutex.withLock { block() }
}
