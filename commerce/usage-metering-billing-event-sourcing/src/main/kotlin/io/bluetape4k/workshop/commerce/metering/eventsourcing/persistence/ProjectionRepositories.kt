package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkEquals
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.StaleProjectionOwnerException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.*

@Repository
/**
 * ProjectionGenerationRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ProjectionGenerationRepository
    : EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class) {

    companion object: KLogging()

    /**
     * createInitialActive은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun createInitialActive(projectionName: String, generation: Int, now: Instant) {
        validateProjectionIdentity(projectionName, generation)

        ProjectionAliases.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[ProjectionAliases.projectionName] = projectionName
            it[activeGeneration] = generation
            it[updatedAt] = now
        }.insertedCount.checkEquals(1) { "active_projection_already_exists:$projectionName" }

        insertGeneration(
            projectionName = projectionName,
            generation = generation,
            state = ProjectionGenerationState.ACTIVE,
            highWatermark = 0,
            now = now
        )
    }

    /**
     * createBuilding은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param highWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun createBuilding(projectionName: String, generation: Int, highWatermark: Long, now: Instant) {
        validateProjectionIdentity(projectionName, generation)
        highWatermark.requireZeroOrPositiveNumber("highWatermark")

        insertGeneration(
            projectionName = projectionName,
            generation = generation,
            state = ProjectionGenerationState.BUILDING,
            highWatermark = highWatermark,
            now = now
        )
    }

    /**
     * createNextBuilding은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param highWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun createNextBuilding(projectionName: String, highWatermark: Long, now: Instant): ProjectionGeneration {
        highWatermark.requireZeroOrPositiveNumber("highWatermark")

        val alias = ProjectionAliases
            .selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .forUpdate()
            .singleOrNull()
            ?: error("active_projection_missing:$projectionName")

        val latest = ProjectionGenerations
            .selectAll()
            .where { ProjectionGenerations.projectionName eq projectionName }
            .orderBy(ProjectionGenerations.generation to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(1)
            .single()
            .toProjectionGeneration()

        check(latest.state != ProjectionGenerationState.BUILDING) { "rebuild_already_in_progress:$projectionName" }
        check(alias[ProjectionAliases.activeGeneration] > 0) { "active_projection_missing:$projectionName" }

        val nextGeneration = latest.generation + 1
        insertGeneration(
            projectionName = projectionName,
            generation = nextGeneration,
            state = ProjectionGenerationState.BUILDING,
            highWatermark = highWatermark,
            now = now
        )
        return get(projectionName, nextGeneration).checkNotNull("ProjectionGeneration($nextGeneration)")
    }

    /**
     * get은 사용량 계량·청구의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun get(projectionName: String, generation: Int): ProjectionGeneration? =
        projectionGenerationRow(projectionName, generation)?.toProjectionGeneration()

    /**
     * active은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun active(projectionName: String): ProjectionGeneration? {
        val generation = ProjectionAliases
            .selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .singleOrNull()
            ?.get(ProjectionAliases.activeGeneration)

        return generation?.let { get(projectionName, it) }
    }

    /**
     * switchActive은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedActiveGeneration 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun switchActive(lease: ProjectionLease, expectedActiveGeneration: Int, now: Instant): Boolean {
        val candidate = lockedProjectionGenerationRow(lease.projectionName, lease.generation)
            ?.toProjectionGeneration()
            ?: throw StaleProjectionOwnerException(lease.projectionName, lease.generation)

        if (candidate.ownerToken != lease.ownerToken) {
            throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        }
        val switchable = candidate.state == ProjectionGenerationState.BUILDING &&
                candidate.checkpoint >= candidate.highWatermark

        val alias = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq lease.projectionName }
            .forUpdate()
            .singleOrNull()

        val expectedAlias = alias?.get(ProjectionAliases.activeGeneration) == expectedActiveGeneration
        if (!switchable || !expectedAlias) return false

        transition(lease.projectionName, expectedActiveGeneration, ProjectionGenerationState.RETIRED, now)
        transition(lease.projectionName, lease.generation, ProjectionGenerationState.ACTIVE, now)
        updateAlias(lease.projectionName, lease.generation, now)

        return true
    }

    /**
     * rollbackActive은 사용량 계량·청구의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedCurrentGeneration 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param targetGeneration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun rollbackActive(
        projectionName: String,
        expectedCurrentGeneration: Int,
        targetGeneration: Int,
        now: Instant,
    ): Boolean {
        val alias = ProjectionAliases.selectAll()
            .where { ProjectionAliases.projectionName eq projectionName }
            .forUpdate()
            .singleOrNull()

        val currentMatches = alias?.get(ProjectionAliases.activeGeneration) == expectedCurrentGeneration
        val target = lockedProjectionGenerationRow(projectionName, targetGeneration)?.toProjectionGeneration()
        val canRollback = currentMatches && target?.state == ProjectionGenerationState.RETIRED
        if (!canRollback) return false

        transition(projectionName, expectedCurrentGeneration, ProjectionGenerationState.RETIRED, now)
        transition(projectionName, targetGeneration, ProjectionGenerationState.ACTIVE, now)
        updateAlias(projectionName, targetGeneration, now)

        return true
    }

    /**
     * insertGeneration은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param highWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun insertGeneration(
        projectionName: String,
        generation: Int,
        state: ProjectionGenerationState,
        highWatermark: Long,
        now: Instant,
    ) {
        ProjectionGenerations.insert {
            it[id] = Uuid.V7.nextId()
            it[ProjectionGenerations.projectionName] = projectionName
            it[ProjectionGenerations.generation] = generation
            it[ProjectionGenerations.state] = state.name
            it[checkpoint] = 0
            it[ProjectionGenerations.highWatermark] = highWatermark
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    /**
     * transition은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun transition(
        projectionName: String,
        generation: Int,
        state: ProjectionGenerationState,
        now: Instant,
    ) {
        ProjectionGenerations.update({ projectionGenerationIdentity(projectionName, generation) }) {
            it[ProjectionGenerations.state] = state.name
            it[updatedAt] = now
        }
    }

    /**
     * updateAlias은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun updateAlias(projectionName: String, generation: Int, now: Instant) {
        ProjectionAliases.update({ ProjectionAliases.projectionName eq projectionName }) {
            it[activeGeneration] = generation
            it[updatedAt] = now
        }
    }
}

@Repository
/**
 * ProjectionGenerationQueryRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ProjectionGenerationQueryRepository
    : EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class) {

    /**
     * building은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun building(projectionName: String): ProjectionGeneration? = ProjectionGenerations.selectAll()
        .where {
            (ProjectionGenerations.projectionName eq projectionName) and
                    (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING.name)
        }
        .orderBy(ProjectionGenerations.generation to org.jetbrains.exposed.v1.core.SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.toProjectionGeneration()
}

@Repository
/**
 * ProjectionCheckpointRepository은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ProjectionCheckpointRepository
    : EventSourcingExposedJdbcRepository<ProjectionGenerationEntity, UUID>(ProjectionGenerationEntity::class) {

    /**
     * acquireLease은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param ownerToken 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun acquireLease(
        projectionName: String,
        generation: Int,
        ownerToken: UUID,
        now: Instant,
        duration: Duration,
    ): ProjectionLease? {
        duration.requirePositiveDuration("duration")

        val current = lockedProjectionGenerationRow(projectionName, generation)?.toProjectionGeneration()
        val available = current != null &&
                current.state != ProjectionGenerationState.FAILED &&
                (current.ownerToken == ownerToken || current.leaseUntil == null || current.leaseUntil <= now)
        if (!available) return null

        val leaseUntil = now + duration

        ProjectionGenerations.update({ projectionGenerationIdentity(projectionName, generation) }) {
            it[ProjectionGenerations.ownerToken] = ownerToken
            it[ProjectionGenerations.leaseUntil] = leaseUntil
            it[updatedAt] = now
        }

        return ProjectionLease(
            projectionName = projectionName,
            generation = generation,
            ownerToken = ownerToken,
            leaseUntil = leaseUntil
        )
    }

    /**
     * renewLease은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun renewLease(lease: ProjectionLease, now: Instant, duration: Duration): ProjectionLease {
        duration.requirePositiveDuration("duration")

        val renewedUntil = now.plus(duration)
        fencedUpdate(lease, now) {
            it[ProjectionGenerations.leaseUntil] = renewedUntil
        }
        return lease.copy(leaseUntil = renewedUntil)
    }

    /**
     * releaseLease은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun releaseLease(lease: ProjectionLease, now: Instant) {
        fencedUpdate(lease, now) {
            it[ProjectionGenerations.ownerToken] = null
            it[ProjectionGenerations.leaseUntil] = null
        }
    }

    /**
     * requireOwnership은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun requireOwnership(lease: ProjectionLease): ProjectionGeneration {
        val generation = lockedProjectionGenerationRow(lease.projectionName, lease.generation)
            ?.toProjectionGeneration()
        if (generation?.ownerToken != lease.ownerToken || generation.state == ProjectionGenerationState.FAILED) {
            throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        }
        return generation
    }

    /**
     * insertAppliedEvent은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param eventId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param globalPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun insertAppliedEvent(lease: ProjectionLease, eventId: UUID, globalPosition: Long, now: Instant): Boolean {
        globalPosition.requirePositiveNumber("globalPosition")

        return ProjectionAppliedEvents.insertIgnore {
            it[id] = Uuid.V7.nextId()
            it[projectionName] = lease.projectionName
            it[generation] = lease.generation
            it[ProjectionAppliedEvents.eventId] = eventId
            it[ProjectionAppliedEvents.globalPosition] = globalPosition
            it[appliedAt] = now
        }.insertedCount == 1
    }

    /**
     * advanceCheckpoint은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param globalPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun advanceCheckpoint(lease: ProjectionLease, globalPosition: Long, now: Instant) {
        val current = requireOwnership(lease)

        if (globalPosition > current.checkpoint) {
            fencedUpdate(lease, now) {
                it[checkpoint] = globalPosition
            }
        }
    }

    /**
     * raiseHighWatermark은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param observedHighWatermark 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun raiseHighWatermark(lease: ProjectionLease, observedHighWatermark: Long, now: Instant) {
        val current = requireOwnership(lease)
        require(observedHighWatermark >= current.highWatermark) { "projection_high_watermark_regression" }

        fencedUpdate(lease, now) {
            it[highWatermark] = observedHighWatermark
        }
    }

    /**
     * markFailed은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failedPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param failureDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun markFailed(lease: ProjectionLease, failedPosition: Long, failureDigest: String, now: Instant) {
        requireOwnership(lease)
        require(failedPosition > 0 && failureDigest.isNotBlank()) { "projection_failure_invalid" }

        fencedUpdate(lease, now) {
            it[state] = ProjectionGenerationState.FAILED.name
            it[ProjectionGenerations.failedPosition] = failedPosition
            it[ProjectionGenerations.failureDigest] = failureDigest
        }
    }

    /**
     * fencedUpdate은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param body 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun fencedUpdate(
        lease: ProjectionLease,
        now: Instant,
        body: ProjectionGenerations.(UpdateStatement) -> Unit,
    ) {
        val updated = ProjectionGenerations.update({
            projectionGenerationIdentity(lease.projectionName, lease.generation) and
                    (ProjectionGenerations.ownerToken eq lease.ownerToken)
        }) {
            ProjectionGenerations.body(it)
            it[updatedAt] = now
        }

        if (updated != 1) {
            throw StaleProjectionOwnerException(lease.projectionName, lease.generation)
        }
    }
}

/**
 * projectionGenerationRow은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
 * 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun projectionGenerationRow(projectionName: String, generation: Int): ResultRow? =
    ProjectionGenerations.selectAll()
        .where { projectionGenerationIdentity(projectionName, generation) }
        .singleOrNull()

/**
 * lockedProjectionGenerationRow은 사용량 계량·청구의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자
 * 불일치를 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun lockedProjectionGenerationRow(projectionName: String, generation: Int): ResultRow? =
    ProjectionGenerations.selectAll()
        .where { projectionGenerationIdentity(projectionName, generation) }
        .forUpdate()
        .singleOrNull()

/**
 * projectionGenerationIdentity은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
 * 필드를
 * 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun projectionGenerationIdentity(projectionName: String, generation: Int) =
    (ProjectionGenerations.projectionName eq projectionName) and
            (ProjectionGenerations.generation eq generation)

/**
 * toProjectionGeneration은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
 * 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ResultRow.toProjectionGeneration(): ProjectionGeneration =
    ProjectionGeneration(
        projectionName = this[ProjectionGenerations.projectionName],
        generation = this[ProjectionGenerations.generation],
        state = ProjectionGenerationState.valueOf(this[ProjectionGenerations.state]),
        checkpoint = this[ProjectionGenerations.checkpoint],
        highWatermark = this[ProjectionGenerations.highWatermark],
        ownerToken = this[ProjectionGenerations.ownerToken],
        leaseUntil = this[ProjectionGenerations.leaseUntil],
        failedPosition = this[ProjectionGenerations.failedPosition],
        failureDigest = this[ProjectionGenerations.failureDigest],
    )

/**
 * validateProjectionIdentity은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
 * 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun validateProjectionIdentity(projectionName: String, generation: Int) {
    require(projectionName.isNotBlank() && generation > 0) { "projection_identity_invalid" }
}
