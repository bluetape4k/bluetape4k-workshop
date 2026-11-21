package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant

/**
 * `PROJECTION_LEASE_TTL_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val PROJECTION_LEASE_TTL_SECONDS = 15L
/**
 * `PROJECTION_LEASE_RENEW_SECONDS`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
internal const val PROJECTION_LEASE_RENEW_SECONDS = 5L
/**
 * `DEFAULT_PROJECTION_LEASE`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private val DEFAULT_PROJECTION_LEASE: Duration = PROJECTION_LEASE_TTL_SECONDS.seconds()

/**
 * projection generation 하나의 fenced ownership입니다. 더 새로운 owner는 항상 더 큰 token을 보유해야 합니다.
 * @property ownerDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property fencingToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property leaseDeadline 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */
internal data class ProjectionLease(
    /**
     * `ownerDigest`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val ownerDigest: String,
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
    /**
     * `leaseDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val leaseDeadline: Instant,
) {
    init {
        ownerDigest.requireNotBlank("ownerDigest")
        fencingToken.requirePositiveNumber("fencingToken")
    }
}

/**
 * PostgreSQL lease authority입니다. caller는 반환된 fencing token으로 projection write를 수행해야 합니다.
 */
internal class ProjectionLeaseRepository {

    companion object: KLogging()

    /**
     * acquire은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun acquire(
        projection: String,
        generation: Long,
        ownerDigest: String,
        now: Instant,
        lease: Duration = DEFAULT_PROJECTION_LEASE,
    ): ProjectionLease? {
        TransactionManager.current()
        validateLeaseRequest(projection, generation, ownerDigest, lease)
        /**
         * `leaseDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val leaseDeadline = now.plus(lease)

        /**
         * `inserted`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val inserted = ProjectionLeases.insertIgnore { row ->
            row[ProjectionLeases.projection] = projection
            row[ProjectionLeases.generation] = generation
            row[ProjectionLeases.ownerDigest] = ownerDigest
            row[ProjectionLeases.leaseDeadline] = leaseDeadline
            row[ProjectionLeases.fencingToken] = FIRST_FENCING_TOKEN
            row[ProjectionLeases.updatedAt] = now
        }.insertedCount == 1

        if (inserted) {
            log.debug { "voucher_projection_lease_acquired projection=$projection generation=$generation" }
            return ProjectionLease(ownerDigest, FIRST_FENCING_TOKEN, leaseDeadline)
        }
        return acquireExisting(projection, generation, ownerDigest, now, leaseDeadline)
    }

    /**
     * renew은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun renew(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
        duration: Duration = DEFAULT_PROJECTION_LEASE,
    ): Boolean = renewLease(projection, generation, lease, now, duration) != null

    /**
     * renewLease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param duration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun renewLease(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
        duration: Duration = DEFAULT_PROJECTION_LEASE,
    ): ProjectionLease? {
        TransactionManager.current()
        validateLeaseRequest(projection, generation, lease.ownerDigest, duration)
        /**
         * `nextDeadline`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val nextDeadline = now.plus(duration)
        /**
         * `renewed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val renewed = ProjectionLeases.update(
            where = {
                leasePredicate(projection, generation, lease) and
                        (ProjectionLeases.leaseDeadline greaterEq now)
            },
        ) { row ->
            row[ProjectionLeases.leaseDeadline] = nextDeadline
            row[ProjectionLeases.updatedAt] = now
        } == 1

        log.debug { "voucher_projection_lease_renewed projection=$projection generation=$generation renewed=$renewed" }
        return if (renewed) lease.copy(leaseDeadline = nextDeadline) else null
    }

    /**
     * release은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun release(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        val released = ProjectionLeases.update(where = { leasePredicate(projection, generation, lease) }) { row ->
            row[ProjectionLeases.leaseDeadline] = now
            row[ProjectionLeases.updatedAt] = now
        } == 1

        log.debug { "voucher_projection_lease_released projection=$projection generation=$generation released=$released" }
        return released
    }

    /** caller의 transaction이 commit될 때까지 lease row lock을 보유합니다. */
    /** caller의 transaction이 commit될 때까지 lease row lock을 보유합니다. */
    fun requireActive(
        projection: String,
        generation: Long,
        lease: ProjectionLease,
        now: Instant,
    ) {
        TransactionManager.current()
        val active = ProjectionLeases
            .selectAll()
            .where {
                leasePredicate(projection, generation, lease) and
                        (ProjectionLeases.leaseDeadline greaterEq now)
            }.forUpdate()
            .singleOrNull() != null

        if (!active) {
            throw ProjectionLeaseLostException(projection, generation)
        }
    }

    /**
     * acquireExisting은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param leaseDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun acquireExisting(
        projection: String,
        generation: Long,
        ownerDigest: String,
        now: Instant,
        leaseDeadline: Instant,
    ): ProjectionLease? =
        find(projection, generation)
            ?.takeUnless { current -> current.leaseDeadline.isAfter(now) }
            ?.let { current ->
                /**
                 * `nextToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
                 *
                 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
                 */
                val nextToken = current.fencingToken + 1
                /**
                 * `acquired`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val acquired = ProjectionLeases.update({ leasePredicate(projection, generation, current) }) { row ->
                    row[ProjectionLeases.ownerDigest] = ownerDigest
                    row[ProjectionLeases.leaseDeadline] = leaseDeadline
                    row[ProjectionLeases.fencingToken] = nextToken
                    row[ProjectionLeases.updatedAt] = now
                } == 1

                if (!acquired) {
                    log.warn { "voucher_projection_lease_takeover_lost projection=$projection generation=$generation" }
                    null
                } else {
                    log.warn { "voucher_projection_lease_taken_over projection=$projection generation=$generation" }
                    ProjectionLease(ownerDigest, nextToken, leaseDeadline)
                }
            }
}

/**
 * ProjectionLeaseLostException은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ProjectionLeaseLostException(
    projection: String,
    generation: Long,
): IllegalStateException("projection lease is no longer active: $projection/$generation")

/**
 * `FIRST_FENCING_TOKEN`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val FIRST_FENCING_TOKEN = 1L

/**
 * find은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun find(
    projection: String,
    generation: Long,
): ProjectionLease? =
    ProjectionLeases
        .selectAll()
        .where { (ProjectionLeases.projection eq projection) and (ProjectionLeases.generation eq generation) }
        .singleOrNull()
        ?.let(::toProjectionLease)

/**
 * toProjectionLease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun toProjectionLease(row: ResultRow): ProjectionLease =
    ProjectionLease(
        ownerDigest = row[ProjectionLeases.ownerDigest],
        fencingToken = row[ProjectionLeases.fencingToken],
        leaseDeadline = row[ProjectionLeases.leaseDeadline],
    )

/**
 * leasePredicate은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun leasePredicate(
    projection: String,
    generation: Long,
    lease: ProjectionLease,
) =
    (ProjectionLeases.projection eq projection) and
            (ProjectionLeases.generation eq generation) and
            (ProjectionLeases.ownerDigest eq lease.ownerDigest) and
            (ProjectionLeases.fencingToken eq lease.fencingToken) and
            (ProjectionLeases.leaseDeadline eq lease.leaseDeadline)

/**
 * validateLeaseRequest은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
 * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun validateLeaseRequest(
    projection: String,
    generation: Long,
    ownerDigest: String,
    lease: Duration,
) {
    projection.requireNotBlank("projection")
    generation.requirePositiveNumber("generation")
    ownerDigest.requireNotBlank("ownerDigest")
    lease.requirePositiveDuration("lease")
}

/**
 * isRenewalDue은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
internal fun ProjectionLease.isRenewalDue(now: Instant): Boolean {
    val renewalDueAt = leaseDeadline.minusSeconds(PROJECTION_LEASE_TTL_SECONDS - PROJECTION_LEASE_RENEW_SECONDS)
    return !now.isBefore(renewalDueAt)
}
