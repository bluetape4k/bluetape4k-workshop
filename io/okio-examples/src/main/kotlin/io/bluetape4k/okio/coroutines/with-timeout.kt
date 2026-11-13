package io.bluetape4k.okio.coroutines

import okio.Timeout
import kotlin.time.Duration.Companion.nanoseconds

suspend fun <T: Any> withTimeoutOrNull(
    timeout: Timeout,
    block: suspend () -> T,
): T? {
    if (timeout == Timeout.NONE || (timeout.timeoutNanos() == 0L && !timeout.hasDeadline())) {
        return block()
    }

    val now = System.nanoTime()
    val waitNanos = when {
        timeout.timeoutNanos() != 0L &&
                timeout.hasDeadline() ->
            minOf(
                timeout.timeoutNanos(),
                timeout.deadlineNanoTime() - now
            )

        timeout.timeoutNanos() != 0L  -> timeout.timeoutNanos()
        timeout.hasDeadline()         -> timeout.deadlineNanoTime() - now
        else                          -> throw IllegalArgumentException("Unexpected Timeout state")
    }

    return kotlinx.coroutines.withTimeoutOrNull(waitNanos.nanoseconds) {
        block()
    }
}
