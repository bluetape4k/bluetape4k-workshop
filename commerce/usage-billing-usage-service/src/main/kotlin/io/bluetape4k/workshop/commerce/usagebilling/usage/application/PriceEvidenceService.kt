package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.persistence.UsagePriceEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PriceEvidenceService(
    private val priceEvidence: UsagePriceEvidenceRepository,
) {
    @Transactional
    fun record(evidence: PriceEvidence) {
        priceEvidence.append(evidence)
    }
}
