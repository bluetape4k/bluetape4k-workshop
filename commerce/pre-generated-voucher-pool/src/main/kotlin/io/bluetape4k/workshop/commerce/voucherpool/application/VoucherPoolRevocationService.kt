@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.io.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * `RevokeAggregateType`은 업무 대상 유형입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class RevokeAggregateType {
    /**
     * `RevokeAggregateType.CAMPAIGN`은 `campaign` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    CAMPAIGN,
    /**
     * `RevokeAggregateType.BATCH`은 `batch` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를 결정합니다.
     */
    BATCH
}

/**
 * `RevocationCommandFailure`은 실패·오류 분류 코드입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class RevocationCommandFailure {
    /**
     * `RevocationCommandFailure.SCOPE_NOT_FOUND`은 요청한 tenant·scope에 해당하는 자원이 존재하지 않는 값입니다.
     */
    SCOPE_NOT_FOUND,
    /**
     * `RevocationCommandFailure.STALE_REVISION`은 호출자가 가진 revision이 저장된 최신 revision보다 오래되어 반영할 수 없는
     * 값입니다.
     */
    STALE_REVISION,
    /**
     * `RevocationCommandFailure.INVALID_STATE`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_STATE,
    /**
     * `RevocationCommandFailure.INVALID_PREVIEW`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_PREVIEW,
    /**
     * `RevocationCommandFailure.COMMAND_IN_PROGRESS`은 동일 command가 이미 실행 중이어서 중복 실행을 막아야 하는 값입니다.
     */
    COMMAND_IN_PROGRESS,
    /**
     * `RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT`은 동일 key에 다른 요청 fingerprint가 들어와
     * side
     * effect를 거부하는 값입니다.
     */
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    /**
     * `RevocationCommandFailure.REPLAY_WINDOW_EXPIRED`은 멱등 결과를 재생할 보존 창이 지나 원 요청을 다시 처리할 수 없는 값입니다.
     */
    REPLAY_WINDOW_EXPIRED,
}
/**
 * RevocationCommandException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property reason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class RevocationCommandException(val reason: RevocationCommandFailure): IllegalStateException(reason.name)
/**
 * RevokePreviewSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property counts 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property eligibleDepth 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property activeReservations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property activeAllocations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property alreadyTerminalCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property affectedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property previewToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class RevokePreviewSnapshot(
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: RevokeAggregateType,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `counts`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val counts: Map<EntryState, Long>,
    /**
     * `eligibleDepth`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val eligibleDepth: Long,
    /**
     * `activeReservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val activeReservations: Long,
    /**
     * `activeAllocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val activeAllocations: Long,
    /**
     * `alreadyTerminalCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val alreadyTerminalCount: Long,
    /**
     * `affectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val affectedCount: Long,
    /**
     * `previewToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val previewToken: String,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
): Serializable {

    /**
     * toString은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String =
        "RevokePreviewSnapshot(aggregateType=$aggregateType,aggregateId=$aggregateId,revision=$revision," +
                "counts=$counts,eligibleDepth=$eligibleDepth,activeReservations=$activeReservations," +
                "activeAllocations=$activeAllocations,alreadyTerminalCount=$alreadyTerminalCount," +
                "affectedCount=$affectedCount,previewToken=[REDACTED],expiresAt=$expiresAt,observedAt=$observedAt)"

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * RevokeProgressSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property workerCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class RevokeProgressSnapshot(
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: RevokeAggregateType,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `workerCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val workerCount: Int,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * RevokeCommand은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
 * @property aggregateType 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property aggregateId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property confirmedAggregateId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property previewToken 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */


