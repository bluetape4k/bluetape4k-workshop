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
 * 이름이 지정된 Micrometer Observation 아래에서 [block] 을 실행하고 coroutine resume 동안 scope 를 전파합니다.
 *
 * ## Behavior / Contract
 * - [registry] 안에서 [name] 을 가진 새 Observation 을 생성하고 시작합니다.
 * - coroutine [ThreadContextElement] 로 observation scope 를 열어 `withContext(...)` 호출 뒤 생성된 child observation 도 이 observation 을 parent 로 볼 수 있게 합니다.
 * - success/failure 양쪽 모두에서 stop 을 보장하도록 `finally` block 안에서 `observation.stop()` 을 호출합니다.
 * - non-cancellation error 에서는 stop 전에 `observation.error(e)` 로 throwable 을 기록합니다.
 * - structured concurrency 를 보존하려고 [CancellationException] 은 error recording 전에 즉시 다시 throw 합니다. `stop()` 은 여전히 `finally` 로 실행됩니다.
 * - upstream `withObservationSuspending` coroutine helper 의 happy path 에 `finally { observation.stop() }` 이 없어서 이 helper 가 필요합니다. 1.8.0-SNAPSHOT 과 1.9.1 에서 확인했습니다.
 *
 * ```kotlin
 * val result: User? = observed("user.service.get", registry) {
 *     repo.findById(id)
 * }
 * ```
 *
 * @param name Observation 이름입니다. blank 일 수 없습니다.
 * @param registry observation 을 등록할 [ObservationRegistry] 입니다.
 * @param block observation 아래에서 실행할 suspend block 입니다.
 * @return [block] 의 실행 결과입니다.
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
        // Kotlin 은 updateThreadContext() 가 반환한 값을 oldState 로 다시 전달합니다.
        oldState.close()
    }
}
