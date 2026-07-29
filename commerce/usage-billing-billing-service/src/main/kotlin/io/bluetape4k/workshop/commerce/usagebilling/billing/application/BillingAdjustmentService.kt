package io.bluetape4k.workshop.commerce.usagebilling.billing.application

import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentCommand
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentJournal
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingAdjustmentOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BillingAdjustmentService(
    private val journal: BillingAdjustmentJournal,
) {
    @Transactional
    fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome = journal.post(command)
}
