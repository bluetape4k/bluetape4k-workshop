package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireNotNull
import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

object HighContentionAwait {

    fun condition(
        timeout: Duration,
        pollInterval: Duration,
        description: String,
        probe: () -> Boolean,
    ) {
        val lastFailure = AtomicReference<Exception>()
        try {
            await(description)
                .pollInterval(pollInterval)
                .atMost(timeout)
                .until {
                    try {
                        probe()
                    } catch (error: Exception) {
                        lastFailure.set(error)
                        false
                    }
                }
        } catch (timeoutFailure: ConditionTimeoutException) {
            throw IllegalStateException(
                description,
                lastFailure.get() ?: timeoutFailure,
            )
        }
    }

    fun <T: Any> value(
        timeout: Duration,
        pollInterval: Duration,
        description: String,
        probe: () -> T?,
    ): T {
        val value = AtomicReference<T>()
        condition(timeout, pollInterval, description) {
            probe()?.let {
                value.set(it)
                true
            } ?: false
        }
        return value.get().requireNotNull(description)
    }

}
