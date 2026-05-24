package io.bluetape4k.workshop.cache.resilience

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Cache Resilience example application.
 *
 * Demonstrates Redis primary cache with Caffeine local fallback using
 * Resilience4j CircuitBreaker and the bluetape4k `SuspendDecorators` API.
 *
 * ## Failure flow:
 * 1. Request → CircuitBreaker (CLOSED) → Redis → cache hit/miss
 * 2. Redis failure → CircuitBreaker records failure → rate ≥ threshold → OPEN
 * 3. Subsequent calls → CircuitBreaker (OPEN) → fallback → Caffeine local cache
 * 4. After `waitDurationInOpenState` → HALF-OPEN → probe Redis
 * 5. Redis recovered → CircuitBreaker → CLOSED → Redis resumes
 */
@SpringBootApplication
class CacheResilienceApplication {
    companion object : KLoggingChannel()
}

fun main(args: Array<String>) {
    runApplication<CacheResilienceApplication>(*args)
}
