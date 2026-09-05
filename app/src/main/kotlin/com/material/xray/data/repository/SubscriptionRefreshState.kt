package com.material.xray.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SubscriptionRefreshState {
    private val refreshLocks = List(REFRESH_LOCK_COUNT) { Mutex() }
    private val refreshCounts = mutableMapOf<Long, Int>()
    private val _refreshingIds = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingIds = _refreshingIds.asStateFlow()

    suspend fun <T> withRefreshLock(subscriptionId: Long, block: suspend () -> T): T = withRefreshTracking(subscriptionId) {
        val lock = refreshLocks[Math.floorMod(subscriptionId.hashCode(), refreshLocks.size)]
        lock.withLock { block() }
    }

    suspend fun <T> withRefreshTracking(subscriptionId: Long, block: suspend () -> T): T {
        synchronized(refreshCounts) {
            refreshCounts[subscriptionId] = refreshCounts.getOrDefault(subscriptionId, 0) + 1
            _refreshingIds.value = refreshCounts.keys.toSet()
        }
        try {
            return block()
        } finally {
            synchronized(refreshCounts) {
                val remaining = refreshCounts.getValue(subscriptionId) - 1
                if (remaining == 0) {
                    refreshCounts.remove(subscriptionId)
                } else {
                    refreshCounts[subscriptionId] = remaining
                }
                _refreshingIds.value = refreshCounts.keys.toSet()
            }
        }
    }

    private companion object {
        const val REFRESH_LOCK_COUNT = 32
    }
}
