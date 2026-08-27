package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

internal class VoucherEventStreamCapacityIntegrationTest : AbstractVoucherIntegrationTest() {
    @Autowired
    private lateinit var streams: VoucherEventStream

    @Autowired
    private lateinit var jdbc: VoucherJdbcExecutor

    @Autowired
    private lateinit var campaigns: CampaignRepository

    @Test
    fun `thirty third campaign returns bounded fallback contract and retries after capacity returns`() {
        val tenant = randomIdentifier()
        val principal = randomIdentifier()
        val campaignIds = seedActiveCampaigns(tenant, 33)
        val subscriptions = campaignIds.take(32).map { streams.open(tenant, it, null) }.toMutableList()
        val rejectedCampaign = campaignIds.last()

        try {
            webTestClient.get().uri("/api/v1/campaigns/$rejectedCampaign/events")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals(
                    "Link",
                    "</api/v1/campaigns/$rejectedCampaign>; rel=\"alternate\"; type=\"application/json\"",
                )
                .expectBody().jsonPath("$.code").isEqualTo("SSE_CAPACITY_REJECTED")

            webTestClient.get().uri("/api/v1/campaigns/$rejectedCampaign")
                .header(TENANT_HEADER, tenant)
                .header(PRINCIPAL_HEADER, principal)
                .exchange().expectStatus().isOk
                .expectBody().jsonPath("$.campaignId").isEqualTo(rejectedCampaign.toString())

            subscriptions.removeFirst().close()
            subscriptions += streams.open(tenant, rejectedCampaign, null)
            streams.activePollers() shouldBeEqualTo 32
        } finally {
            subscriptions.forEach(AutoCloseable::close)
        }
        streams.activePollers() shouldBeEqualTo 0
    }

    private fun seedActiveCampaigns(
        tenant: String,
        count: Int,
    ) = jdbc.foregroundTransaction {
        val now = Instant.now()
        (1..count).map {
            campaigns.create(
                CampaignRecord(
                    id = 0,
                    tenantId = tenant,
                    campaignId = Uuid.V7.nextId(),
                    state = CampaignState.ACTIVE,
                    startsAt = now.minusSeconds(60),
                    endsAt = now.plusSeconds(3600),
                    capacity = 10,
                    allocatedCount = 0,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                    policyVersion = 1,
                    revision = 1,
                ),
            ).campaignId
        }
    }
}
