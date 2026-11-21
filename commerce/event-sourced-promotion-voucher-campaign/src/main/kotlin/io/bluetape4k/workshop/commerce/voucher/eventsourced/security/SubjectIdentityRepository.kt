@file:Suppress("MagicNumber") // SQL column sizes are schema declarations.

package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * SubjectIdentityMappings은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object SubjectIdentityMappings: TimebasedUUIDTable("voucher_subject_identity_mapping", "surrogate_id") {
    /**
     * 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     */
    val tenantId = varchar("tenant_id", 64)
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val identityDigest = varchar("identity_digest", 64)
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */
    val hmacKeyVersion = integer("hmac_key_version")
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(tenantId, identityDigest, hmacKeyVersion)
        index(false, tenantId, id)
    }
}

@ConsistentCopyVisibility
/**
 * SubjectIdentity은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class SubjectIdentity private constructor(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: TenantId,
    /**
     * `surrogate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val surrogate: UUID,
    /**
     * `identityDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val identityDigest: String,
    /**
     * `hmacKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val hmacKeyVersion: Int,
    /**
     * `createdAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
         * @param surrogate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param identityDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param hmacKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param createdAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         */
        operator fun invoke(
            tenantId: TenantId,
            surrogate: UUID,
            identityDigest: String,
            hmacKeyVersion: Int,
            createdAt: Instant,
        ): SubjectIdentity =
            SubjectIdentity(
                tenantId = tenantId,
                surrogate = surrogate,
                identityDigest = identityDigest.requireNotBlank("identityDigest"),
                hmacKeyVersion = hmacKeyVersion,
                createdAt = createdAt,
            )
    }
}

/**
 * reversible identity mapping을 위한 transaction-bound persistence boundary입니다.
 *
 * erasure와 key-ring lookup만 지원되는 mutation path이므로 generic CRUD를 의도적으로 노출하지 않습니다.
 * @property keyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property nextSurrogate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class SubjectIdentityRepository(
    /**
     * `keyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keyRing: EventSourcedHmacKeyRing,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
    /**
     * `nextSurrogate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val nextSurrogate: () -> UUID = Uuid.V7::nextUUID,
) {
    /**
     * resolve은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity {
        val validDomain = domain.requireNotBlank("identity.domain")
        val validIdentity = identity.requireNotBlank("identity")
        val candidates = keyRing.digestsForLookup(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = tenantId,
            domain = validDomain,
            value = validIdentity,
        )
        candidates.firstNotNullOfOrNull { findByDigest(tenantId, it) }?.let { return it }

        return insert(
            digest = keyRing.digest(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = tenantId,
                domain = validDomain,
                value = validIdentity,
            ),
            tenantId = tenantId,
        )
    }

    /**
     * insert은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param digest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun insert(
        digest: KeyedDigest,
        tenantId: TenantId,
    ): SubjectIdentity {
        val surrogate = nextSurrogate()
        val createdAt = clock.instant()
        SubjectIdentityMappings.insertIgnore { row ->
            row[SubjectIdentityMappings.id] = surrogate
            row[SubjectIdentityMappings.tenantId] = tenantId.value
            row[SubjectIdentityMappings.identityDigest] = digest.value
            row[SubjectIdentityMappings.hmacKeyVersion] = digest.keyVersion
            row[SubjectIdentityMappings.createdAt] = createdAt
        }
        return findByDigest(tenantId, digest).requireNotNull("subjectIdentity")
    }

    /**
     * findBySurrogate은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param surrogate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun findBySurrogate(
        tenantId: TenantId,
        surrogate: UUID,
    ): SubjectIdentity? =
        SubjectIdentityMappings
            .selectAll()
            .where {
                (SubjectIdentityMappings.tenantId eq tenantId.value) and
                        (SubjectIdentityMappings.id eq surrogate)
            }.singleOrNull()
            ?.let(::toSubjectIdentity)

    /**
     * erase은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int {
        val candidates = keyRing.digestsForLookup(
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = tenantId,
            domain = domain.requireNotBlank("identity.domain"),
            value = identity.requireNotBlank("identity"),
        )
        val erased = candidates.sumOf { digest ->
            SubjectIdentityMappings.deleteWhere {
                (SubjectIdentityMappings.tenantId eq tenantId.value) and
                        (SubjectIdentityMappings.identityDigest eq digest.value) and
                        (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
            }
        }
        if (erased > 0) {
            log.info { "Erased subject identity mappings. count=$erased" }
        }
        return erased
    }

    /**
     * countFor은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun countFor(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Long =
        keyRing
            .digestsForLookup(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = tenantId,
                domain = domain.requireNotBlank("identity.domain"),
                value = identity.requireNotBlank("identity"),
            ).sumOf { digest ->
                SubjectIdentityMappings
                    .selectAll()
                    .where {
                        (SubjectIdentityMappings.tenantId eq tenantId.value) and
                                (SubjectIdentityMappings.identityDigest eq digest.value) and
                                (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
                    }
                    .count()
            }

    /**
     * findByDigest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param digest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun findByDigest(
        tenantId: TenantId,
        digest: KeyedDigest,
    ): SubjectIdentity? =
        SubjectIdentityMappings
            .selectAll()
            .where {
                (SubjectIdentityMappings.tenantId eq tenantId.value) and
                        (SubjectIdentityMappings.identityDigest eq digest.value) and
                        (SubjectIdentityMappings.hmacKeyVersion eq digest.keyVersion)
            }
            .singleOrNull()
            ?.let(::toSubjectIdentity)

    /**
     * toSubjectIdentity은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param row 업무 처리에 필요한 row 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun toSubjectIdentity(row: ResultRow): SubjectIdentity =
        SubjectIdentity(
            tenantId = TenantId(row[SubjectIdentityMappings.tenantId]),
            surrogate = row[SubjectIdentityMappings.id].value,
            identityDigest = row[SubjectIdentityMappings.identityDigest],
            hmacKeyVersion = row[SubjectIdentityMappings.hmacKeyVersion],
            createdAt = row[SubjectIdentityMappings.createdAt],
        )

    private companion object: KLogging()
}

/**
 * SubjectIdentityService은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface SubjectIdentityService {
    /**
     * resolve은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity

    /**
     * erase은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int
}
/**
 * ExposedSubjectIdentityService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ExposedSubjectIdentityService(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: SubjectIdentityRepository,
): SubjectIdentityService {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transactions =
        EventSourcedPermitTransactionRunner(
            database = database,
            permits = permits,
            lane = EventSourcedDatabaseLane.FOREGROUND,
        )

    /**
     * resolve은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun resolve(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): SubjectIdentity = transactions.inTransaction {
        repository.resolve(tenantId, domain, identity)
    }

    /**
     * erase은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param domain 업무 처리에 필요한 domain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun erase(
        tenantId: TenantId,
        domain: String,
        identity: String,
    ): Int = transactions.inTransaction {
        repository.erase(tenantId, domain, identity)
    }
}
