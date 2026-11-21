package io.bluetape4k.workshop.commerce.usagebilling.meter.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterIdempotencyConflict
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

/**
 * MeterCommandServiceTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeterCommandServiceTest {

    private companion object: KLogging() {
/**
 * EVENT_ID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val EVENT_ID: UUID = UUID.fromString("a93d0384-cdf2-446a-991d-7e014c8b8e7e")
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
    private val journal = InMemoryMeterCommandJournal()
/**
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = MeterCommandService(journal, Clock.fixed(now, ZoneOffset.UTC)) { EVENT_ID }

    @BeforeEach
    fun beforeEach() {
        journal.reset()
    }

    @Test
/**
 * 이 시나리오는 "price activation appends a version and PriceActivated outbox record together" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `price activation appends a version and PriceActivated outbox record together`() {
        val result = service.activatePrice(command())

        result.replayed.shouldBeFalse()
        journal.priceVersions.single().meterCode shouldBeEqualTo "api_calls"
        journal.outboxRecords.single().eventType shouldBeEqualTo "PriceActivated"
        journal.outboxRecords.single().partitionKey shouldBeEqualTo "tenant-a|Meter|api_calls"

        val outbox = journal.outboxRecords.single()
        val wire = Jackson.defaultJsonMapper.readTree(outbox.payload)
        wire.get("eventId")!!.asString() shouldBeEqualTo EVENT_ID.toString()
        wire.get("tenantId")!!.asString() shouldBeEqualTo "tenant-a"

        outbox.payloadDigest shouldBeEqualTo digestOf(outbox.payload)
    }

    @Test
/**
 * 이 시나리오는 "same idempotency key and fingerprint replays the original response without a second
 * event" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same idempotency key and fingerprint replays the original response without a second event`() {
        service.activatePrice(command())
        val replay = service.activatePrice(command())

        replay.replayed.shouldBeTrue()
        journal.priceVersions shouldHaveSize 1
        journal.outboxRecords shouldHaveSize 1
    }

    @Test
/**
 * 이 시나리오는 "same idempotency key with a different fingerprint is rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same idempotency key with a different fingerprint is rejected`() {
        service.activatePrice(command())

        val failure = assertFailsWith<MeterIdempotencyConflict> {
            service.activatePrice(command(unitPrice = BigDecimal("0.20")))
        }

        failure::class.simpleName shouldBeEqualTo "MeterIdempotencyConflict"
        journal.priceVersions shouldHaveSize 1
        journal.outboxRecords shouldHaveSize 1
    }

/**
 * command은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(unitPrice: BigDecimal = BigDecimal("0.10")): ActivatePriceCommand =
        ActivatePriceCommand(
            idempotencyKey = "activate-price-1",
            tenantId = "tenant-a",
            meterCode = "api_calls",
            currency = "USD",
            unitPrice = unitPrice,
            effectiveAt = now,
        )

/**
 * digestOf은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))

/**
 * InMemoryMeterCommandJournal 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class InMemoryMeterCommandJournal: MeterCommandJournal {
/**
 * priceVersions은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val priceVersions = mutableListOf<MeterPriceVersion>()
/**
 * outboxRecords은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val outboxRecords = mutableListOf<MeterOutboxRecord>()
/**
 * receipts은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val receipts = mutableMapOf<String, MeterCommandReceipt>()

/**
 * findReceipt은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun findReceipt(idempotencyKey: String): MeterCommandReceipt? = receipts[idempotencyKey]

/**
 * append은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun append(
            receipt: MeterCommandReceipt,
            priceVersion: MeterPriceVersion,
            outboxRecord: MeterOutboxRecord,
        ) {
            receipts[receipt.idempotencyKey] = receipt
            priceVersions += priceVersion
            outboxRecords += outboxRecord
        }

/**
 * reset은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun reset() {
            priceVersions.clear()
            outboxRecords.clear()
            receipts.clear()
        }
    }
}
