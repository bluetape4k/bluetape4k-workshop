package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.voucher.AbstractVoucherIntegrationTest
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherSseProperties
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.Instant

internal class VoucherEventStreamBatchBoundIntegrationTest : AbstractVoucherIntegrationTest() {
    @Autowired
    private lateinit var jdbc: VoucherJdbcExecutor

    @Autowired
    private lateinit var campaigns: CampaignRepository

    @Autowired
    private lateinit var audits: AuditRepository

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var permits: DatabasePermitGate

    @Test
    fun `poll batch stops at row and encoded payload boundaries then returns database permits`() {
        val tenant = randomIdentifier()
        val campaignId = Uuid.V7.nextId()
        seedCampaignWithAudits(tenant, campaignId, 10)

        val rowBoundSource = source(maxRows = 3, maxPayloadBytes = 256 * 1024)
        val rowBound = rowBoundSource.poll(tenant, campaignId, 0)
        check(rowBound.events.size == 3)

        val oneEncodedEventBytes =
            rowBound.events.first().let { (cursor, event) ->
                mapper.writeValueAsBytes(event).size + cursor.toString().length + SSE_FRAME_BYTES
            }
        val payloadLimit = oneEncodedEventBytes * 2 + oneEncodedEventBytes / 2
        val payloadBound = source(maxRows = 200, maxPayloadBytes = payloadLimit).poll(tenant, campaignId, 0)

        check(payloadBound.events.size == 2)
        check(payloadBound.events.sumOf { (cursor, event) ->
            mapper.writeValueAsBytes(event).size + cursor.toString().length + SSE_FRAME_BYTES
        } <= payloadLimit)
        check(permits.availablePermits(DatabaseLane.SSE_MAINTENANCE) == 3)
        check(permits.availablePermits(DatabaseLane.WORKER) == 1)
    }

    private fun source(
        maxRows: Int,
        maxPayloadBytes: Int,
    ): PostgresVoucherEventSource =
        PostgresVoucherEventSource(
            jdbc = jdbc,
            campaigns = campaigns,
            audits = audits,
            mapper = mapper,
            properties =
                VoucherProperties(
                    sse = VoucherSseProperties(maxRows = maxRows, maxPayloadBytes = maxPayloadBytes),
                ),
        )

    private fun seedCampaignWithAudits(
        tenant: String,
        campaignId: java.util.UUID,
        count: Int,
    ) {
        val now = Instant.now()
        jdbc.foregroundTransaction {
            campaigns.create(
                CampaignRecord(
                    id = 0,
                    tenantId = tenant,
                    campaignId = campaignId,
                    state = CampaignState.ACTIVE,
                    startsAt = now.minusSeconds(60),
                    endsAt = now.plusSeconds(3600),
                    capacity = 10,
                    allocatedCount = 0,
                    perUserLimit = 1,
                    redemptionTtlSeconds = 600,
                    policyVersion = 1,
                    revision = count.toLong(),
                ),
            )
            (1..count).forEach { revision ->
                audits.append(
                    AuditRecord(
                        id = 0,
                        tenantId = tenant,
                        campaignId = campaignId,
                        aggregateType = "CAMPAIGN",
                        aggregateId = campaignId,
                        revision = revision.toLong(),
                        actorType = "OPERATOR",
                        reasonCode = "R".repeat(64),
                        policyVersion = 1,
                        correlationDigest = null,
                    ),
                )
            }
        }
    }

    private companion object {
        private const val SSE_FRAME_BYTES = 32
    }
}
