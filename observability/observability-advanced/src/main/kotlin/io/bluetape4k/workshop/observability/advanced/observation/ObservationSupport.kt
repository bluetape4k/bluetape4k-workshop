package io.bluetape4k.workshop.observability.advanced.observation

import io.bluetape4k.micrometer.observation.start
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] under a named Micrometer Observation, always stopping the observation on exit.
 *
 * ## Behavior / Contract
 * - Creates and starts a new Observation with [name] in [registry].
 * - Calls `observation.stop()` in a `finally` block — guarantees stop on both success and failure.
 * - Records the throwable via `observation.error(e)` before stopping on non-cancellation errors.
 * - Rethrows [CancellationException] immediately (before error recording) to preserve structured
 *   concurrency; `stop()` still runs via `finally`.
 * - Exists because the upstream `withObservationSuspending` coroutine helper is missing
 *   `finally { observation.stop() }` on the happy path (verified in 1.8.0-SNAPSHOT and 1.9.1).
 *
 * ```kotlin
 * val result: User? = observed("user.service.get", registry) {
 *     repo.findById(id)
 * }
 * ```
 *
 * @param name Observation name (must be non-blank).
 * @param registry [ObservationRegistry] to register the observation with.
 * @param block Suspend block to execute under the observation.
 * @return The result of [block].
 */
suspend fun <T> observed(
    name: String,
    registry: ObservationRegistry,
    block: suspend () -> T,
): T {
    val observation = registry.start(name)
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        observation.error(e)
        throw e
    } finally {
        observation.stop()
    }
}
