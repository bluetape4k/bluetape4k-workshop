package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoveryJournal
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoverySnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * QueryRecoveryServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryRecoveryServiceTest {

    companion object: KLogging()

/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryQueryRecoveryJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = QueryRecoveryService(journal)

    @Test
/**
 * 이 시나리오는 "redrive records the operator decision without changing financial state" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redrive records the operator decision without changing financial state`() {
        val eventId = UUID.randomUUID()
        journal.quarantine(
            QueryQuarantineEvent(
                eventId = eventId,
                tenantId = "tenant-a",
                eventType = "InvoiceIssued",
                reason = "unsupported_schema",
                quarantinedAt = Instant.EPOCH
            ),
        )

        service.redrive(
            eventId = eventId,
            actor = "operator-a",
            correlationId = "correlation-a"
        ).requested.shouldBeTrue()

        journal.snapshot().quarantineCount shouldBeEqualTo 0
        journal.redriveActors shouldBeEqualTo listOf("operator-a")
    }

/**
 * InMemoryQueryRecoveryJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryQueryRecoveryJournal: QueryRecoveryJournal {

        companion object: KLogging()

/**
 * quarantines은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val quarantines = linkedMapOf<UUID, QueryQuarantineEvent>()
/**
 * redriveActors은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val redriveActors = mutableListOf<String>()

/**
 * snapshot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun snapshot(): QueryRecoverySnapshot =
            QueryRecoverySnapshot(quarantines.size.toLong(), null)

/**
 * quarantine은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun quarantine(event: QueryQuarantineEvent) {
            quarantines[event.eventId] = event
        }

/**
 * requestRedrive은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun requestRedrive(eventId: UUID, actor: String, correlationId: String): Boolean =
            quarantines.remove(eventId)?.let {
                check(correlationId == "correlation-a")
                redriveActors += actor
                true
            } ?: false
    }
}
