package io.bluetape4k.workshop.commerce.usagebilling.billing.config

import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingCharges
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingInboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingOutboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingPriceEvidenceInboxEvents
import io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.BillingPricingEvidence
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class BillingSchemaInitializer : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        SchemaUtils.create(
            BillingOutboxEvents,
            BillingPricingEvidence,
            BillingPriceEvidenceInboxEvents,
            BillingInboxEvents,
            BillingCharges,
        )
    }
}
