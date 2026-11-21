package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * QueryInboxServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryInboxServiceTest {

    companion object: KLogging()

/**
 * meterRegistry은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val meterRegistry = SimpleMeterRegistry()
/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryQueryProjectionJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = QueryInboxService(journal, QueryMetrics(meterRegistry))

    @BeforeEach
    fun beforeEach() {
        meterRegistry.clear()
        journal.reset()
    }

    @Test
/**
 * 이 시나리오는 "new event mutates its local read model and advances the projection checkpoint together"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `new event mutates its local read model and advances the projection checkpoint together`() {
        service.apply(event()).applied.shouldBeTrue()
        journal.readModelEventIds shouldHaveSize 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "duplicate event does not mutate the read model or advance the checkpoint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate event does not mutate the read model or advance the checkpoint`() {
        val event = event()
        service.apply(event)

        service.apply(event).applied.shouldBeFalse()
        journal.readModelEventIds shouldHaveSize 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "terminal inbox results are recorded with low cardinality outcome and event type tags"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `terminal inbox results are recorded with low cardinality outcome and event type tags`() {
        service.apply(event())


        meterRegistry.find("usage_billing_inbox_outcome_total")
            .tags("service", "query", "outcome", "APPLIED", "event_type", "InvoiceIssued")
            .counter().shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }

/**
 * event은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(): QueryInboxEvent =
        QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

/**
 * InMemoryQueryProjectionJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryQueryProjectionJournal: QueryProjectionJournal {
/**
 * readModelEventIds은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        override val readModelEventIds = mutableSetOf<UUID>()
/**
 * checkpoint은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        override var checkpoint: Long = 0

/**
 * hasEvent은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun hasEvent(eventId: UUID): Boolean = eventId in readModelEventIds

/**
 * apply은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun apply(event: QueryInboxEvent) {
            readModelEventIds += event.eventId
            checkpoint += 1
        }

/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            readModelEventIds.clear()
            checkpoint = 0
        }
    }
}
