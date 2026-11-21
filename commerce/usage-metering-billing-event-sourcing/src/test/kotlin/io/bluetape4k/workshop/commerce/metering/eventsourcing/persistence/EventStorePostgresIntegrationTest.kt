package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventStorePostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class EventStorePostgresIntegrationTest {

    companion object: KLogging()

/**
 * fixture은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = EventStoreDatabaseFixture()
/**
 * repository은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = EventStoreRepository()

    @Test
/**
 * 이 시나리오는 "append validates expected version and preserves a hash chain" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `append validates expected version and preserves a hash chain`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "usage-1")

        val first = fixture.executor.transaction {
            repository.append(stream, 0, listOf(event("source-1")))
        }
        val second = fixture.executor.transaction {
            repository.append(stream, 1, listOf(event("source-2")))
        }

        log.debug { "first: $first" }
        log.debug { "second: $second" }

        first.single().streamVersion shouldBeEqualTo 1L
        second.single().previousHash shouldBeEqualTo first.single().eventHash

        fixture.executor.transaction {
            repository.load(stream).map { it.streamVersion }
        } shouldBeEqualTo listOf(1L, 2L)

        assertFailsWith<OptimisticConcurrencyException> {
            fixture.executor.transaction {
                repository.append(stream, 0, listOf(event("stale")))
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "recorded time comes from PostgreSQL instead of the application clock" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `recorded time comes from PostgreSQL instead of the application clock`() {
        fixture.reset()
        val applicationTime = Instant.EPOCH
        val databaseTimedRepository = EventStoreRepository(Clock.fixed(applicationTime, ZoneOffset.UTC))
        val stream = StreamKey("tenant-a", "Usage", "database-time")

        val persisted = fixture.executor.transaction {
            databaseTimedRepository.append(stream, 0, listOf(event("database-time"))).single()
        }

        persisted.recordedAt shouldBeGreaterThan Instant.parse("2020-01-01T00:00:00Z")
        fixture.executor.transaction {
            databaseTimedRepository.load(stream).single().recordedAt
        } shouldBeEqualTo persisted.recordedAt
    }

    @Test
/**
 * 이 시나리오는 "tenant is mandatory when loading a stream" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant is mandatory when loading a stream`() {
        fixture.reset()
        val tenantA = StreamKey("tenant-a", "Usage", "same-id")
        val tenantB = StreamKey("tenant-b", "Usage", "same-id")

        fixture.executor.transaction {
            repository.append(tenantA, 0, listOf(event("a")))
        }
        fixture.executor.transaction {
            repository.append(tenantB, 0, listOf(event("b")))
        }

        fixture.executor.transaction {
            repository.load(tenantA).single().payload
        }.sourceId() shouldBeEqualTo "a"

        fixture.executor.transaction {
            repository.load(tenantB).single().payload
        }.sourceId() shouldBeEqualTo "b"
    }

    @Test
/**
 * 이 시나리오는 "multi-stream append locks and returns streams in canonical order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multi-stream append locks and returns streams in canonical order`() {
        fixture.reset()
        val later = StreamKey("tenant-a", "Usage", "z-stream")
        val earlier = StreamKey("tenant-a", "Meter", "a-stream")

        val appended = fixture.executor.transaction {
            repository.appendAll(
                listOf(
                    StreamAppend(later, 0, listOf(event("later"))),
                    StreamAppend(earlier, 0, listOf(event("earlier"))),
                ),
            )
        }

        appended.map { it.stream } shouldBeEqualTo listOf(earlier, later)
    }

    @Test
/**
 * 이 시나리오는 "only one of twenty writers can append the same expected version" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `only one of twenty writers can append the same expected version`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "contended")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)

        try {
            val outcomes = List(20) { index ->
                pool.submit<Boolean> {
                    start.await()
                    try {
                        fixture.executor.transaction {
                            repository.append(stream, 0, listOf(event("source-$index")))
                        }
                        true
                    } catch (_: OptimisticConcurrencyException) {
                        false
                    }
                }
            }
            start.countDown()

            outcomes.count { it.get() } shouldBeEqualTo 1
            fixture.executor.transaction {
                repository.load(stream) shouldHaveSize 1
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "generic repository mutation is blocked for stored events" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `generic repository mutation is blocked for stored events`() {
        fixture.reset()
        val stream = StreamKey("tenant-a", "Usage", "immutable")
        val stored = fixture.executor.transaction {
            repository.append(stream, 0, listOf(event("immutable"))).single()
        }

        assertFailsWith<UnsupportedOperationException> {
            fixture.executor.transaction {
                repository.save(DomainEventEntity[stored.eventId])
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "global position keyset tolerates sequence gaps" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `global position keyset tolerates sequence gaps`() {
        fixture.reset()
        val firstStream = StreamKey("tenant-a", "Usage", "first")
        val rolledBackStream = StreamKey("tenant-a", "Usage", "rolled-back")
        val laterStream = StreamKey("tenant-a", "Usage", "later")

        val first = fixture.executor.transaction {
            repository.append(firstStream, 0, listOf(event("first"))).single()
        }

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                repository.append(rolledBackStream, 0, listOf(event("rolled-back")))
                error("rollback after consuming a sequence value")
            }
        }
        val later = fixture.executor.transaction {
            repository.append(laterStream, 0, listOf(event("later"))).single()
        }

        val page = fixture.executor.transaction {
            repository.loadAfterGlobalPosition(first.globalPosition, 10)
        }

        page.map { it.eventId }.shouldBeEqualTo(listOf(later.eventId))
        later.globalPosition.shouldBeGreaterThan(first.globalPosition + 1)
    }

/**
 * event은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(sourceId: String): NewEvent =
        NewEvent(
            eventId = UUID.randomUUID(),
            event = UsageAccepted(
                sourceSystem = "gateway",
                sourceEventId = sourceId,
                meterCode = "api_calls",
                quantity = BigDecimal.TEN,
                occurredAt = Instant.parse("2026-07-01T00:00:00Z"),
            ),
            payload = """{"sourceEventId":"$sourceId","quantity":10}""",
            metadata = """{"actor":"tenant-a"}""",
            occurredAt = Instant.parse("2026-07-01T00:00:00Z"),
        )

/**
 * sourceId은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun String.sourceId(): String =
        substringAfter("sourceEventId\":\"")
            .substringBefore('"')
}
