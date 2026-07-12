package dev.yatori.mobile.app.ocr

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps one closeable resource warm while in use, then releases it after an idle timeout. */
internal class IdleCloseableResource<T : Closeable>(
    private val scope: CoroutineScope,
    private val idleTimeoutMillis: Long,
    private val create: () -> T,
) {
    private val mutex = Mutex()
    private var value: T? = null
    private var activeUses = 0
    private var releaseJob: Job? = null

    suspend fun <R> use(block: suspend (T) -> R): R {
        val resource = mutex.withLock {
            releaseJob?.cancel()
            releaseJob = null
            (value ?: create().also { value = it }).also { activeUses++ }
        }
        return try {
            block(resource)
        } finally {
            mutex.withLock {
                activeUses--
                if (activeUses == 0) scheduleRelease()
            }
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(idleTimeoutMillis)
            mutex.withLock {
                if (activeUses == 0) {
                    value?.close()
                    value = null
                    releaseJob = null
                }
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            releaseJob?.cancel()
            releaseJob = null
            value?.close()
            value = null
        }
    }
}
