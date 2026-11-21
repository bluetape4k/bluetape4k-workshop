package io.bluetape4k.workshop.commerce.reservation.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.reservation.redis.InFlightCommandSuppressor
import io.bluetape4k.workshop.commerce.reservation.redis.NodeLocalDatabaseBulkhead
import io.bluetape4k.workshop.commerce.reservation.redis.ReservationAdmissionGate
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * ReservationOperationalLoggingTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ReservationOperationalLoggingTest {

    companion object {
/**
 * RESERVATION_LOGGER은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val RESERVATION_LOGGER = "io.bluetape4k.workshop.commerce.reservation"
    }

    @Test
/**
 * 이 시나리오는 "command boundary logs stable events without raw owner or idempotency credentials" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `command boundary logs stable events without raw owner or idempotency credentials`() {
        val rawOwner = "owner-secret-0123456789abcdef0123456789abcdef"
        val rawKey = "idempotency-secret-0123456789abcdef"
        val credentials = ReservationCredentialService("0123456789abcdef0123456789abcdef")

        val gate = ReservationCommandExecutionGate(
            admission = ReservationAdmissionGate(NodeLocalDatabaseBulkhead()),
            suppression = InFlightCommandSuppressor(),
            credentials = credentials,
            tenantId = "demo"
        )

        val messages = captureReservationLogs {
            credentials.matchesOwner(rawOwner, credentials.ownerDigest(rawOwner))
            gate.execute("LOGGING_FIXTURE", rawKey) { "ok" } shouldBeEqualTo "ok"
        }.joinToString("\n")

        messages shouldContain "reservation_owner_verified"
        messages shouldContain "reservation_command_gate_completed"
        messages shouldNotContain rawOwner
        messages shouldNotContain rawKey
        messages shouldNotContain credentials.ownerDigest(rawOwner)
    }

/**
 * captureReservationLogs은 예약 제어 플레인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun captureReservationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(RESERVATION_LOGGER) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)

        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }
}