internal class RevokeCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: RevokeAggregateType,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `confirmedAggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val confirmedAggregateId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `previewToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val previewToken: String,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)
        require(previewToken.isNotBlank() && previewToken.length <= MAX_PREVIEW_TOKEN_LENGTH)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH)
    }

    /**
     * toString은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String =
        "RevokeCommand(tenantId=$tenantId,aggregateType=$aggregateType,aggregateId=$aggregateId," +
                "confirmedAggregateId=$confirmedAggregateId,expectedRevision=$expectedRevision," +
                "previewToken=[REDACTED],idempotencyKey=[REDACTED])"
}

/**
 * VoucherPoolRevocationService은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface VoucherPoolRevocationService {

    /**
     * `preview`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = preview(tenantId = "tenant-a", aggregateType = aggregateType, aggregateId =
     * UUID.randomUUID(), expectedRevision = 1L)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param aggregateType 요청 경계에서 검증된 `aggregateType`입니다.
     * @param aggregateId 요청 경계에서 검증된 `aggregateId`입니다.
     * @param expectedRevision 요청 경계에서 검증된 `expectedRevision`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot

    /**
     * `revoke`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = revoke(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot>

    /**
     * `progress`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = progress(tenantId = "tenant-a", aggregateType = aggregateType, aggregateId =
     * UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param aggregateType 요청 경계에서 검증된 `aggregateType`입니다.
     * @param aggregateId 요청 경계에서 검증된 `aggregateId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun progress(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): RevokeProgressSnapshot?
}

/**
 * single-use signed preview grant를 발급하고, accepted revocation을 durable worker에 atomic하게 넘깁니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property claims 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property random 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property previewLifetime 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */
