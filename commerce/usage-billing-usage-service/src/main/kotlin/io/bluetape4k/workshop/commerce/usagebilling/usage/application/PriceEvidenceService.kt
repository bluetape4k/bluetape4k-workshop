package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidenceInboxRepository
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PriceEvidenceService(
    private val priceEvidence: UsagePriceEvidenceRepository,
    private val inbox: UsagePriceEvidenceInboxRepository,
) {
    @Transactional
    fun record(evidence: PriceEvidence) {
        priceEvidence.append(evidence)
    }

    @Transactional
    fun record(event: PriceEvidenceInboxEvent): PriceEvidenceInboxOutcome = inbox.record(event)
}
