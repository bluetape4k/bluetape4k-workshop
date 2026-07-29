package io.bluetape4k.workshop.commerce.usagebilling.meter.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterIdempotencyConflict
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

class MeterCommandServiceTest {
    private val now = Instant.parse("2026-07-22T00:00:00Z")
    private val journal = InMemoryMeterCommandJournal()
    private val service = MeterCommandService(journal, Clock.fixed(now, ZoneOffset.UTC)) { EVENT_ID }

    @Test
    fun `price activation appends a version and PriceActivated outbox record together`() {
        val result = service.activatePrice(command())

        result.replayed shouldBeEqualTo false
        journal.priceVersions.single().meterCode shouldBeEqualTo "api_calls"
        journal.outboxRecords.single().eventType shouldBeEqualTo "PriceActivated"
        journal.outboxRecords.single().partitionKey shouldBeEqualTo "tenant-a|Meter|api_calls"
        val outbox = journal.outboxRecords.single()
        val wire = Jackson.defaultJsonMapper.readTree(outbox.payload)
        requireNotNull(wire.get("eventId")).asString() shouldBeEqualTo EVENT_ID.toString()
        requireNotNull(wire.get("tenantId")).asString() shouldBeEqualTo "tenant-a"
        outbox.payloadDigest shouldBeEqualTo digestOf(outbox.payload)
    }

    @Test
    fun `same idempotency key and fingerprint replays the original response without a second event`() {
        service.activatePrice(command())
        val replay = service.activatePrice(command())

        replay.replayed shouldBeEqualTo true
        journal.priceVersions.size shouldBeEqualTo 1
        journal.outboxRecords.size shouldBeEqualTo 1
    }

    @Test
    fun `same idempotency key with a different fingerprint is rejected`() {
        service.activatePrice(command())

        val failure = assertFailsWith<MeterIdempotencyConflict> {
            service.activatePrice(command(unitPrice = BigDecimal("0.20")))
        }

        failure::class.simpleName shouldBeEqualTo "MeterIdempotencyConflict"
        journal.priceVersions.size shouldBeEqualTo 1
        journal.outboxRecords.size shouldBeEqualTo 1
    }

    private fun command(unitPrice: BigDecimal = BigDecimal("0.10")): ActivatePriceCommand =
        ActivatePriceCommand(
            idempotencyKey = "activate-price-1",
            tenantId = "tenant-a",
            meterCode = "api_calls",
            currency = "USD",
            unitPrice = unitPrice,
            effectiveAt = now,
        )

    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

    private class InMemoryMeterCommandJournal : MeterCommandJournal {
        val priceVersions = mutableListOf<MeterPriceVersion>()
        val outboxRecords = mutableListOf<MeterOutboxRecord>()
        private val receipts = mutableMapOf<String, MeterCommandReceipt>()

        override fun findReceipt(idempotencyKey: String): MeterCommandReceipt? = receipts[idempotencyKey]

        override fun append(
            receipt: MeterCommandReceipt,
            priceVersion: MeterPriceVersion,
            outboxRecord: MeterOutboxRecord,
        ) {
            receipts[receipt.idempotencyKey] = receipt
            priceVersions += priceVersion
            outboxRecords += outboxRecord
        }
    }

    private companion object {
        val EVENT_ID: UUID = UUID.fromString("a93d0384-cdf2-446a-991d-7e014c8b8e7e")
    }

}
