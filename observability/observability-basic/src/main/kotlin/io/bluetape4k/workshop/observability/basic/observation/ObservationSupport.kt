package io.bluetape4k.workshop.observability.basic.observation

import io.bluetape4k.micrometer.observation.start
import io.bluetape4k.support.requireNotBlank
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException

/**
 * 이름이 지정된 Micrometer Observation 아래에서 [block] 을 실행하고 exit 시 항상 observation 을 stop 합니다.
 *
 * ## Behavior / Contract
 * - [registry] 안에서 [name] 을 가진 새 Observation 을 생성하고 시작합니다.
 * - success/failure 양쪽 모두에서 stop 을 보장하도록 `finally` block 안에서 `observation.stop()` 을 호출합니다.
 * - non-cancellation error 에서는 stop 전에 `observation.error(e)` 로 throwable 을 기록합니다.
 * - structured concurrency 를 보존하려고 [CancellationException] 은 error recording 전에 즉시 다시 throw 합니다. `stop()` 은 여전히 `finally` 로 실행됩니다.
 * - upstream `withObservationSuspending` coroutine helper 의 happy path 에 `finally { observation.stop() }` 이 없어서 이 helper 가 필요합니다. 1.8.0-SNAPSHOT 과 1.9.1 에서 확인했습니다.
 *
 * ```kotlin
 * val order: Order? = observed("order.service.fetch", registry) {
 *     inventoryClient.fetchInventory(orderId)?.let { ... }
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
    val observation = registry.start(name.requireNotBlank("name"))
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
