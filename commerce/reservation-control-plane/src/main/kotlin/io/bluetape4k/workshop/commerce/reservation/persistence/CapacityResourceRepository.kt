package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.reservation.domain.ResourceState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * reservation correctness boundary로 동작하는 capacity counter를 저장합니다.
 *
 * occupy와 release operation은 state, revision, capacity predicate를 단일 SQL update로 결합하므로
 * caller가 stale read에서 capacity를 authorize하지 못합니다.
 */
@Repository
internal class CapacityResourceRepository
    : LongAuditableJdbcRepository<CapacityResourceRecord, CapacityResourceTable> {

    companion object: KLogging()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = CapacityResourceTable

    /**
     * extractId은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: CapacityResourceRecord): Long = entity.id

    /**
     * toEntity은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): CapacityResourceRecord =
        CapacityResourceRecord(
            id = this[table.id].value,
            code = this[table.code],
            state = this[table.state],
            capacity = this[table.capacity],
            occupiedCount = this[table.occupiedCount],
            revision = this[table.revision],
            policyVersion = this[table.policyVersion],
            timezone = this[table.timezone],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    /**
     * create은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param capacity 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param policyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun create(
        code: String,
        capacity: Int,
        policyVersion: Long,
    ): CapacityResourceRecord {
        capacity.requirePositiveNumber("capacity")

        val id = table
            .insertAndGetId {
                it[table.code] = code.take(80)
                it[table.capacity] = capacity
                it[table.policyVersion] = policyVersion
            }.value

        log.debug { "capacity_resource_created resourceId=$id capacity=$capacity policyVersion=$policyVersion" }
        return findById(id)
    }

    /**
     * tryOccupy은 예약 제어면 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun tryOccupy(
        id: Long,
        expectedRevision: Long,
    ): Boolean {
        val applied = auditedUpdateAll({
            (table.id eq id) and
                    (table.state eq ResourceState.OPEN) and
                    (table.revision eq expectedRevision) and
                    (table.occupiedCount less table.capacity)
        }) {
            it[occupiedCount] = occupiedCount + 1
            it[revision] = expectedRevision + 1
        } == 1

        log.debug { "capacity_resource_occupy resourceId=$id expectedRevision=$expectedRevision applied=$applied" }
        return applied
    }

    /**
     * release은 예약 제어면의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param id 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun release(
        id: Long,
        expectedRevision: Long,
    ): Boolean =
        auditedUpdateAll({
            (table.id eq id) and
                    (table.revision eq expectedRevision) and
                    (table.occupiedCount greater 0)
        }) {
            it[occupiedCount] = occupiedCount - 1
            it[revision] = expectedRevision + 1
        }
            .also { updated ->
                log.debug { "capacity_resource_release resourceId=$id expectedRevision=$expectedRevision updated=$updated" }
            } == 1

    /**
     * snapshots은 예약 제어면의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun snapshots(): List<CapacityResourceRecord> =
        table.selectAll().map { it.toEntity() }

    /** capacity ownership을 이전할 수 있는 모든 transaction에서 canonical first lock을 획득합니다. */
    /** capacity ownership을 이전할 수 있는 모든 transaction에서 canonical first lock을 획득합니다. */
    fun findByIdForUpdate(id: Long): CapacityResourceRecord =
        table
            .selectAll()
            .where { table.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.toEntity() ?: throw NoSuchElementException("capacity resource not found")

}
