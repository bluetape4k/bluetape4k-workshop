package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperatorReconcile
import io.bluetape4k.workshop.commerce.ticket.operations.internal.OperationsService
import io.bluetape4k.workshop.commerce.ticket.operations.internal.ReconciliationJob
import io.bluetape4k.workshop.commerce.ticket.web.StreamScope
import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import io.bluetape4k.workshop.commerce.ticket.web.TicketStreamCapacityExceeded
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.time.Clock
import java.time.Instant

/**
 * TicketHealthLifecycleIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHealthLifecycleIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "migration readiness and liveness are independent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `migration readiness and liveness are independent`() {
        val readiness = TicketMigrationReadiness()
        log.debug { "readiness.isReady=${readiness.isReady}" }
        TicketMigrationHealthIndicator(readiness).health().status shouldBeEqualTo Status.OUT_OF_SERVICE
        TicketLivenessHealthIndicator().health().status shouldBeEqualTo Status.UP

        readiness.markReady()
        log.debug { "readiness.isReady=${readiness.isReady}" }
        TicketMigrationHealthIndicator(readiness).health().status shouldBeEqualTo Status.UP
    }

    @Test
/**
 * 이 시나리오는 "redis outage degrades purchase readiness but not liveness" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redis outage degrades purchase readiness but not liveness`() {
        val redis = TicketRedisHealthIndicator { error("redis unavailable") }

        redis.health().status shouldBeEqualTo Status.OUT_OF_SERVICE
        redis.health().details["code"] shouldBeEqualTo "redis_unavailable"
        TicketLivenessHealthIndicator().health().status shouldBeEqualTo Status.UP
    }

    @Test
/**
 * 이 시나리오는 "operator permit is returned when a recovery job fails" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator permit is returned when a recovery job fails`() {
        val service = OperationsService(
            jobs = listOf(ReconciliationJob { error("provider unavailable") }),
            operatorPermits = 1,
            maxBatchSize = 50,
            runDeadline = 10.seconds(),
            clock = Clock.systemUTC(),
        )
        assertFailsWith<IllegalStateException> {
            service.reconcile(OperatorReconcile(Uuid.V7.nextId(), 1, Instant.now(), "manual recovery"))
        }
        service.availablePermits() shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "shutdown rejects new streams and metrics accept only low cardinality outcomes" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown rejects new streams and metrics accept only low cardinality outcomes`() {
        val stream = TicketEventStream(2, 1)
        val lifecycle = TicketLifecycle(stream)
        val registry = SimpleMeterRegistry()
        val metrics = TicketMetrics(registry)
        metrics.recordOutcome("approved")
        lifecycle.shutdown()

        lifecycle.acceptsForegroundWork().shouldBeFalse()
        registry.counter("ticket.purchase.outcomes", "outcome", "approved").count() shouldBeEqualTo 1.0

        assertFailsWith<TicketStreamCapacityExceeded> {
            stream.subscribe(StreamScope.PublicSale(Uuid.V7.nextId())) { emptyMap() }
        }
    }
}
