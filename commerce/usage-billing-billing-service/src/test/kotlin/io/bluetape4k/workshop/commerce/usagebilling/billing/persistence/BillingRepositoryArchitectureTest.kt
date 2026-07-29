package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test

class BillingRepositoryArchitectureTest {
    @Test
    fun `billing durable repositories implement ExposedJdbcRepository`() {
        listOf(
            "BillingOutboxRepository",
            "BillingChargeRepository",
            "BillingPricingEvidenceRepository",
            "BillingPriceEvidenceInboxRepository",
            "BillingInboxRepository",
        ).all { repository ->
            ExposedJdbcRepository::class.java.isAssignableFrom(
                Class.forName("io.bluetape4k.workshop.commerce.usagebilling.billing.persistence.$repository"),
            )
        } shouldBeEqualTo true
    }
}
