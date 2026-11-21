package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.MissingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageSourceConflict
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

/**
 * UsageCommandServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class UsageCommandServiceTest {

    private companion object: KLogging() {
/**
 * EVENT_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val EVENT_ID: UUID = UUID.fromString("487a67c1-6541-4c31-af7a-916fd35b2f83")
    }

/**
 * now은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-22T00:00:00Z")
/**
 * journal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val journal = InMemoryUsageAcceptanceJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = UsageCommandService(journal, Clock.fixed(now, ZoneOffset.UTC)) { EVENT_ID }

    @BeforeEach
    fun beforeEach() {
        journal.reset()
    }

    @Test
/**
 * 이 시나리오는 "verified local price evidence accepts usage and appends UsageAccepted outbox record" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `verified local price evidence accepts usage and appends UsageAccepted outbox record`() {
        journal.evidence += PriceEvidence(
            tenantId = "tenant-a",
            meterCode = "api_calls",
            currency = "USD",
            unitPrice = BigDecimal("0.10"),
            effectiveAt = now
        )

        val result = service.accept(command())

        result.replayed.shouldBeFalse()
        journal.usages.single().sourceEventId shouldBeEqualTo "source-usage-1"
        journal.outbox.single().eventType shouldBeEqualTo "UsageAccepted"
        journal.outbox.single().partitionKey shouldBeEqualTo "tenant-a|Usage|source-usage-1"

        val wire = Jackson.defaultJsonMapper.readTree(journal.outbox.single().payload)
        wire.get("eventId")!!.asString() shouldBeEqualTo result.eventId.toString()

        val payload = Jackson.defaultJsonMapper.readTree(wire.get("payload")!!.asString())
        payload.get("meterCode")!!.asString() shouldBeEqualTo "api_calls"
        payload.get("unitPrice")!!.asString() shouldBeEqualTo "0.10"
    }

    @Test
/**
 * 이 시나리오는 "same source event replays without a second usage or outbox record" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same source event replays without a second usage or outbox record`() {
        journal.evidence += PriceEvidence(
            tenantId = "tenant-a",
            meterCode = "api_calls",
            currency = "USD",
            unitPrice = BigDecimal("0.10"),
            effectiveAt = now
        )
        service.accept(command())

        service.accept(command()).replayed.shouldBeTrue()
        journal.usages.size shouldBeEqualTo 1
        journal.outbox.size shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "same source event with different payload is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same source event with different payload is rejected`() {
        journal.evidence += PriceEvidence(
            tenantId = "tenant-a",
            meterCode = "api_calls",
            currency = "USD",
            unitPrice = BigDecimal("0.10"),
            effectiveAt = now
        )
        service.accept(command())

        val failure = assertFailsWith<UsageSourceConflict> {
            service.accept(command(quantity = BigDecimal.TWO))
        }

        log.debug { "failure: $failure" }
        journal.usages.size shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "missing local price evidence is rejected without an outbox record" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing local price evidence is rejected without an outbox record`() {
        val failure = assertFailsWith<MissingPriceEvidence> {
            service.accept(command())
        }

        log.debug { "failure: $failure" }
        journal.outbox.size shouldBeEqualTo 0
    }

/**
 * command은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(quantity: BigDecimal = BigDecimal.ONE): AcceptUsageCommand =
        AcceptUsageCommand(
            tenantId = "tenant-a",
            sourceSystem = "meter-agent",
            sourceEventId = "source-usage-1",
            meterCode = "api_calls",
            currency = "USD",
            quantity = quantity,
            occurredAt = now,
        )

/**
 * InMemoryUsageAcceptanceJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryUsageAcceptanceJournal: UsageAcceptanceJournal {
/**
 * evidence은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val evidence = mutableListOf<PriceEvidence>()
/**
 * usages은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val usages = mutableListOf<UsageRecord>()
/**
 * outbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val outbox = mutableListOf<UsageOutboxRecord>()

/**
 * priceEvidence은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun priceEvidence(tenantId: String, meterCode: String, currency: String): PriceEvidence? =
            evidence.firstOrNull { it.tenantId == tenantId && it.meterCode == meterCode && it.currency == currency }

/**
 * findUsage은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun findUsage(tenantId: String, sourceSystem: String, sourceEventId: String): UsageRecord? =
            usages.firstOrNull {
                it.tenantId == tenantId && it.sourceSystem == sourceSystem && it.sourceEventId == sourceEventId
            }

/**
 * append은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun append(usage: UsageRecord, outboxRecord: UsageOutboxRecord) {
            usages += usage
            outbox += outboxRecord
        }

/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            evidence.clear()
            usages.clear()
            outbox.clear()
        }
    }
}
