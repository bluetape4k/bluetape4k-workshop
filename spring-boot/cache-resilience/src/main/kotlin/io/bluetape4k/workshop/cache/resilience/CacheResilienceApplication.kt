package io.bluetape4k.workshop.cache.resilience

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Cache Resilience 예제 application 입니다.
 *
 * Resilience4j CircuitBreaker 와 bluetape4k `SuspendDecorators` API 를 사용해
 * Redis primary cache 와 Caffeine local fallback 을 보여줍니다.
 *
 * ## Failure flow
 * 1. 요청은 CLOSED 상태의 CircuitBreaker 를 거쳐 Redis 에 접근하고 cache hit/miss 를 확인합니다.
 * 2. Redis failure 가 누적되면 CircuitBreaker 가 failure rate 를 계산하고 threshold 이상에서 OPEN 됩니다.
 * 3. 후속 call 은 OPEN 상태의 CircuitBreaker 에서 fallback 되어 Caffeine local cache 로 이동합니다.
 * 4. `waitDurationInOpenState` 이후 HALF-OPEN 으로 전환되어 Redis probe 를 수행합니다.
 * 5. Redis 가 recovered 되면 CircuitBreaker 는 CLOSED 로 돌아가고 Redis 사용을 재개합니다.
 */
@SpringBootApplication
class CacheResilienceApplication {
    companion object : KLoggingChannel()
}

fun main(args: Array<String>) {
    runApplication<CacheResilienceApplication>(*args)
}
