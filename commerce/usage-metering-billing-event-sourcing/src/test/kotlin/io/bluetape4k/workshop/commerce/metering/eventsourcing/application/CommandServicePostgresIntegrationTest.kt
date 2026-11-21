package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * CommandServicePostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CommandServicePostgresIntegrationTest {

    companion object: KLogging()

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * eventStore은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val eventStore = EventStoreRepository()
/**
 * codec은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val codec = DomainEventJsonCodec()
/**
 * meters은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val meters = MeterCommandService(eventStore, codec)
/**
 * usages은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val usages = UsageCommandService(eventStore, codec)
/**
 * billing은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val billing = BillingLifecycleCommandService(eventStore, codec)
/**
 * closer은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val closer = BillingCloseService(eventStore, codec)
/**
 * now은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-01T00:00:00Z")

    @Test
/**
 * 이 시나리오는 "meter registration and price activation use replayed invariants" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `meter registration and price activation use replayed invariants`() {
        fixture.reset()
        fixture.executor.transaction {
            meters.register("tenant-a", "api_calls", "request", "USD", now)
        }
        fixture.executor.transaction {
            meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.10"), "USD", now)
        }

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                meters.register("tenant-a", "api_calls", "request", "USD", now)
            }
        }
        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.20"), "USD", now)
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "twenty concurrent retries create one usage event and conflicts remain visible" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `twenty concurrent retries create one usage event and conflicts remain visible`() {
        fixture.reset()
        val usage = UsageAccepted("gateway", "source-1", "api_calls", BigDecimal.TEN, now)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)

        try {
            val results = List(20) {
                pool.submit<Boolean> {
                    start.await()
                    fixture.executor.transaction { usages.accept("tenant-a", usage, now).created }
                }
            }
            start.countDown()
            results.count { it.get() }.shouldBeEqualTo(1)
        } finally {
            pool.shutdownNow()
        }

        val replay = fixture.executor.transaction {
            usages.accept("tenant-a", usage, now)
        }
        log.debug { "replay=$replay" }
        replay.created.shouldBeFalse()

        fixture.executor.transaction {
            eventStore.load(replay.stream).size
        } shouldBeEqualTo 1

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                usages.accept("tenant-a", usage.copy(quantity = BigDecimal.ONE), now)
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "period close and invoice issue are replay guarded and tenant isolated" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `period close and invoice issue are replay guarded and tenant isolated`() {
        fixture.reset()
        fixture.executor.transaction {
            billing.open("tenant-a", "2026-07", "USD", now, now + 6.hours())
        }
        fixture.executor.transaction {
            billing.startClose("tenant-a", "2026-07", now + 6.hours())
        }
        fixture.executor.transaction {
            billing.issueInvoice("tenant-a", "invoice-1", BigDecimal.TEN, "USD", now)
        }

        fixture.executor.transaction {
            billing.issueInvoice("tenant-a", "invoice-1", BigDecimal.TEN, "USD", now)
        }.shouldBeFalse()

        fixture.executor.transaction {
            eventStore.load(StreamKey("tenant-b", "Invoice", "invoice-1")).size
        } shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "restartable close rates usage from event history without a projection" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `restartable close rates usage from event history without a projection`() {
        fixture.reset()
        fixture.executor.transaction {
            meters.register("tenant-a", "api_calls", "request", "USD", now)
        }
        fixture.executor.transaction {
            meters.activatePrice("tenant-a", "api_calls", BigDecimal("0.50"), "USD", now - 1.seconds())
            billing.open("tenant-a", "2026-07", "USD", now, now + 3600.seconds())
            usages.accept("tenant-a", UsageAccepted("gateway", "one", "api_calls", BigDecimal("2"), now), now)
            usages.accept("tenant-a", UsageAccepted("gateway", "two", "api_calls", BigDecimal("3"), now), now)
            billing.startClose("tenant-a", "2026-07", now + 6.hours())
        }

        fixture.executor.transaction {
            closer.closeNextBatch("tenant-a", "2026-07", 1, now)
        } shouldBeEqualTo BillingCloseBatchResult.APPLIED

        fixture.executor.transaction {
            closer.closeNextBatch("tenant-a", "2026-07", 1, now)
        } shouldBeEqualTo BillingCloseBatchResult.APPLIED

        fixture.executor.transaction {
            closer.closeNextBatch("tenant-a", "2026-07", 1, now)
        } shouldBeEqualTo BillingCloseBatchResult.FINALIZED

        val rated = fixture.executor.transaction {
            eventStore.loadByType(
                EventTypeQuery(
                    tenantId = "tenant-a",
                    eventType = "usage.rated",
                    startsAt = now - 1.seconds(),
                    endsAt = now + 6.hours(),
                    limit = 10
                ),
            )
        }
        rated.size.shouldBeEqualTo(2)
    }

    @Test
/**
 * 이 시나리오는 "concurrent close retries never duplicate a rated usage" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent close retries never duplicate a rated usage`() {
        fixture.reset()
        fixture.executor.transaction {
            meters.register("tenant-a", "api_calls", "request", "USD", now)
            meters.activatePrice("tenant-a", "api_calls", BigDecimal.ONE, "USD", now - 1.seconds())
            billing.open("tenant-a", "2026-07", "USD", now, now + 6.hours())
            usages.accept("tenant-a", UsageAccepted("gateway", "one", "api_calls", BigDecimal.ONE, now), now)
            billing.startClose("tenant-a", "2026-07", now.plusSeconds(3600))
        }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)

        try {
            val attempts = List(20) {
                pool.submit<BillingCloseBatchResult> {
                    start.await()
                    fixture.executor.transaction {
                        closer.closeNextBatch("tenant-a", "2026-07", 10, now)
                    }
                }
            }
            start.countDown()
            attempts.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        fixture.executor.transaction {
            closer.closeNextBatch("tenant-a", "2026-07", 10, now)
        }

        val rated = fixture.executor.transaction {
            eventStore.loadByType(
                EventTypeQuery(
                    tenantId = "tenant-a",
                    eventType = "usage.rated",
                    startsAt = now - 1.seconds(),
                    endsAt = now + 6.hours(),
                    limit = 10
                ),
            )
        }
        log.debug { "rated: $rated" }
        rated shouldHaveSize 1
    }
}
