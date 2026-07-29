package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BillingInboxService(
    private val journal: BillingInboxJournal,
) {
    @Transactional
    fun handle(event: BillingInboxEvent): BillingInboxOutcome =
        journal.digestFor(event.eventId)?.let { existingDigest ->
            if (existingDigest == event.payloadDigest) {
                BillingInboxOutcome.DUPLICATE
            } else {
                BillingInboxOutcome.QUARANTINED
            }
        } ?: handleUnseen(event)

    private fun handleUnseen(event: BillingInboxEvent): BillingInboxOutcome {
        if (journal.priceEvidence(event.tenantId, event.meterCode, event.currency) == null) {
            return BillingInboxOutcome.DEFERRED
        }
        return when (event.aggregateVersion) {
            journal.expectedVersion(event.tenantId, event.aggregateId) -> {
                journal.apply(event)
                BillingInboxOutcome.APPLIED
            }
            in Long.MIN_VALUE until journal.expectedVersion(event.tenantId, event.aggregateId) ->
                BillingInboxOutcome.DUPLICATE
            else -> BillingInboxOutcome.DEFERRED
        }
    }
}
