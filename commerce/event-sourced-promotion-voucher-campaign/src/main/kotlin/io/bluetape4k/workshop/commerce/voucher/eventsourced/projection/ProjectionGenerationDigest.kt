package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * semantic read-model field에서 generation-independent digest를 계산합니다.
 *
 * active generation과 rebuilt candidate를 비교할 때 어느 write fence도 약화하지 않도록
 * fencing token, generation number, timestamp는 의도적으로 제외합니다.
 */
internal class ProjectionGenerationDigest {

    private companion object: KLogging() {
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */
        const val DIGEST_VERSION = "voucher-projection-digest-v1"
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        const val DIGEST_FIELD_SEPARATOR = "\u001f"
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        const val DIGEST_ROW_SEPARATOR = "\u001e"
    }

    /**
     * compute은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun compute(key: ProjectionKey): ReceiptDigest {
        TransactionManager.current()
        val streamRows = ProjectionReadModels
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

        val campaignRows = CampaignProjectionReadModels
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
}
