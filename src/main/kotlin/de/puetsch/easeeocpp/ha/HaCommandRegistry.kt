package de.puetsch.easeeocpp.ha

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class HaCommandRegistry {
    private val lastIssuedId = AtomicLong(0)
    private val pendingResults = ConcurrentHashMap<Long, CompletableFuture<Boolean>>()

    fun reserveId(): Long = lastIssuedId.incrementAndGet()

    fun expectResult(id: Long): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        pendingResults[id] = result
        return result
    }

    fun completeResult(id: Long, success: Boolean) {
        pendingResults.remove(id)?.complete(success)
    }

    fun cancel(id: Long) {
        pendingResults.remove(id)?.cancel(false)
    }

    fun failAll() {
        pendingResults.keys.toList().forEach { id -> completeResult(id, success = false) }
    }
}
