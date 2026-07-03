package io.bluetape4k.workshop.resilience.controller

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.workshop.resilience.service.Service
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class BackendBControllerLifecycleTest {

    @Test
    fun `close shuts down the programmatic resilience scheduler`() {
        val scheduler = TestingExecutors.newScheduledExecutorService(corePoolSize = 1)
        val controller = backendBController(scheduler)

        try {
            scheduler.isShutdown.shouldBeFalse()

            controller.close()
            scheduler.isShutdown.shouldBeTrue()

            controller.close()
            scheduler.isShutdown.shouldBeTrue()
        } finally {
            scheduler.shutdownNow()
        }
    }

    private fun backendBController(scheduler: ScheduledExecutorService): BackendBController {
        return BackendBController(
            serviceB = StubBackendBService,
            circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
            threadPoolBulkheadRegistry = ThreadPoolBulkheadRegistry.ofDefaults(),
            bulkheadRegistry = BulkheadRegistry.ofDefaults(),
            retryRegistry = RetryRegistry.ofDefaults(),
            rateLimiterRegistry = RateLimiterRegistry.ofDefaults(),
            timeLimiterRegistry = TimeLimiterRegistry.ofDefaults(),
            scheduledExecutorService = scheduler,
        )
    }

    private object StubBackendBService: Service {
        override fun failure(): String = error("not used")
        override fun failureWithFallback(): String = error("not used")
        override fun success(): String = "ok"
        override fun successWithException(): String = error("not used")
        override fun ignoreException(): String = error("not used")
        override fun fluxSuccess(): Flux<String> = Flux.just("ok")
        override fun fluxFailure(): Flux<String> = Flux.error(IllegalStateException("not used"))
        override fun fluxTimeout(): Flux<String> = Flux.just("ok")
        override fun monoSuccess(): Mono<String> = Mono.just("ok")
        override fun monoFailure(): Mono<String> = Mono.error(IllegalStateException("not used"))
        override fun monoTimeout(): Mono<String> = Mono.just("ok")
        override fun futureSuccess(): CompletableFuture<String> = CompletableFuture.completedFuture("ok")
        override fun futureFailure(): CompletableFuture<String> = CompletableFuture.failedFuture(error("not used"))
        override fun futureTimeout(): CompletableFuture<String> = CompletableFuture.completedFuture("ok")
    }
}
