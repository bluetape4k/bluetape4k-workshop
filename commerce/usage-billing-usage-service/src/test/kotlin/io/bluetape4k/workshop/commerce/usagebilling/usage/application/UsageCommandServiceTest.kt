package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UsageCommandServiceTest {
    private val now = Instant.parse("2026-07-22T00:00:00Z")
    private val journal = InMemoryUsageAcceptanceJournal()
    private val service = UsageCommandService(journal, Clock.fixed(now, ZoneOffset.UTC)) { EVENT_ID }

    @Test
    fun `verified local price evidence accepts usage and appends UsageAccepted outbox record`() {
        journal.evidence += PriceEvidence("tenant-a", "api_calls", "USD", BigDecimal("0.10"), now)

        val result = service.accept(command())

        result.replayed shouldBeEqualTo false
        journal.usages.single().sourceEventId shouldBeEqualTo "source-usage-1"
        journal.outbox.single().eventType shouldBeEqualTo "UsageAccepted"
        journal.outbox.single().partitionKey shouldBeEqualTo "tenant-a|Usage|source-usage-1"
    }

    @Test
    fun `same source event replays without a second usage or outbox record`() {
        journal.evidence += PriceEvidence("tenant-a", "api_calls", "USD", BigDecimal("0.10"), now)
        service.accept(command())

        service.accept(command()).replayed shouldBeEqualTo true
        journal.usages.size shouldBeEqualTo 1
        journal.outbox.size shouldBeEqualTo 1
    }

    @Test
    fun `same source event with different payload is rejected`() {
        journal.evidence += PriceEvidence("tenant-a", "api_calls", "USD", BigDecimal("0.10"), now)
        service.accept(command())

        val failure = runCatching { service.accept(command(quantity = BigDecimal.TWO)) }.exceptionOrNull()

        failure!!::class.simpleName shouldBeEqualTo "UsageSourceConflict"
        journal.usages.size shouldBeEqualTo 1
    }

    @Test
    fun `missing local price evidence is rejected without an outbox record`() {
        val failure = runCatching { service.accept(command()) }.exceptionOrNull()

        failure!!::class.simpleName shouldBeEqualTo "MissingPriceEvidence"
        journal.outbox.size shouldBeEqualTo 0
    }

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

    private class InMemoryUsageAcceptanceJournal : UsageAcceptanceJournal {
        val evidence = mutableListOf<PriceEvidence>()
        val usages = mutableListOf<UsageRecord>()
        val outbox = mutableListOf<UsageOutboxRecord>()

        override fun priceEvidence(tenantId: String, meterCode: String, currency: String): PriceEvidence? =
            evidence.firstOrNull { it.tenantId == tenantId && it.meterCode == meterCode && it.currency == currency }

        override fun findUsage(tenantId: String, sourceSystem: String, sourceEventId: String): UsageRecord? =
            usages.firstOrNull {
                it.tenantId == tenantId && it.sourceSystem == sourceSystem && it.sourceEventId == sourceEventId
            }

        override fun append(usage: UsageRecord, outboxRecord: UsageOutboxRecord) {
            usages += usage
            outbox += outboxRecord
        }
    }

    private companion object {
        val EVENT_ID: UUID = UUID.fromString("487a67c1-6541-4c31-af7a-916fd35b2f83")
    }
}
