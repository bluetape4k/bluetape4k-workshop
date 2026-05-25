package io.bluetape4k.workshop.observability.advanced.observation

import io.bluetape4k.micrometer.observation.start
import io.bluetape4k.support.requireNotBlank
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block] under a named Micrometer Observation and propagates its scope through coroutine resumes.
 *
 * ## Behavior / Contract
 * - Creates and starts a new Observation with [name] in [registry].
 * - Opens the observation scope through a coroutine [ThreadContextElement], so child observations
 *   created after `withContext(...)` calls still see this observation as their parent.
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
    name.requireNotBlank("name")
    val observation = registry.start(name)
    return try {
        withContext(ObservationScopeContextElement(observation)) {
            block()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        observation.error(e)
        throw e
    } finally {
        observation.stop()
    }
}

private class ObservationScopeContextElement(
    private val observation: Observation,
) : ThreadContextElement<Observation.Scope>,
    AbstractCoroutineContextElement(ObservationScopeContextElement) {

    companion object Key : CoroutineContext.Key<ObservationScopeContextElement>

    override fun updateThreadContext(context: CoroutineContext): Observation.Scope =
        observation.openScope()

    override fun restoreThreadContext(context: CoroutineContext, oldState: Observation.Scope) {
        // Kotlin passes the value returned by updateThreadContext() back as oldState.
        oldState.close()
    }
}
