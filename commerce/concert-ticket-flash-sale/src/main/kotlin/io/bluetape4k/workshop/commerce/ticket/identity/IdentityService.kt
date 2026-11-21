package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireBeTrue
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliasEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentityAliases
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketIdentitySubjectEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import java.io.Serializable
import java.nio.ByteBuffer
import java.text.Normalizer
import java.time.Instant
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * active HMAC read ring과 현재 write version 하나입니다.
 * @property currentVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property activeReadVersions 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */
class IdentityKeyRing(
    /**
     * `currentVersion`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentVersion: Int,
    /**
     * `activeReadVersions`은 콘서트 티켓 플래시 세일에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val activeReadVersions: Set<Int>,
    keys: Map<Int, ByteArray>,
) {
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val keys: Map<Int, ByteArray> = keys.mapValues { it.value.copyOf() }

    init {
        require(currentVersion in activeReadVersions) { "currentVersion[$currentVersion] in activeReadVersions[$activeReadVersions]" }
        this.activeReadVersions.requireNotEmpty("activeReadVersions")
        this.activeReadVersions
            .all { version -> this.keys[version]?.size?.let { it >= 32 } == true }
            .requireBeTrue { "activeReadVersions all keys must have 32+ characters." }
    }

    /**
     * digest은 콘서트 티켓 판매 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param canonical 업무 처리에 필요한 canonical 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun digest(
        version: Int,
        kind: IdentityKind,
        canonical: String,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(SecretKeySpec(keys.getValue(version), HMAC_SHA_256))
        mac.update(IDENTITY_DOMAIN.toUtf8Bytes())
        mac.update(0)
        mac.update(kind.name.toUtf8Bytes())
        mac.update(0)
        mac.update(canonical.toUtf8Bytes())

        return mac.doFinal()
    }

    companion object: KLogging() {
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val HMAC_SHA_256 = "HmacSHA256"
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val IDENTITY_DOMAIN = "ticket-identity-subject-v1"
    }
}

/**
 * raw identifier를 포함하지 않는 안정적인 internal subject입니다.
 * @property subjectId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property kind 현재 콘서트 티켓 판매 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 */
data class IdentitySubject(
    /**
     * `subjectId`은 콘서트 티켓 플래시 세일에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val subjectId: UUID,
    /**
     * `kind`은 콘서트 티켓 플래시 세일에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: IdentityKind,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** 모든 active digest 위의 advisory lock으로 직렬화되는 PostgreSQL alias authority입니다. */
/** 모든 active digest 위의 advisory lock으로 직렬화되는 PostgreSQL alias authority입니다. */
class IdentitySubjectRepository:
    TicketExposedJdbcRepository<TicketIdentitySubjectEntity, UUID>(TicketIdentitySubjectEntity::class)
/**
 * IdentityAliasRepository은 콘서트 티켓 판매에서 사용하는 업무 객체입니다.
 * @property jdbc 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property subjectRepo 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class IdentityAliasRepository(
    /**
     * `jdbc`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: TicketJdbcExecutor,
    /**
     * `subjectRepo`은 콘서트 티켓 플래시 세일에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val subjectRepo: IdentitySubjectRepository = IdentitySubjectRepository(),
): TicketExposedJdbcRepository<TicketIdentityAliasEntity, Long>(TicketIdentityAliasEntity::class) {

    /**
     * resolveOrCreate은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param candidates 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param currentVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun resolveOrCreate(
        kind: IdentityKind,
        candidates: Map<Int, ByteArray>,
        currentVersion: Int,
    ): IdentitySubject =
        jdbc.transaction {
            /**
             * `lockKeys`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val lockKeys = candidates.values.map(::advisoryKey).distinct().sorted()
            lockKeys.forEach { key ->
                transaction.exec("SELECT pg_advisory_xact_lock($key)") { result ->
                    check(result.next())
                }
            }

            /**
             * `subject`은 콘서트 티켓 플래시 세일에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
             *
             * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
             */
            val subject = candidates.entries
                .sortedByDescending { it.key }
                .firstNotNullOfOrNull { (version, digest) ->
                    findSubject(kind, version, digest)
                }
                ?: IdentitySubject(Uuid.V7.nextId(), kind).also { insertSubject(it) }

            ensureAlias(subject, currentVersion, candidates.getValue(currentVersion))
            subject
        }

    /**
     * aliasVersions은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subjectId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun aliasVersions(subjectId: UUID): Set<Int> =
        jdbc.transaction {
            findAll { TicketIdentityAliases.subjectId eq subjectId }
                .mapTo(linkedSetOf()) { it.keyVersion }
        }

    /**
     * findSubject은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param digest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun findSubject(
        kind: IdentityKind,
        version: Int,
        digest: ByteArray,
    ): IdentitySubject? {
        val whereOp: Op<Boolean> = (TicketIdentityAliases.identityKind eq kind.name) and
                (TicketIdentityAliases.keyVersion eq version) and
                (TicketIdentityAliases.digest eq digest)

        return findAll { whereOp }.singleOrNull()
            ?.let { IdentitySubject(it.subjectId, kind) }
    }

    /**
     * insertSubject은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun insertSubject(subject: IdentitySubject) {
        val entity = TicketIdentitySubjectEntity.new(subject.subjectId) {
            identityKind = subject.kind.name
            createdAt = Instant.now()
        }
        subjectRepo.save(entity)
    }

    /**
     * ensureAlias은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subject 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param digest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun ensureAlias(
        subject: IdentitySubject,
        version: Int,
        digest: ByteArray,
    ) {
        val whereOp = (TicketIdentityAliases.identityKind eq subject.kind.name) and
                (TicketIdentityAliases.keyVersion eq version) and
                (TicketIdentityAliases.digest eq digest)
        if (exists { whereOp }) {
            return
        }

        save(
            TicketIdentityAliasEntity.new {
                identityKind = subject.kind.name
                keyVersion = version
                this.digest = digest
                subjectId = subject.subjectId
                createdAt = Instant.now()
            },
        )
    }

    /**
     * advisoryKey은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param digest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun advisoryKey(digest: ByteArray): Long =
        ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
}

/**
 * 모든 active read key를 통해 raw request identity를 안정적인 database subject로 해석합니다.
 * @property keys 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property aliases 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class IdentityService(
    /**
     * `keys`은 콘서트 티켓 플래시 세일에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val keys: IdentityKeyRing,
    /**
     * `aliases`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val aliases: IdentityAliasRepository,
) {

    /**
     * resolve은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param canonical 업무 처리에 필요한 canonical 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun resolve(
        kind: IdentityKind,
        canonical: String,
    ): IdentitySubject {
        val normalized = Normalizer.normalize(canonical.trim(), Normalizer.Form.NFC)
        normalized.requireNotBlank("canonical")

        val candidates = keys.activeReadVersions.associateWith {
            keys.digest(it, kind, normalized)
        }
        return aliases.resolveOrCreate(kind, candidates, keys.currentVersion)
    }
}
