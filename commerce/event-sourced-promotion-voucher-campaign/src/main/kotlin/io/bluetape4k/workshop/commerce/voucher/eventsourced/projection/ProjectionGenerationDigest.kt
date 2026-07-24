package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Computes a generation-independent digest from semantic read-model fields.
 *
 * Fencing tokens, generation numbers, and timestamps are intentionally excluded so an active
 * generation and a rebuilt candidate can be compared without weakening either write fence.
 */
internal class ProjectionGenerationDigest {
    fun compute(key: ProjectionKey): ReceiptDigest {
        TransactionManager.current()
        val streamRows =
            ProjectionReadModels
                .selectAll()
                .where {
                    (ProjectionReadModels.projection eq key.projection) and
                        (ProjectionReadModels.generation eq key.generation)
                }.orderBy(
                    ProjectionReadModels.tenantId to SortOrder.ASC,
                    ProjectionReadModels.streamType to SortOrder.ASC,
                    ProjectionReadModels.streamId to SortOrder.ASC,
                ).map { row ->
                    listOf(
                        "stream",
                        row[ProjectionReadModels.tenantId],
                        row[ProjectionReadModels.streamType],
                        row[ProjectionReadModels.streamId],
                        row[ProjectionReadModels.streamVersion],
                        row[ProjectionReadModels.globalPosition],
                        row[ProjectionReadModels.eventType],
                        row[ProjectionReadModels.payloadDigest],
                    ).joinToString(DIGEST_FIELD_SEPARATOR)
                }
        val campaignRows =
            CampaignProjectionReadModels
                .selectAll()
                .where {
                    (CampaignProjectionReadModels.projection eq key.projection) and
                        (CampaignProjectionReadModels.generation eq key.generation)
                }.orderBy(
                    CampaignProjectionReadModels.tenantId to SortOrder.ASC,
                    CampaignProjectionReadModels.campaignId to SortOrder.ASC,
                ).map { row ->
                    listOf(
                        "campaign",
                        row[CampaignProjectionReadModels.tenantId],
                        row[CampaignProjectionReadModels.campaignId],
                        row[CampaignProjectionReadModels.state],
                        row[CampaignProjectionReadModels.streamVersion],
                        row[CampaignProjectionReadModels.globalPosition],
                        row[CampaignProjectionReadModels.policyVersion],
                        row[CampaignProjectionReadModels.capacity],
                        row[CampaignProjectionReadModels.allocatedCount],
                        row[CampaignProjectionReadModels.perUserLimit],
                        row[CampaignProjectionReadModels.redemptionTtlSeconds],
                        row[CampaignProjectionReadModels.startsAt],
                        row[CampaignProjectionReadModels.endsAt],
                    ).joinToString(DIGEST_FIELD_SEPARATOR)
                }
        return ReceiptDigest.sha256(
            (listOf(DIGEST_VERSION) + streamRows + campaignRows).joinToString(DIGEST_ROW_SEPARATOR),
        )
    }

    private companion object {
        const val DIGEST_VERSION = "voucher-projection-digest-v1"
        const val DIGEST_FIELD_SEPARATOR = "\u001f"
        const val DIGEST_ROW_SEPARATOR = "\u001e"
    }
}
