@file:Suppress("MaxLineLength") // Exact timestamps and receipt scenarios stay readable at each transaction boundary.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.NewEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.*


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * CommandReceiptPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CommandReceiptPostgresIntegrationTest {

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
    private val repository = CommandReceiptRepository()
/**
 * eventStore은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val eventStore = EventStoreRepository()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = CommandReceiptService(repository, 30.seconds(), 24.hours())
/**
 * scope은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val scope = CommandScope("tenant-a", "meter-register", CommandFingerprint.key("stable-key"))
/**
 * fingerprint은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fingerprint = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls"))

    @Test
/**
 * 이 시나리오는 "same command replays its exact terminal response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same command replays its exact terminal response`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")

        val owned = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.succeed(owned, 201, "{\"code\":\"api_calls\"}", now)
        }.shouldBeTrue()

        val replay = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }

        log.debug { "replay: $replay" }
        replay shouldBeEqualTo CommandAcquireResult.Replay(201, "{\"code\":\"api_calls\"}")
    }

    @Test
/**
 * 이 시나리오는 "same key with different fingerprint conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key with different fingerprint conflicts`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")

        fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }

        val result = fixture.executor.transaction {
            service.acquire(
                scope,
                CommandFingerprint.request("meter-register", mapOf("code" to "other")),
                now
            )
        }

        result shouldBeEqualTo CommandAcquireResult.Conflict
    }

    @Test
/**
 * 이 시나리오는 "expired owner is fenced after takeover" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired owner is fenced after takeover`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction {
            service.acquire(
                scope,
                fingerprint,
                acquiredAt
            )
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        val second = fixture.executor.transaction {
            service.acquire(scope, fingerprint, acquiredAt + 31.seconds())
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.succeed(first, 201, "{}", acquiredAt + 31.seconds())
        }.shouldBeFalse()

        fixture.executor.transaction {
            service.succeed(second, 201, "{}", acquiredAt + 31.seconds())
        }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "stale owner is rejected before event append" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale owner is rejected before event append`() {
        fixture.reset()
        val acquiredAt = Instant.parse("2026-07-01T00:00:00Z")
        val first = fixture.executor.transaction {
            service.acquire(
                scope,
                fingerprint,
                acquiredAt
            )
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        fixture.executor.transaction {
            service.acquire(scope, fingerprint, acquiredAt + 31.seconds())
        }

        assertFailsWith<CommandOwnerLostException> {
            fixture.executor.transaction {
                service.requireOwnership(first, acquiredAt + 31.seconds())
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "event append and terminal response roll back together" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event append and terminal response roll back together`() {
        fixture.reset()
        val now = Instant.parse("2026-07-01T00:00:00Z")
        val owned = fixture.executor.transaction {
            service.acquire(scope, fingerprint, now)
        }.shouldBeInstanceOf<CommandAcquireResult.Owned>()

        val stream = StreamKey("tenant-a", "Usage", "atomic")

        assertFailsWith<IllegalStateException> {
            fixture.executor.transaction {
                service.requireOwnership(owned, now)
                eventStore.append(stream, 0, listOf(usageEvent(now)))
                check(service.succeed(owned, 201, "{}", now))
                error("force_rollback")
            }
        }

        fixture.executor.transaction { eventStore.load(stream) }.shouldBeEmpty()
        fixture.executor.transaction {
            service.acquire(scope, fingerprint, now + 1.seconds())
        }.shouldBeInstanceOf<CommandAcquireResult.InProgress>()
    }

/**
 * usageEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun usageEvent(now: Instant): NewEvent =
        NewEvent(
            eventId = UUID.randomUUID(),
            event = UsageAccepted(
                sourceSystem = "gateway",
                sourceEventId = "atomic",
                meterCode = "api_calls",
                quantity = BigDecimal.ONE,
                occurredAt = now
            ),
            payload = """{"sourceEventId":"atomic","quantity":1}""",
            metadata = """{"actor":"tenant-a"}""",
            occurredAt = now,
        )
}