@Suppress("SqlResolve")
internal class JdbcVoucherPoolRevocationService(
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: VoucherPoolJdbcExecutor,
    /**
     * `repository`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: VoucherPoolRepository,
    /**
     * `idempotency`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: VoucherPoolIdempotencyRepository,
    /**
     * `digests`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val digests: VoucherDigestService,
    /**
     * `claims`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val claims: JdbcVoucherPoolWorkerRepository,
    /**
     * `random`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val random: SecureRandom = SecureRandom(),
    /**
     * `previewLifetime`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val previewLifetime: Duration = 5.seconds(),
): VoucherPoolRevocationService {

    companion object: KLogging()

    init {
        previewLifetime.requirePositiveDuration("previewLifetimeDuration")
    }

    /**
     * `preview`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = preview(tenantId = "tenant-a", aggregateType = aggregateType, aggregateId =
     * UUID.randomUUID(), expectedRevision = 1L)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param aggregateType 요청 경계에서 검증된 `aggregateType`입니다.
     * @param aggregateId 요청 경계에서 검증된 `aggregateId`입니다.
     * @param expectedRevision 요청 경계에서 검증된 `expectedRevision`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)

        return executor.operatorTransaction {
            val authority = lockAggregate(tenantId, aggregateType, aggregateId)

            requireRevision(authority.revision, expectedRevision)
            requireRevocable(aggregateType, authority.state)

            val impact = readImpact(tenantId, aggregateType, aggregateId, authority.revision)
            issueGrant(tenantId, impact)
        }
    }

    /**
     * `revoke`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = revoke(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot> {
        val operation = command.aggregateType.operation()
        val scope = CommandScope(command.tenantId, operation)

        val decision = executor.operatorTransaction {
            idempotency.acquire(scope, command.idempotencyKey, command.fingerprint(operation))
        }
        return when (decision) {
            is IdempotencyDecision.Execute    -> executeOwned(decision.owner, command)
            is IdempotencyDecision.Replay     -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired    -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(RevocationCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    /**
     * `progress`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = progress(tenantId = "tenant-a", aggregateType = aggregateType, aggregateId =
     * UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param aggregateType 요청 경계에서 검증된 `aggregateType`입니다.
     * @param aggregateId 요청 경계에서 검증된 `aggregateId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun progress(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): RevokeProgressSnapshot? = executor.operatorTransaction {
        // state/revision은 Exposed로 읽고, observedAt만 PostgreSQL transaction snapshot을 사용합니다.
        val observedAt = repository.transactionTime()
        when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolCampaignTable
                .select(VoucherPoolCampaignTable.state, VoucherPoolCampaignTable.revision)
                .where {
                    (VoucherPoolCampaignTable.tenantId eq tenantId) and
                            (VoucherPoolCampaignTable.campaignId eq aggregateId)
                }
                .singleOrNull()
                ?.let { row ->
                    RevokeProgressSnapshot(
                        aggregateType = aggregateType,
                        aggregateId = aggregateId,
                        state = row[VoucherPoolCampaignTable.state].name,
                        revision = row[VoucherPoolCampaignTable.revision],
                        workerCount = campaignWorkerCount(tenantId, aggregateId),
                        observedAt = observedAt,
                    )
                }

            RevokeAggregateType.BATCH -> VoucherPoolBatchTable
                .select(VoucherPoolBatchTable.state, VoucherPoolBatchTable.revision)
                .where {
                    (VoucherPoolBatchTable.tenantId eq tenantId) and
                            (VoucherPoolBatchTable.batchId eq aggregateId)
                }
                .singleOrNull()
                ?.let { row ->
                    RevokeProgressSnapshot(
                        aggregateType = aggregateType,
                        aggregateId = aggregateId,
                        state = row[VoucherPoolBatchTable.state].name,
                        revision = row[VoucherPoolBatchTable.revision],
                        workerCount = batchWorkerCount(tenantId, aggregateId),
                        observedAt = observedAt,
                    )
                }
        }
    }

    /**
     * executeOwned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun executeOwned(
        owner: IdempotencyOwner,
        command: RevokeCommand,
    ): MutationResult<RevokeProgressSnapshot> = try {
        val progress = executor.operatorTransaction {
            idempotency.lockOwnerForExecution(owner)
            val authority = lockAggregate(command.tenantId, command.aggregateType, command.aggregateId)
            requireRevision(authority.revision, command.expectedRevision)
            if (command.confirmedAggregateId != command.aggregateId) fail(RevocationCommandFailure.INVALID_PREVIEW)
            val token = parseToken(command.previewToken)
            val grant = lockGrant(command.tenantId, token.grantId)
                ?: fail(RevocationCommandFailure.INVALID_PREVIEW)

            val impact = readImpact(
                tenantId = command.tenantId,
                aggregateType = command.aggregateType,
                aggregateId = command.aggregateId,
                revision = authority.revision,
            )
            validateGrant(command, token, grant, impact)
            requireRevocable(command.aggregateType, authority.state)
            consumeGrant(grant)
            val updated = transitionAndHandoff(command)
            idempotency.finalize(
                owner = owner,
                descriptor = SafeResponseDescriptor.success(
                    status = HTTP_ACCEPTED,
                    outcome = "REVOCATION_ACCEPTED",
                    effectId = command.aggregateId,
                    revision = updated.revision
                ),
                effect = EffectReference.effect(command.aggregateId),
            )
            updated
        }
        MutationResult.Applied(progress)
    } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
        releaseOwner(owner)
        throw failure
    }

    /**
     * releaseOwner은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun releaseOwner(owner: IdempotencyOwner) {
        try {
            executor.operatorTransaction {
                idempotency.releaseRetryable(owner)
            }
        } catch (_: RuntimeException) {
            // 원래 command failure는 authoritative로 남고, ownership expiry는 안전한 fallback입니다.
        }
    }

    /**
     * transitionAndHandoff은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun transitionAndHandoff(
        command: RevokeCommand,
    ): RevokeProgressSnapshot {
        val now = repository.transactionTime()
        return when (command.aggregateType) {
            RevokeAggregateType.CAMPAIGN -> {
                val campaign = repository.lockCampaignForUpdate(
                    tenantId = command.tenantId,
                    campaignId = command.aggregateId
                ).checkNotNull("campaign")

                val updated = repository.updateCampaign(
                    campaign = campaign.copy(state = CampaignState.REVOKING),
                    expectedRevision = campaign.revision
                )
                val workerCount = campaignWorkerCount(command.tenantId, command.aggregateId)

                val terminal = if (workerCount == 0) {
                    repository.updateCampaign(
                        updated.copy(state = CampaignState.REVOKED),
                        updated.revision,
                    )
                } else {
                    claims.ensureClaimInTransaction(
                        command.tenantId,
                        WorkerKind.CAMPAIGN_REVOKE,
                        command.aggregateId,
                    )
                    updated
                }

                RevokeProgressSnapshot(
                    aggregateType = command.aggregateType,
                    aggregateId = command.aggregateId,
                    state = terminal.state.name,
                    revision = terminal.revision,
                    workerCount = workerCount,
                    observedAt = now,
                )
            }

            RevokeAggregateType.BATCH    -> {
                val batch = repository.lockBatchForUpdate(
                    command.tenantId,
                    command.aggregateId
                ).checkNotNull("batch")
                val updated = repository.updateBatchState(batch, BatchState.REVOKING)
                claims.ensureClaimInTransaction(
                    tenantId = command.tenantId,
                    kind = WorkerKind.BATCH_REVOKE,
                    scopeId = command.aggregateId,
                )
                RevokeProgressSnapshot(
                    command.aggregateType,
                    command.aggregateId,
                    updated.state.name,
                    updated.revision,
                    1,
                    now
                )
            }
        }
    }

    /**
     * issueGrant은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param impact 업무 처리에 필요한 impact 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun issueGrant(
        tenantId: String,
        impact: ImpactSnapshot,
    ): RevokePreviewSnapshot {
        val grantId = Uuid.V7.nextId()
        // nonce는 서명 토큰의 cryptographic nonce이므로 SecureRandom 생성 경로를 유지한다.
        val nonce = ByteArray(TOKEN_NONCE_BYTES).also(random::nextBytes)
        val expiresAt = impact.observedAt.plus(previewLifetime)
        val material = tokenMaterial(grantId, nonce, impact, expiresAt)
        val signature = digests.audit(tenantId, TOKEN_OPERATION, material)
        val signatureBytes = signature.copyBytes()

        return try {
            val inserted = VoucherPoolRevokePreviewGrantTable.insert {
                it[VoucherPoolRevokePreviewGrantTable.tenantId] = tenantId
                it[VoucherPoolRevokePreviewGrantTable.grantId] = grantId
                it[VoucherPoolRevokePreviewGrantTable.aggregateType] = impact.aggregateType.name
                it[VoucherPoolRevokePreviewGrantTable.aggregateId] = impact.aggregateId
                it[VoucherPoolRevokePreviewGrantTable.aggregateRevision] = impact.revision
                it[VoucherPoolRevokePreviewGrantTable.impactDigest] = impact.impactDigest
                it[VoucherPoolRevokePreviewGrantTable.affectedCount] = impact.affectedCount
                it[VoucherPoolRevokePreviewGrantTable.signatureKeyVersion] = signature.keyVersion
                it[VoucherPoolRevokePreviewGrantTable.signatureDigest] = signatureBytes
                it[VoucherPoolRevokePreviewGrantTable.expiresAt] = expiresAt
            }
            check(inserted.insertedCount == 1)
            val token = encodeToken(grantId, nonce, signature)
            RevokePreviewSnapshot(
                aggregateType = impact.aggregateType,
                aggregateId = impact.aggregateId,
                revision = impact.revision,
                counts = impact.counts,
                eligibleDepth = impact.eligibleDepth,
                activeReservations = impact.activeReservations,
                activeAllocations = impact.activeAllocations,
                alreadyTerminalCount = impact.alreadyTerminalCount,
                affectedCount = impact.affectedCount,
                previewToken = token,
                expiresAt = expiresAt,
                observedAt = impact.observedAt,
            )
        } finally {
            nonce.fill(0)
            signatureBytes.fill(0)
        }
    }

    /**
     * validateGrant은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param token 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param grant 업무 처리에 필요한 grant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param impact 업무 처리에 필요한 impact 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateGrant(
        command: RevokeCommand,
        token: ParsedToken,
        grant: RevokeGrant,
        impact: ImpactSnapshot,
    ) {
        val now = impact.observedAt
        val validGrant = sequenceOf(
            grant.aggregateType == command.aggregateType,
            grant.aggregateId == command.aggregateId,
            grant.aggregateRevision == command.expectedRevision,
            grant.consumedAt == null,
            grant.expiresAt > now,
            grant.affectedCount == impact.affectedCount,
            MessageDigest.isEqual(grant.impactDigest, impact.impactDigest),
            grant.signatureKeyVersion == token.keyVersion,
            MessageDigest.isEqual(grant.signatureDigest, token.signature),
        ).all { it }
        if (!validGrant) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }

        val expected = VoucherDigest.of(DigestPurpose.AUDIT, token.keyVersion, token.signature)
        val material = tokenMaterial(token.grantId, token.nonce, impact, grant.expiresAt)
        if (!digests.matchesAudit(command.tenantId, TOKEN_OPERATION, material, expected)) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
    }

    /**
     * consumeGrant은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param grant 업무 처리에 필요한 grant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun consumeGrant(grant: RevokeGrant) {
        val updated = VoucherPoolRevokePreviewGrantTable.update({
            (VoucherPoolRevokePreviewGrantTable.tenantId eq grant.tenantId) and
                    (VoucherPoolRevokePreviewGrantTable.grantId eq grant.grantId) and
                    VoucherPoolRevokePreviewGrantTable.consumedAt.isNull() and
                    (VoucherPoolRevokePreviewGrantTable.expiresAt greater CurrentTimestamp)
        }) {
            it[VoucherPoolRevokePreviewGrantTable.consumedAt] = CurrentTimestamp
        }
        if (updated != 1) fail(RevocationCommandFailure.INVALID_PREVIEW)
    }

    /**
     * lockGrant은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param grantId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun lockGrant(tenantId: String, grantId: UUID): RevokeGrant? =
        VoucherPoolRevokePreviewGrantTable
            .selectAll()
            .where {
                (VoucherPoolRevokePreviewGrantTable.tenantId eq tenantId) and
                        (VoucherPoolRevokePreviewGrantTable.grantId eq grantId)
            }
            .forUpdate()
            .singleOrNull()
            ?.let { it.revokeGrant() }

    /**
     * lockAggregate은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun lockAggregate(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): LockedAggregate = when (aggregateType) {
        RevokeAggregateType.CAMPAIGN -> {
            val campaign = repository.lockCampaignForUpdate(tenantId, aggregateId)
                ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)
            LockedAggregate(campaign.state.name, campaign.revision)
        }

        RevokeAggregateType.BATCH ->
            lockBatchAggregate(tenantId, aggregateId)
    }

    /**
     * lockBatchAggregate은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun lockBatchAggregate(tenantId: String, batchId: UUID): LockedAggregate {
        val campaignId = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull()
            ?.get(VoucherPoolBatchTable.campaignId)
            ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)

        repository.lockCampaignForShare(tenantId, campaignId)
        val batch = repository.lockBatchForUpdate(tenantId, batchId)
            ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)

        return LockedAggregate(batch.state.name, batch.revision)
    }

    /**
     * readImpact은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun readImpact(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        revision: Long,
    ): ImpactSnapshot {
        val counts = EntryState.entries.associateWith { 0L }.toMutableMap()
        val entryScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolEntryTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolEntryTable.batchId eq aggregateId
        }
        val entryCount = VoucherPoolEntryTable.entryId.count()
        VoucherPoolEntryTable
            .select(VoucherPoolEntryTable.state, entryCount)
            .where { (VoucherPoolEntryTable.tenantId eq tenantId) and entryScope }
            .groupBy(VoucherPoolEntryTable.state)
            .forEach { row -> counts[row[VoucherPoolEntryTable.state]] = row[entryCount] }

        val reservationScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolReservationTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolReservationTable.batchId eq aggregateId
        }
        val activeReservations = VoucherPoolReservationTable
            .selectAll()
            .where {
                (VoucherPoolReservationTable.tenantId eq tenantId) and
                        reservationScope and
                        (VoucherPoolReservationTable.state eq io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState.ACTIVE)
            }
            .count()
        val eligibleDepth = eligibleDepth(tenantId, aggregateType, aggregateId)
        val activeAllocations = counts.getValue(EntryState.ALLOCATED)
        val affectedCount = counts.getValue(EntryState.AVAILABLE) +
                counts.getValue(EntryState.RESERVED) + activeAllocations
        val terminalCount = counts.getValue(EntryState.REDEEMED) +
                counts.getValue(EntryState.RELEASED) +
                counts.getValue(EntryState.REVOKED) +
                counts.getValue(EntryState.EXPIRED)

        val observedAt = repository.transactionTime()

        val digest = impactDigest(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            revision = revision,
            counts = counts,
            eligibleDepth = eligibleDepth,
            activeReservations = activeReservations,
            activeAllocations = activeAllocations,
            terminalCount = terminalCount,
            affectedCount = affectedCount,
        )
        return ImpactSnapshot(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            revision = revision,
            counts = counts.toMap(),
            eligibleDepth = eligibleDepth,
            activeReservations = activeReservations,
            activeAllocations = activeAllocations,
            alreadyTerminalCount = terminalCount,
            affectedCount = affectedCount,
            impactDigest = digest,
            observedAt = observedAt,
        )
    }

    /**
     * eligibleDepth은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun eligibleDepth(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): Long {
        val aggregateScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolEntryTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolEntryTable.batchId eq aggregateId
        }
        val joined = VoucherPoolEntryTable
            .innerJoin(
                VoucherPoolCampaignTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolCampaignTable.tenantId },
                additionalConstraint = { VoucherPoolEntryTable.campaignId eq VoucherPoolCampaignTable.campaignId },
            )
            .innerJoin(
                VoucherPoolBatchTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolBatchTable.tenantId },
                additionalConstraint = {
                    (VoucherPoolEntryTable.batchId eq VoucherPoolBatchTable.batchId) and
                            (VoucherPoolEntryTable.campaignId eq VoucherPoolBatchTable.campaignId)
                },
            )
        return joined
            .select(VoucherPoolEntryTable.entryId)
            .where {
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                        aggregateScope and
                        (VoucherPoolEntryTable.state eq EntryState.AVAILABLE) and
                        VoucherPoolEntryTable.quarantinedAt.isNull() and
                        (VoucherPoolCampaignTable.state eq CampaignState.ACTIVE) and
                        (VoucherPoolCampaignTable.startsAt lessEq CurrentTimestamp) and
                        (VoucherPoolCampaignTable.endsAt greater CurrentTimestamp) and
                        (VoucherPoolBatchTable.state eq BatchState.ACTIVE) and
                        (VoucherPoolBatchTable.activatesAt lessEq CurrentTimestamp) and
                        (VoucherPoolBatchTable.expiresAt.isNull() or
                                (VoucherPoolBatchTable.expiresAt greater CurrentTimestamp))
            }
            .count()
    }

    /**
     * campaignWorkerCount은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun campaignWorkerCount(tenantId: String, campaignId: UUID): Int =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .count()
            .toInt()

    /**
     * batchWorkerCount은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun batchWorkerCount(tenantId: String, batchId: UUID): Int =
        VoucherPoolWorkerClaimTable
            .selectAll()
            .where {
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.BATCH_REVOKE.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq batchId)
            }
            .count()
            .toInt()

    /**
     * requireRevision은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actual 업무 처리에 필요한 actual 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireRevision(actual: Long, expected: Long) {
        if (actual != expected) fail(RevocationCommandFailure.STALE_REVISION)
    }

    /**
     * requireRevocable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun requireRevocable(aggregateType: RevokeAggregateType, state: String) {
        val allowed = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN ->
                CampaignPolicy.canTransition(
                    CampaignState.valueOf(state),
                    CampaignState.REVOKING
                )
            RevokeAggregateType.BATCH    ->
                BatchPolicy.canTransition(BatchState.valueOf(state), BatchState.REVOKING)
        }
        if (!allowed) fail(RevocationCommandFailure.INVALID_STATE)
    }

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun RevokeCommand.fingerprint(operation: String): CommandFingerprint =
        VoucherPoolFingerprint.command(
            operation = operation,
            fields = mapOf(
                "aggregateType" to aggregateType.name,
                "aggregateId" to aggregateId.toString(),
                "confirmedAggregateId" to confirmedAggregateId.toString(),
                "expectedRevision" to expectedRevision.toString(),
                "previewTokenDigest" to MessageDigest.getInstance("SHA-256").digest(previewToken.toUtf8Bytes()).toHex(),
            ),
        )

    /**
     * operation은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun RevokeAggregateType.operation(): String = "${name.lowercase()}-revoke"

    /**
     * tokenMaterial은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param grantId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param nonce 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param impact 업무 처리에 필요한 impact 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expiresAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun tokenMaterial(
        grantId: UUID,
        nonce: ByteArray,
        impact: ImpactSnapshot,
        expiresAt: Instant,
    ): String =
        listOf(
            grantId,
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
            impact.aggregateType,
            impact.aggregateId,
            impact.revision,
            Base64.getUrlEncoder().withoutPadding().encodeToString(impact.impactDigest),
            impact.affectedCount,
            expiresAt.epochSecond,
            expiresAt.nano,
        )
            .joinToString("|")

    /**
     * encodeToken은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param grantId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param nonce 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param signature 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun encodeToken(grantId: UUID, nonce: ByteArray, signature: VoucherDigest): String {
        val signatureBytes = signature.copyBytes()

        return try {
            listOf(
                TOKEN_VERSION,
                grantId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                signature.keyVersion,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes),
            ).joinToString(".")
        } finally {
            signatureBytes.fill(0)
        }
    }

    /**
     * parseToken은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param raw 업무 처리에 필요한 raw 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun parseToken(raw: String): ParsedToken =
        try {
            /**
             * `parts`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val parts = raw.split('.')
            if (parts.size != TOKEN_PARTS || parts[0] != TOKEN_VERSION) fail(RevocationCommandFailure.INVALID_PREVIEW)
            /**
             * `nonce`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
             *
             * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
             */
            val nonce = Base64.getUrlDecoder().decode(parts[2])
            /**
             * `signature`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val signature = Base64.getUrlDecoder().decode(parts[4])
            if (nonce.size != TOKEN_NONCE_BYTES || signature.size != TOKEN_SIGNATURE_BYTES) {
                fail(RevocationCommandFailure.INVALID_PREVIEW)
            }
            ParsedToken(UUID.fromString(parts[1]), nonce, parts[3].toInt(), signature)
        } catch (failure: RevocationCommandException) {
            throw failure
        } catch (_: RuntimeException) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
/**
 * LockedAggregate은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


    private data class LockedAggregate(
        /**
         * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val state: String,
        /**
         * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val revision: Long,
    ): Serializable {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * ImpactSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property counts 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property eligibleDepth 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property activeReservations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property activeAllocations 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property alreadyTerminalCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property affectedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property impactDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


    private data class ImpactSnapshot(
        /**
         * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val aggregateType: RevokeAggregateType,
        /**
         * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val aggregateId: UUID,
        /**
         * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val revision: Long,
        /**
         * `counts`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val counts: Map<EntryState, Long>,
        /**
         * `eligibleDepth`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val eligibleDepth: Long,
        /**
         * `activeReservations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val activeReservations: Long,
        /**
         * `activeAllocations`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val activeAllocations: Long,
        /**
         * `alreadyTerminalCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val alreadyTerminalCount: Long,
        /**
         * `affectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val affectedCount: Long,
        /**
         * `impactDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val impactDigest: ByteArray,
        /**
         * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val observedAt: Instant,
    ): Serializable {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * ParsedToken은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property grantId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property nonce 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property keyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property signature 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


    private data class ParsedToken(
        /**
         * `grantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val grantId: UUID,
        /**
         * `nonce`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val nonce: ByteArray,
        /**
         * `keyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val keyVersion: Int,
        /**
         * `signature`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val signature: ByteArray,
    ): Serializable {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
/**
 * RevokeGrant은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property grantId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property aggregateType 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property aggregateId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property aggregateRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property impactDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property affectedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property signatureKeyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property signatureDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property consumedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


    private data class RevokeGrant(
        /**
         * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val tenantId: String,
        /**
         * `grantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val grantId: UUID,
        /**
         * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val aggregateType: RevokeAggregateType,
        /**
         * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val aggregateId: UUID,
        /**
         * `aggregateRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val aggregateRevision: Long,
        /**
         * `impactDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val impactDigest: ByteArray,
        /**
         * `affectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val affectedCount: Long,
        /**
         * `signatureKeyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val signatureKeyVersion: Int,
        /**
         * `signatureDigest`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val signatureDigest: ByteArray,
        /**
         * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val expiresAt: Instant,
        /**
         * `consumedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val consumedAt: Instant?,
    ): Serializable {
        companion object {
            /**
             * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * revokeGrant은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ResultRow.revokeGrant(): RevokeGrant =
        RevokeGrant(
            tenantId = this[VoucherPoolRevokePreviewGrantTable.tenantId],
            grantId = this[VoucherPoolRevokePreviewGrantTable.grantId],
            aggregateType = RevokeAggregateType.valueOf(this[VoucherPoolRevokePreviewGrantTable.aggregateType]),
            aggregateId = this[VoucherPoolRevokePreviewGrantTable.aggregateId],
            aggregateRevision = this[VoucherPoolRevokePreviewGrantTable.aggregateRevision],
            impactDigest = this[VoucherPoolRevokePreviewGrantTable.impactDigest],
            affectedCount = this[VoucherPoolRevokePreviewGrantTable.affectedCount],
            signatureKeyVersion = this[VoucherPoolRevokePreviewGrantTable.signatureKeyVersion],
            signatureDigest = this[VoucherPoolRevokePreviewGrantTable.signatureDigest],
            expiresAt = this[VoucherPoolRevokePreviewGrantTable.expiresAt],
            consumedAt = this[VoucherPoolRevokePreviewGrantTable.consumedAt],
        )
}

/**
 * impactDigest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param aggregateId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param counts 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
 * @param eligibleDepth 업무 처리에 필요한 eligibleDepth 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param activeReservations 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param activeAllocations 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param terminalCount 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
 * @param affectedCount 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
 */
private fun impactDigest(
    aggregateType: RevokeAggregateType,
    aggregateId: UUID,
    revision: Long,
    counts: Map<EntryState, Long>,
    eligibleDepth: Long,
    activeReservations: Long,
    activeAllocations: Long,
    terminalCount: Long,
    affectedCount: Long,
): ByteArray {
    val material = buildString {
        append(aggregateType).append('|').append(aggregateId).append('|').append(revision)
        EntryState.entries.forEach { state -> append('|').append(state).append('=').append(counts[state] ?: 0L) }
        append('|').append(eligibleDepth)
        append('|').append(activeReservations)
        append('|').append(activeAllocations)
        append('|').append(terminalCount)
        append('|').append(affectedCount)
    }
    return MessageDigest.getInstance("SHA-256").digest(material.toUtf8Bytes())
}

/**
 * toHex은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

/**
 * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun fail(reason: RevocationCommandFailure): Nothing = throw RevocationCommandException(reason)

/**
 * `HTTP_ACCEPTED`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HTTP_ACCEPTED = 202
/**
 * `MAX_TENANT_LENGTH`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val MAX_TENANT_LENGTH = 64
/**
 * `MAX_PREVIEW_TOKEN_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_PREVIEW_TOKEN_LENGTH = 512
/**
 * `MAX_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
/**
 * `TOKEN_OPERATION`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val TOKEN_OPERATION = "operator-revoke-preview-token-v1"
/**
 * `TOKEN_VERSION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val TOKEN_VERSION = "v1"
/**
 * `TOKEN_PARTS`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val TOKEN_PARTS = 5
/**
 * `TOKEN_NONCE_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val TOKEN_NONCE_BYTES = 24
/**
 * `TOKEN_SIGNATURE_BYTES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val TOKEN_SIGNATURE_BYTES = 32
