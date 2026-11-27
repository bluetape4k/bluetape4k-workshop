@file:Suppress("MaxLineLength") // Full receipt transitions remain visible as single assertions.

package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.persistence.CommandReceiptRepository
import io.bluetape4k.workshop.commerce.metering.persistence.MeteringDatabaseFixture
import io.bluetape4k.workshop.shared.time.MutableClock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
    private val fixture = MeteringDatabaseFixture()
/**
 * repository은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = CommandReceiptRepository()
/**
 * clock은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val clock = MutableClock(Instant.parse("2026-07-22T00:00:00Z"))
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = CommandReceiptService(repository, MeteringProperties(), clock)

    @AfterAll
    fun close(): Unit = fixture.close()

    @Test
/**
 * 이 시나리오는 "same request replays while a different fingerprint conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same request replays while a different fingerprint conflicts`() {
        fixture.resetAndSeed()
        val scope = scope("replay")
        val fingerprint = CommandFingerprint.request("usage", mapOf("quantity" to "1"))

        val acquired = transaction {
            service.acquire(scope, fingerprint)
        }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        transaction {
            service.succeed(
                receiptId = acquired.receipt.id,
                ownerToken = acquired.receipt.ownerToken,
                httpStatus = 201,
                response = "accepted"
            )
        }.shouldBeTrue()
        transaction {
            service.acquire(scope, fingerprint)
        } shouldBeEqualTo CommandAcquireResult.Replay(201, "accepted", failed = false)

        transaction {
            val fingerprint2 = CommandFingerprint.request("usage", mapOf("quantity" to "2"))
            service.acquire(scope, fingerprint2)
        } shouldBeEqualTo CommandAcquireResult.Conflict
    }

    @Test
/**
 * 이 시나리오는 "expired owner can be replaced and stale owner cannot complete" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired owner can be replaced and stale owner cannot complete`() {
        fixture.resetAndSeed()
        val scope = scope("takeover")
        val fingerprint = CommandFingerprint.request("usage", mapOf("quantity" to "1"))
        val first = transaction {
            service.acquire(scope, fingerprint)
        }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        transaction {
            service.acquire(scope, fingerprint)
        }.shouldBeInstanceOf<CommandAcquireResult.InProgress>()

        clock.advance(31.seconds())
        val second = transaction {
            service.acquire(scope, fingerprint)
        }.shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        second.takeover.shouldBeTrue()
        transaction {
            service.succeed(first.receipt.id, first.receipt.ownerToken, 201, "stale")
        }.shouldBeFalse()
        transaction {
            service.succeed(second.receipt.id, second.receipt.ownerToken, 201, "winner")
        }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "terminal response is bounded and cleanup never removes in progress receipts" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `terminal response is bounded and cleanup never removes in progress receipts`() {
        fixture.resetAndSeed()

        val terminal = transaction {
            service.acquire(scope("terminal"), CommandFingerprint.key("terminal"))
        }
            .shouldBeInstanceOf<CommandAcquireResult.Acquired>()
        val live = transaction {
            service.acquire(scope("live"), CommandFingerprint.key("live"))
        }
            .shouldBeInstanceOf<CommandAcquireResult.Acquired>()

        assertFailsWith<IllegalArgumentException> {
            transaction {
                service.succeed(
                    terminal.receipt.id,
                    terminal.receipt.ownerToken,
                    200,
                    "x".repeat(MeteringProperties.MAX_TERMINAL_RESPONSE_BYTES + 1),
                )
            }
        }
        transaction {
            service.succeed(terminal.receipt.id, terminal.receipt.ownerToken, 200, "ok")
        }.shouldBeTrue()
        clock.advance(25.hours())

        transaction { service.cleanupExpiredTerminal() } shouldBeEqualTo 1
        transaction { repository.find(scope("terminal")) }.shouldBeNull()
        transaction { repository.find(scope("live")) }.shouldNotBeNull()

        live.receipt.status.name shouldBeEqualTo "IN_PROGRESS"
    }

    @Test
/**
 * 이 시나리오는 "twenty contenders elect one receipt owner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `twenty contenders elect one receipt owner`() {
        fixture.resetAndSeed()
        val scope = scope("twenty-contenders")
        val fingerprint = CommandFingerprint.key("same-request")

        Executors.newFixedThreadPool(8).use { pool ->
            val results = pool.invokeAll(
                List(20) {
                    Callable {
                        transaction {
                            service.acquire(scope, fingerprint)
                        }
                    }
                },
            ).map { it.get() }

            results.count { it is CommandAcquireResult.Acquired } shouldBeEqualTo 1
            results.count { it is CommandAcquireResult.InProgress } shouldBeEqualTo 19
        }
    }

/**
 * scope은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scope(suffix: String): CommandReceiptScope =
        CommandReceiptScope(
            tenantId = "tenant-a",
            operation = "usage-ingest",
            keyDigest = CommandFingerprint.key("key-$suffix").value
        )

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T> transaction(crossinline block: () -> T): T =
        fixture.executor.transaction { block() }
}
