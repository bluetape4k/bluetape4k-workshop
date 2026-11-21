package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

@Repository
/**
 * ProviderEventInboxRepository은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ProviderEventInboxRepository:
    LongAuditableJdbcRepository<ProviderEventRecord, ProviderEventInboxTable> {

    companion object: KLogging()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ProviderEventInboxTable

    /**
     * extractId은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ProviderEventRecord) = entity.id

    /**
     * toEntity은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ProviderEventRecord = ProviderEventRecord(
        id = this[table.id].value,
        provider = this[table.provider],
        providerEventId = this[table.providerEventId],
        paymentAttemptId = this[table.paymentAttemptId],
        payloadFingerprint = this[table.payloadFingerprint],
        eventKind = this[table.eventKind],
        disposition = this[table.disposition],
        providerOccurredAt = this[table.providerOccurredAt],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt]
    )

    /**
     * record은 주문 라이프사이클과 fulfillment 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param record 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun record(record: ProviderEventRecord): ProviderEventDisposition {
        val inserted = table.insertIgnore {
            it[provider] = record.provider.take(40)
            it[providerEventId] = record.providerEventId.take(160)
            it[paymentAttemptId] = record.paymentAttemptId
            it[payloadFingerprint] = record.payloadFingerprint
            it[eventKind] = record.eventKind
            it[disposition] = record.disposition
            it[providerOccurredAt] = record.providerOccurredAt
        }.insertedCount == 1
        if (inserted) return record.disposition

        val existing = find(record.provider, record.providerEventId)
            ?: error("provider event disappeared after unique conflict")

        return if (existing.payloadFingerprint == record.payloadFingerprint) {
            ProviderEventDisposition.DUPLICATE
        } else {
            updateDisposition(record.provider, record.providerEventId, ProviderEventDisposition.CONFLICT)
            log.warn {
                "provider_event_payload_conflict provider=${record.provider} " +
                        "providerEventId=${record.providerEventId} paymentAttemptId=${record.paymentAttemptId}"
            }
            ProviderEventDisposition.CONFLICT
        }
    }

    /**
     * find은 주문 라이프사이클과 fulfillment의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param provider 업무 처리에 필요한 provider 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param providerEventId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun find(
        provider: String,
        providerEventId: String,
    ): ProviderEventRecord? =
        table
            .selectAll()
            .where { (table.provider eq provider) and (table.providerEventId eq providerEventId) }
            .firstOrNull()
            ?.toEntity()

    /**
     * countUnresolved은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency
     * key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun countUnresolved(): Long =
        table
            .selectAll()
            .where {
                table.disposition inList listOf(ProviderEventDisposition.CONFLICT, ProviderEventDisposition.UNRESOLVED)
            }
            .count()

    /**
     * updateDisposition은 주문 라이프사이클과 fulfillment 저장소를 transaction 경계 안에서 변경하며 idempotency
     * key·revision·감사
     * 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param provider 업무 처리에 필요한 provider 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param providerEventId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param disposition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun updateDisposition(
        provider: String,
        providerEventId: String,
        disposition: ProviderEventDisposition,
    ): Boolean =
        table.update(
            { (table.provider eq provider) and (table.providerEventId eq providerEventId) }
        ) {
            it[ProviderEventInboxTable.disposition] = disposition
        } == 1
}
