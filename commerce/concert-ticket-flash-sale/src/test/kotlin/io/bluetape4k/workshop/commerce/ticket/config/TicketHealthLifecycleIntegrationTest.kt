package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperatorReconcile
import io.bluetape4k.workshop.commerce.ticket.operations.internal.OperationsService
import io.bluetape4k.workshop.commerce.ticket.operations.internal.ReconciliationJob
import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import io.bluetape4k.workshop.commerce.ticket.web.TicketStreamCapacityExceeded
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class TicketHealthLifecycleIntegrationTest {
    @Test
    fun `migration readiness and liveness are independent`() {
        val readiness = TicketMigrationReadiness()
        TicketMigrationHealthIndicator(readiness).health().status shouldBeEqualTo Status.OUT_OF_SERVICE
        TicketLivenessHealthIndicator().health().status shouldBeEqualTo Status.UP
        readiness.markReady()
        TicketMigrationHealthIndicator(readiness).health().status shouldBeEqualTo Status.UP
    }

    @Test
    fun `redis outage degrades purchase readiness but not liveness`() {
        val redis = TicketRedisHealthIndicator(TicketRedisHealthProbe { error("redis unavailable") })

        redis.health().status shouldBeEqualTo Status.OUT_OF_SERVICE
        redis.health().details["code"] shouldBeEqualTo "redis_unavailable"
        TicketLivenessHealthIndicator().health().status shouldBeEqualTo Status.UP
    }

    @Test
    fun `operator permit is returned when a recovery job fails`() {
        val service = OperationsService(
            jobs = listOf(ReconciliationJob { error("provider unavailable") }),
            operatorPermits = 1,
            maxBatchSize = 50,
            runDeadline = Duration.ofSeconds(10),
            clock = Clock.systemUTC(),
        )
        assertFailsWith<IllegalStateException> {
            service.reconcile(OperatorReconcile(Uuid.V7.nextId(), 1, Instant.now(), "manual recovery"))
        }
        service.availablePermits() shouldBeEqualTo 1
    }

    @Test
    fun `shutdown rejects new streams and metrics accept only low cardinality outcomes`() {
        val stream = TicketEventStream(2, 1)
        val lifecycle = TicketLifecycle(stream)
        val registry = SimpleMeterRegistry()
        val metrics = TicketMetrics(registry)
        metrics.recordOutcome("approved")
        lifecycle.shutdown()

        lifecycle.acceptsForegroundWork() shouldBeEqualTo false
        registry.counter("ticket.purchase.outcomes", "outcome", "approved").count() shouldBeEqualTo 1.0
        assertFailsWith<TicketStreamCapacityExceeded> {
            stream.subscribe(io.bluetape4k.workshop.commerce.ticket.web.StreamScope.PublicSale(Uuid.V7.nextId())) { emptyMap() }
        }
    }
}
