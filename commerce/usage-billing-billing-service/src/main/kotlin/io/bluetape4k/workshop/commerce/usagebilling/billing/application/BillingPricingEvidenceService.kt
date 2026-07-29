package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingPriceEvidenceInboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingPricingEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BillingPricingEvidenceService(
    private val pricingEvidence: BillingPricingEvidenceRepository,
    private val inbox: BillingPriceEvidenceInboxRepository,
) {
    @Transactional
    fun record(meterCode: String) {
        pricingEvidence.append(meterCode)
    }

    @Transactional
    fun record(evidence: BillingPriceEvidence) {
        pricingEvidence.append(evidence)
    }

    @Transactional
    fun record(event: BillingPriceEvidenceEvent): BillingPriceEvidenceOutcome = inbox.record(event)
}
