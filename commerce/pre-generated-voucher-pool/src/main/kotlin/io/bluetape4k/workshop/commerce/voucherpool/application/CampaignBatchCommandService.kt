@file:Suppress(
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.PreparedVoucherEntryRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.RejectedVoucherEntryRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoException
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

/**
 * `BatchSourceKind`은 분류 코드입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class BatchSourceKind {
    /**
     * `BatchSourceKind.IMPORTED`은 `imported` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를 결정합니다.
     */
    IMPORTED,
    /**
     * `BatchSourceKind.GENERATED`은 `generated` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    GENERATED
}

/**
 * releaseRetryableOwner은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
 * 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param release 업무 처리에 필요한 release 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal inline fun releaseRetryableOwner(release: () -> Unit) {
    var lastTimeout: VoucherPoolJdbcTimeoutException? = null
    repeat(MAX_OWNER_RELEASE_ATTEMPTS) {
        try {
            release()
            return
        } catch (failure: VoucherPoolJdbcTimeoutException) {
            lastTimeout = failure
            Thread.yield()
        }
    }
    throw checkNotNull(lastTimeout)
}

/**
 * `MAX_OWNER_RELEASE_ATTEMPTS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val MAX_OWNER_RELEASE_ATTEMPTS = 8

/**
 * `BatchCommandFailure`은 실패·오류 분류 코드입니다.
 * 현재 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행하는 명령·신호 계약입니다.
 */
internal enum class BatchCommandFailure {
    /**
     * `BatchCommandFailure.SCOPE_NOT_FOUND`은 요청한 tenant·scope에 해당하는 자원이 존재하지 않는 값입니다.
     */
    SCOPE_NOT_FOUND,
    /**
     * `BatchCommandFailure.STALE_REVISION`은 호출자가 가진 revision이 저장된 최신 revision보다 오래되어 반영할 수 없는 값입니다.
     */
    STALE_REVISION,
    /**
     * `BatchCommandFailure.INVALID_STATE`은 입력 또는 구성 검증에 실패해 안전하게 처리를 시작하지 않은 값입니다.
     */
    INVALID_STATE,
    /**
     * `BatchCommandFailure.ORDINAL_GAP`은 `ordinal gap` 업무 상황을 나타냅니다. 현재 상태와 aggregate revision을
     * 검증한 뒤 허용된
     * 전이만 실행합니다.
     */
    ORDINAL_GAP,
    /**
     * `BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT`은 `chunk fingerprint conflict` 업무 상황을 나타냅니다.
     * 현재 상태와
     * aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CHUNK_FINGERPRINT_CONFLICT,
    /**
     * `BatchCommandFailure.DUPLICATE_CODE`은 `duplicate code` 업무 상황을 나타냅니다. 현재 상태와 aggregate
     * revision을 검증한
     * 뒤 허용된 전이만 실행합니다.
     */
    DUPLICATE_CODE,
    /**
     * `BatchCommandFailure.ACTIVATION_INCOMPLETE`은 `activation incomplete` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    ACTIVATION_INCOMPLETE,
    /**
     * `BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT`은 동일 key에 다른 요청 fingerprint가 들어와 side
     * effect를
     * 거부하는 값입니다.
     */
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    /**
     * `BatchCommandFailure.COMMAND_IN_PROGRESS`은 동일 command가 이미 실행 중이어서 중복 실행을 막아야 하는 값입니다.
     */
    COMMAND_IN_PROGRESS,
    /**
     * `BatchCommandFailure.REPLAY_WINDOW_EXPIRED`은 멱등 결과를 재생할 보존 창이 지나 원 요청을 다시 처리할 수 없는 값입니다.
     */
    REPLAY_WINDOW_EXPIRED,
    /**
     * `BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT`은 `create fingerprint conflict` 업무 상황을
     * 나타냅니다. 현재
     * 상태와 aggregate revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    CREATE_FINGERPRINT_CONFLICT,
    /**
     * `BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION`은 일시 오류로 분류되어 동일 operation을 재시도할 수 있는
     * 값입니다.
     */
    RETRYABLE_INTEGRITY_COLLISION,
    /**
     * `BatchCommandFailure.VALIDATION_REJECTED`은 `validation rejected` 업무 상황을 나타냅니다. 현재 상태와
     * aggregate
     * revision을 검증한 뒤 허용된 전이만 실행합니다.
     */
    VALIDATION_REJECTED,
}
/**
 * BatchCommandException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property reason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal open class BatchCommandException(val reason: BatchCommandFailure): IllegalStateException(reason.name)
/**
 * PreparedChunkRejectedException은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property evidenceCode 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property rejectedCount 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property nextSourceOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class PreparedChunkRejectedException(
    /**
     * `evidenceCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val evidenceCode: String,
    /**
     * `rejectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val rejectedCount: Long,
    /**
     * `nextSourceOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nextSourceOrdinal: Long,
): BatchCommandException(BatchCommandFailure.VALIDATION_REJECTED)

/**
 * MutationResult은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface MutationResult<out T> {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    data class Applied<T>(val value: T): MutationResult<T>
/**
 * Replay은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

    data class Replay(val descriptor: SafeResponseDescriptor): MutationResult<Nothing>
/**
 * Expired은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property effectId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property terminalCode 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */

    data class Expired(val effectId: UUID?, val terminalCode: VoucherPoolErrorCode?): MutationResult<Nothing>
}

/**
 * `VoucherPoolRuntimeProfile`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 * @property permitsDeterministicGeneration 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */
internal enum class VoucherPoolRuntimeProfile(val permitsDeterministicGeneration: Boolean) {
    /**
     * `VoucherPoolRuntimeProfile.PRODUCTION`은 `production` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    PRODUCTION(false),
    /**
     * `VoucherPoolRuntimeProfile.LOOPBACK_TEST`은 `loopback test` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    LOOPBACK_TEST(true),
    /**
     * `VoucherPoolRuntimeProfile.DEMO`은 `demo` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    DEMO(true),
}
/**
 * CampaignSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class CampaignSnapshot(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: CampaignState,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
)
/**
 * BatchSnapshot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property nextSourceOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expectedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property acceptedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property rejectedCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property checkpointDigest 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lastFailureCode 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class BatchSnapshot(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: BatchState,
    /**
     * `nextSourceOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val nextSourceOrdinal: Long,
    /**
     * `expectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val expectedCount: Long,
    /**
     * `acceptedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val acceptedCount: Long,
    /**
     * `rejectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val rejectedCount: Long,
    /**
     * `checkpointDigest`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpointDigest: DigestValue?,
    /**
     * `lastFailureCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val lastFailureCode: String?,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
)
/**
 * CreateCampaignCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property startsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 * @property endsAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을 유지합니다.
 * @property policy 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class CreateCampaignCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `startsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startsAt: Instant,
    /**
     * `endsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val endsAt: Instant,
    /**
     * `policy`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val policy: VoucherPoolPolicy,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(startsAt < endsAt) { "campaign start must precede end" }
    }
}
/**
 * UpdateCampaignPolicyCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property policy 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class UpdateCampaignPolicyCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `policy`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val policy: VoucherPoolPolicy,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}
/**
 * CampaignRevisionCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class CampaignRevisionCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}
/**
 * CreateImportBatchCommand은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
 * @property batchId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property campaignId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property sourceKind 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property manifestDigest 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property requestFingerprint 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property expectedCount 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property activatesAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property expiresAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */


internal class CreateImportBatchCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `sourceKind`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val sourceKind: BatchSourceKind,
    /**
     * `manifestDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val manifestDigest: DigestValue,
    /**
     * `requestFingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val requestFingerprint: DigestValue,
    /**
     * `expectedCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val expectedCount: Long,
    /**
     * `activatesAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val activatesAt: Instant,
    /**
     * `expiresAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant? = null,
    initialCodes: List<String>,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val initialCodes: List<String> = initialCodes.toList()

    init {
        requireTenant(tenantId)
        require(expectedCount in 1..MAX_BATCH_ENTRIES) { "expectedCount must be in 1..$MAX_BATCH_ENTRIES" }
        require(this.initialCodes.size <= expectedCount) { "initial chunk exceeds expectedCount" }
        if (sourceKind == BatchSourceKind.IMPORTED) require(this.initialCodes.isNotEmpty()) {
            "import batch requires its first chunk"
        }
        if (sourceKind == BatchSourceKind.GENERATED) require(this.initialCodes.isEmpty()) {
            "generated batch must not accept caller codes"
        }
        validateChunkPayload(this.initialCodes, allowEmpty = sourceKind == BatchSourceKind.GENERATED)
        require(expiresAt == null || expiresAt > activatesAt) { "batch expiry must follow activation" }
    }
}
/**
 * ImportChunkCommand은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
 * @property batchId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property campaignId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property firstOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property manifestDigest 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */


internal class ImportChunkCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `firstOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val firstOrdinal: Long,
    /**
     * `manifestDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val manifestDigest: DigestValue,
    codes: List<String>,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    val codes: List<String> = codes.toList()

    init {
        requireTenant(tenantId)
        require(firstOrdinal >= 0) { "firstOrdinal must not be negative" }
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
        validateChunkPayload(this.codes)
    }
}
/**
 * GenerateChunkCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property firstOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property manifestDigest 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property count 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class GenerateChunkCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `firstOrdinal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val firstOrdinal: Long,
    /**
     * `manifestDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val manifestDigest: DigestValue,
    /**
     * `count`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val count: Int,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(firstOrdinal >= 0) { "firstOrdinal must not be negative" }
        require(count in 1..MAX_CHUNK_ENTRIES) { "generation count must be in 1..$MAX_CHUNK_ENTRIES" }
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}
/**
 * BatchRevisionCommand은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property batchId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property idempotencyKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


internal data class BatchRevisionCommand(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `batchId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val batchId: UUID,
    /**
     * `expectedRevision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedRevision: Long,
    /**
     * `idempotencyKey`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

/**
 * GeneratedVoucherCodeSource은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface GeneratedVoucherCodeSource {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val deterministic: Boolean
    /**
     * `nextCode`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = nextCode()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun nextCode(): String
}
/**
 * SecureRandomVoucherCodeSource은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property random 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class SecureRandomVoucherCodeSource(private val random: SecureRandom = SecureRandom()):
    GeneratedVoucherCodeSource {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    override val deterministic: Boolean = false

    /**
     * `nextCode`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = nextCode()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun nextCode(): String {
        val entropy = ByteArray(GENERATED_ENTROPY_BYTES).also(random::nextBytes)
        return try {
            "VP-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /**
     * toString은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String = "SecureRandomVoucherCodeSource([REDACTED])"
}

/**
 * CampaignBatchCommandService은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface CampaignBatchCommandService {
    /**
     * `createCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = createCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun createCampaign(command: CreateCampaignCommand): MutationResult<CampaignSnapshot>
    /**
     * `updatePolicy`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = updatePolicy(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun updatePolicy(command: UpdateCampaignPolicyCommand): MutationResult<CampaignSnapshot>
    /**
     * `activateCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activateCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun activateCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    /**
     * `pauseCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = pauseCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun pauseCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    /**
     * `resumeCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = resumeCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun resumeCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    /**
     * `createImportBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = createImportBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun createImportBatch(command: CreateImportBatchCommand): MutationResult<BatchSnapshot>
    /**
     * `importChunk`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = importChunk(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun importChunk(command: ImportChunkCommand): MutationResult<BatchSnapshot>
    /**
     * `generateChunk`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = generateChunk(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun generateChunk(command: GenerateChunkCommand): MutationResult<BatchSnapshot>
    /**
     * `activateBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activateBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun activateBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
    /**
     * `pauseBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = pauseBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun pauseBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
    /**
     * `resumeBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = resumeBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun resumeBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
}

/**
 * bounded application orchestration입니다. 모든 parsing과 encryption은 JDBC admission보다 먼저 수행됩니다.
 * @property executor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property repository 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property digests 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property crypto 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property generatedCodes 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Suppress("LargeClass") // Task-scoped command contracts and their shared idempotency boundary remain co-located.
internal class JdbcCampaignBatchCommandService(
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
     * `crypto`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val crypto: VoucherEnvelopeCrypto,
    /**
     * `generatedCodes`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val generatedCodes: GeneratedVoucherCodeSource = SecureRandomVoucherCodeSource(),
    runtimeProfile: VoucherPoolRuntimeProfile = VoucherPoolRuntimeProfile.PRODUCTION,
): CampaignBatchCommandService {

    companion object: KLogging()

    init {
        require(!generatedCodes.deterministic || runtimeProfile.permitsDeterministicGeneration) {
            "deterministic voucher generation is restricted to loopback test or demo mode"
        }
    }

    /**
     * `createCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = createCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun createCampaign(command: CreateCampaignCommand): MutationResult<CampaignSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = OP_CREATE_CAMPAIGN,
            fingerprint = command.fingerprint(),
            effectId = command.campaignId,
            status = HTTP_CREATED,
            outcome = "CAMPAIGN_CREATED",
            lane = MutationLane.OPERATOR,
            failureMapper = ::classifyIntegrityFailure,
            createRecovery = { connection ->
                repository.lockCampaignForUpdate(command.tenantId, command.campaignId)
                    ?.takeIf { it.sameCreateAuthority(requestedCampaign(command)) }
                    ?.snapshot()
            },
        ) { connection ->
            /**
             * `requested`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val requested = requestedCampaign(command)
            repository.lockCampaignForUpdate(command.tenantId, command.campaignId)?.let { existing ->
                if (!existing.sameCreateAuthority(requested)) fail(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
                return@executeIdempotent existing.snapshot()
            }
            repository.createCampaign(requested).snapshot()
        }

    /**
     * `updatePolicy`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = updatePolicy(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun updatePolicy(command: UpdateCampaignPolicyCommand): MutationResult<CampaignSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = OP_UPDATE_CAMPAIGN_POLICY,
            fingerprint = command.fingerprint(),
            effectId = command.campaignId,
            status = HTTP_OK,
            outcome = "CAMPAIGN_POLICY_UPDATED",
            lane = MutationLane.OPERATOR,
        ) { connection ->
            /**
             * `current`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val current = repository.lockCampaignForUpdate(command.tenantId, command.campaignId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireRevision(current.revision, command.expectedRevision)
            if (current.state !in setOf(CampaignState.DRAFT, CampaignState.PAUSED)) {
                fail(BatchCommandFailure.INVALID_STATE)
            }
            repository.updateCampaign(
                current.copy(
                    perUserLimit = command.policy.perUserLimit,
                    reservationTtlSeconds = command.policy.reservationTtl.inWholeSeconds,
                    allocationTtlSeconds = command.policy.allocationTtl.inWholeSeconds,
                    replacementAllowance = command.policy.replacementAllowance,
                    policyVersion = current.policyVersion + 1,
                ),
                current.revision,
            ).snapshot()
        }

    /**
     * `activateCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activateCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun activateCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = OP_ACTIVATE_CAMPAIGN,
            fingerprint = command.fingerprint(OP_ACTIVATE_CAMPAIGN),
            effectId = command.campaignId,
            status = HTTP_OK,
            outcome = "CAMPAIGN_ACTIVATED",
            lane = MutationLane.OPERATOR,
        ) { connection ->
            /**
             * `current`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val current = repository.lockCampaignForUpdate(command.tenantId, command.campaignId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)

            requireRevision(current.revision, command.expectedRevision)

            if (!CampaignPolicy.canTransition(current.state, CampaignState.ACTIVE)) {
                fail(BatchCommandFailure.INVALID_STATE)
            }
            repository.updateCampaign(current.copy(state = CampaignState.ACTIVE), current.revision).snapshot()
        }

    /**
     * `pauseCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = pauseCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun pauseCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> =
        transitionCampaign(command, CampaignState.PAUSED, OP_PAUSE_CAMPAIGN, "CAMPAIGN_PAUSED")

    /**
     * `resumeCampaign`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = resumeCampaign(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun resumeCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> =
        transitionCampaign(command, CampaignState.ACTIVE, OP_RESUME_CAMPAIGN, "CAMPAIGN_RESUMED")

    /**
     * `createImportBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = createImportBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun createImportBatch(command: CreateImportBatchCommand): MutationResult<BatchSnapshot> {
        val prepared = prepare(command.tenantId, command.campaignId, command.batchId, 0, command.initialCodes)

        return executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = OP_CREATE_BATCH,
            fingerprint = command.fingerprint(prepared),
            effectId = command.batchId,
            status = HTTP_CREATED,
            outcome = "BATCH_CREATED",
            lane = MutationLane.OPERATOR,
            failureMapper = ::classifyIntegrityFailure,
            terminalEffect = { _, failure ->
                markTerminalBatchFailure(
                    command.tenantId,
                    command.campaignId,
                    command.batchId,
                    failure,
                    command.manifestDigest,
                    0,
                ) { campaign -> requestedBatch(command, campaign.policyVersion) }
            },
            createRecovery = { connection ->
                val campaign = repository.lockCampaignForShare(command.tenantId, command.campaignId)
                repository.lockBatchForUpdate(command.tenantId, command.batchId)
                    ?.takeIf { existing ->
                        existing.sameCreateAuthority(requestedBatch(command, campaign.policyVersion)) &&
                                (prepared.totalCount == 0 || committedReplayExact(existing, prepared))
                    }
                    ?.snapshot()
            },
        ) { connection ->
            val campaign = repository.lockCampaignForShare(command.tenantId, command.campaignId)
            if (campaign.state != CampaignState.ACTIVE) fail(BatchCommandFailure.INVALID_STATE)
            val requested = requestedBatch(command, campaign.policyVersion)
            repository.lockBatchForUpdate(command.tenantId, command.batchId)?.let { existing ->
                if (!existing.sameCreateAuthority(requested)) fail(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
                if (prepared.totalCount > 0) replayCommitted(existing, prepared)
                return@executeIdempotent existing.snapshot()
            }
            var batch = repository.createBatch(requested)
            if (prepared.totalCount > 0) {
                batch = commitPrepared(batch, command.manifestDigest, prepared)
            }
            batch.snapshot()
        }
    }

    /**
     * `importChunk`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = importChunk(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun importChunk(command: ImportChunkCommand): MutationResult<BatchSnapshot> {
        val prepared =
            prepare(command.tenantId, command.campaignId, command.batchId, command.firstOrdinal, command.codes)

        return ingest(
            tenantId = command.tenantId,
            campaignId = command.campaignId,
            batchId = command.batchId,
            expectedRevision = command.expectedRevision,
            idempotencyKey = command.idempotencyKey,
            fingerprint = command.fingerprint(prepared),
            manifestDigest = command.manifestDigest,
            prepared = prepared,
            expectedSource = BatchSourceKind.IMPORTED,
        )
    }

    /**
     * `generateChunk`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = generateChunk(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun generateChunk(command: GenerateChunkCommand): MutationResult<BatchSnapshot> {
        val codes = List(command.count) { generatedCodes.nextCode() }
        val prepared = prepare(command.tenantId, command.campaignId, command.batchId, command.firstOrdinal, codes)
        return ingest(
            tenantId = command.tenantId,
            campaignId = command.campaignId,
            batchId = command.batchId,
            expectedRevision = command.expectedRevision,
            idempotencyKey = command.idempotencyKey,
            fingerprint = command.fingerprint(),
            manifestDigest = command.manifestDigest,
            prepared = prepared,
            expectedSource = BatchSourceKind.GENERATED,
        )
    }

    /**
     * `activateBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activateBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun activateBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = OP_ACTIVATE_BATCH,
            fingerprint = command.fingerprint(),
            effectId = command.batchId,
            status = HTTP_OK,
            outcome = "BATCH_ACTIVATED",
            lane = MutationLane.OPERATOR,
        ) { connection ->
            /**
             * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val campaign = repository.lockCampaignForShare(command.tenantId, command.campaignId)
            if (campaign.state != CampaignState.ACTIVE) fail(BatchCommandFailure.INVALID_STATE)
            /**
             * `batch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val batch = repository.lockBatchForUpdate(command.tenantId, command.batchId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireBatchScope(batch, command.campaignId)
            requireRevision(batch.revision, command.expectedRevision)
            /**
             * `coverage`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val coverage = repository.batchOrdinalCoverage(command.tenantId, command.batchId)
            /**
             * `canonicalCheckpoint`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val canonicalCheckpoint = canonicalCheckpoint(batch, batch.expectedCount)
            /**
             * `complete`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val complete = batch.state == BatchState.STAGING &&
                    batch.nextSourceOrdinal == batch.expectedCount &&
                    batch.acceptedCount == batch.expectedCount &&
                    batch.rejectedCount == 0L &&
                    batch.lastFailureCode == null &&
                    coverage.entryCount == batch.expectedCount &&
                    coverage.minimumOrdinal == 0L &&
                    coverage.maximumOrdinal == batch.expectedCount - 1 &&
                    batch.checkpointDigest?.secureEquals(canonicalCheckpoint) == true
            if (!complete || !BatchPolicy.canTransition(batch.state, BatchState.ACTIVE)) {
                fail(BatchCommandFailure.ACTIVATION_INCOMPLETE)
            }
            repository.activateBatch(batch).snapshot()
        }

    /**
     * `pauseBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = pauseBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun pauseBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> =
        transitionBatch(command, BatchState.PAUSED, OP_PAUSE_BATCH, "BATCH_PAUSED")

    /**
     * `resumeBatch`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = resumeBatch(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    override fun resumeBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> =
        transitionBatch(command, BatchState.ACTIVE, OP_RESUME_BATCH, "BATCH_RESUMED")

    /**
     * transitionCampaign은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param target 업무 처리에 필요한 target 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun transitionCampaign(
        command: CampaignRevisionCommand,
        target: CampaignState,
        operation: String,
        outcome: String,
    ): MutationResult<CampaignSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = operation,
            fingerprint = command.fingerprint(operation),
            effectId = command.campaignId,
            status = HTTP_OK,
            outcome = outcome,
            lane = MutationLane.OPERATOR,
        ) { connection ->
            /**
             * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val campaign = repository.lockCampaignForUpdate(command.tenantId, command.campaignId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireRevision(campaign.revision, command.expectedRevision)
            if (!CampaignPolicy.canTransition(campaign.state, target)) fail(BatchCommandFailure.INVALID_STATE)
            repository.updateCampaign(campaign.copy(state = target), campaign.revision).snapshot()
        }

    /**
     * transitionBatch은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param target 업무 처리에 필요한 target 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun transitionBatch(
        command: BatchRevisionCommand,
        target: BatchState,
        operation: String,
        outcome: String,
    ): MutationResult<BatchSnapshot> =
        executeIdempotent(
            tenantId = command.tenantId,
            rawKey = command.idempotencyKey,
            operation = operation,
            fingerprint = command.fingerprint(operation),
            effectId = command.batchId,
            status = HTTP_OK,
            outcome = outcome,
            lane = MutationLane.OPERATOR,
        ) { connection ->
            repository.lockCampaignForShare(command.tenantId, command.campaignId)
            /**
             * `batch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val batch = repository.lockBatchForUpdate(command.tenantId, command.batchId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireBatchScope(batch, command.campaignId)
            requireRevision(batch.revision, command.expectedRevision)
            if (!BatchPolicy.canTransition(batch.state, target)) fail(BatchCommandFailure.INVALID_STATE)
            repository.updateBatchState(batch, target).snapshot()
        }

    /**
     * ingest은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param idempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param manifestDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedSource 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun ingest(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        expectedRevision: Long,
        idempotencyKey: String,
        fingerprint: CommandFingerprint,
        manifestDigest: DigestValue,
        prepared: PreparedChunk,
        expectedSource: BatchSourceKind,
    ): MutationResult<BatchSnapshot> =
        executeIdempotent(
            tenantId = tenantId,
            rawKey = idempotencyKey,
            operation = if (expectedSource == BatchSourceKind.IMPORTED) OP_IMPORT_CHUNK else OP_GENERATE_CHUNK,
            fingerprint = fingerprint,
            effectId = batchId,
            status = HTTP_OK,
            outcome = "BATCH_CHECKPOINTED",
            lane = MutationLane.WORKER,
            failureMapper = ::classifyIntegrityFailure,
            terminalEffect = { _, failure ->
                markTerminalBatchFailure(
                    tenantId,
                    campaignId,
                    batchId,
                    failure,
                    manifestDigest,
                    prepared.firstOrdinal,
                )
            },
        ) {
            repository.lockCampaignForShare(tenantId, campaignId)
            /**
             * `batch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val batch = repository.lockBatchForUpdate(tenantId, batchId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireBatchScope(batch, campaignId)
            requireRevision(batch.revision, expectedRevision)
            if (batch.sourceKind != expectedSource.name) fail(BatchCommandFailure.INVALID_STATE)
            commitPrepared(batch, manifestDigest, prepared).snapshot()
        }

    /**
     * commitPrepared은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param manifestDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun commitPrepared(
        batch: BatchRecord,
        manifestDigest: DigestValue,
        prepared: PreparedChunk,
    ): BatchRecord {
        if (!MessageDigest.isEqual(batch.provenanceDigest.copyBytes(), manifestDigest.copyBytes())) {
            fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        }
        val chunkEnd = prepared.firstOrdinal + prepared.totalCount
        if (chunkEnd > batch.expectedCount) fail(BatchCommandFailure.ORDINAL_GAP)
        if (prepared.firstOrdinal < batch.nextSourceOrdinal) return replayCommitted(batch, prepared)
        if (prepared.firstOrdinal > batch.nextSourceOrdinal) fail(BatchCommandFailure.ORDINAL_GAP)
        if (batch.state != BatchState.STAGING) fail(BatchCommandFailure.INVALID_STATE)

        prepared.rejections.firstOrNull()?.let { rejection ->
            throw PreparedChunkRejectedException(
                evidenceCode = rejection.evidenceCode(),
                rejectedCount = prepared.totalCount.toLong(),
                nextSourceOrdinal = chunkEnd,
            )
        }
        repository.insertPreparedEntries(prepared.entries)
        val nextCheckpoint = prepared.entries
            .fold(batch.checkpointDigest ?: initialCheckpoint(batch)) { previous, entry ->
                advanceCheckpoint(previous, entry.sourceOrdinal, entry.stableDedupDigest)
            }
        return repository.updateBatchCheckpoint(
            batch = batch,
            nextSourceOrdinal = chunkEnd,
            acceptedCount = batch.acceptedCount + prepared.entries.size,
            rejectedCount = batch.rejectedCount,
            checkpointDigest = nextCheckpoint,
            state = BatchState.STAGING,
            lastFailureCode = null,
        )
    }

    /**
     * replayCommitted은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun replayCommitted(batch: BatchRecord, prepared: PreparedChunk): BatchRecord {
        if (prepared.rejections.isNotEmpty() || prepared.firstOrdinal + prepared.totalCount > batch.nextSourceOrdinal) {
            fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        }
        if (!committedReplayExact(batch, prepared)) fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        return batch
    }

    /**
     * committedReplayExact은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun committedReplayExact(batch: BatchRecord, prepared: PreparedChunk): Boolean {
        val stored = repository.committedOrdinalDigests(
            batch.tenantId,
            batch.batchId,
            prepared.firstOrdinal,
            prepared.totalCount,
        )
        return stored.size == prepared.entries.size &&
                stored.zip(prepared.entries).all { (authority, candidate) ->
                    authority.sourceOrdinal == candidate.sourceOrdinal &&
                            MessageDigest.isEqual(
                                authority.stableDedupDigest.copyBytes(),
                                candidate.stableDedupDigest.copyBytes()
                            )
                }
    }

    /**
     * prepare은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param firstOrdinal 업무 처리에 필요한 firstOrdinal 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param codes 업무 처리에 필요한 codes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun prepare(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        firstOrdinal: Long,
        codes: List<String>,
    ): PreparedChunk {
        val entries = ArrayList<PreparedVoucherEntryRecord>(codes.size)
        val rejections = ArrayList<RejectedVoucherEntryRecord>()
        codes.forEachIndexed { index, rawCode ->
            val ordinal = firstOrdinal + index
            val payloadDigest =
                DigestValue.of(digests.audit(tenantId, INVALID_CODE_AUDIT_OPERATION, rawCode).copyBytes())

            try {
                val code = CanonicalVoucherCode.of(rawCode)
                val stable = digests.stableDedup(tenantId, code)
                val entryId = Uuid.V7.nextId()
                val encrypted = crypto.encrypt(EntryIdentity(tenantId, campaignId, batchId, entryId, ordinal), code)
                entries += PreparedVoucherEntryRecord(
                    tenantId = tenantId,
                    campaignId = campaignId,
                    batchId = batchId,
                    entryId = entryId,
                    sourceOrdinal = ordinal,
                    stableDedupDigest = DigestValue.of(stable.copyBytes()),
                    stableDedupKeyVersion = stable.keyVersion,
                    codeCiphertext = DigestValue.of(encrypted.copyCodeCiphertext()),
                    codeNonce = DigestValue.of(encrypted.copyCodeNonce()),
                    wrappedDek = DigestValue.of(encrypted.copyWrappedDek()),
                    wrapNonce = DigestValue.of(encrypted.copyWrapNonce()),
                    kekVersion = encrypted.kekVersion,
                )
            } catch (_: IllegalArgumentException) {
                rejections += RejectedVoucherEntryRecord(ordinal, "INVALID_CODE", payloadDigest)
            } catch (_: VoucherCryptoException) {
                rejections += RejectedVoucherEntryRecord(ordinal, "CRYPTO_FAILURE", payloadDigest)
            }
        }
        return PreparedChunk(firstOrdinal, codes.size, entries, rejections)
    }

    /**
     * canonicalCheckpoint은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param count 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun canonicalCheckpoint(batch: BatchRecord, count: Long): DigestValue {
        require(count in 0..MAX_BATCH_ENTRIES)
        var checkpoint = initialCheckpoint(batch)
        var firstOrdinal = 0L
        while (firstOrdinal < count) {
            val windowCount = minOf(CHECKPOINT_WINDOW_SIZE.toLong(), count - firstOrdinal).toInt()
            val authority = repository.committedOrdinalDigests(
                batch.tenantId,
                batch.batchId,
                firstOrdinal,
                windowCount,
            )
            if (authority.size != windowCount || authority.withIndex().any { (index, item) ->
                    item.sourceOrdinal != firstOrdinal + index
                }
            ) {
                fail(BatchCommandFailure.ACTIVATION_INCOMPLETE)
            }
            authority.forEach { item ->
                checkpoint = advanceCheckpoint(checkpoint, item.sourceOrdinal, item.stableDedupDigest)
            }
            firstOrdinal += windowCount
        }
        return checkpoint
    }

    /**
     * initialCheckpoint은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun initialCheckpoint(batch: BatchRecord): DigestValue {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(FINAL_CHECKPOINT_DOMAIN.toUtf8Bytes())
            update(batch.provenanceDigest.copyBytes())
            update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(batch.expectedCount).array())
        }
        return DigestValue.of(digest.digest())
    }

    /**
     * advanceCheckpoint은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param previous 업무 처리에 필요한 previous 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param ordinal 업무 처리에 필요한 ordinal 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param stableDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    private fun advanceCheckpoint(previous: DigestValue, ordinal: Long, stableDigest: DigestValue): DigestValue {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHECKPOINT_STEP_DOMAIN.toUtf8Bytes())
        digest.update(previous.copyBytes())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(ordinal).array())
        digest.update(stableDigest.copyBytes())
        return DigestValue.of(digest.digest())
    }

    /**
     * executeIdempotent은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param fingerprint 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failureMapper 업무 처리에 필요한 failureMapper 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param terminalEffect 업무 처리에 필요한 terminalEffect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param createRecovery 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param effect 업무 처리에 필요한 effect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> executeIdempotent(
        tenantId: String,
        rawKey: String,
        operation: String,
        fingerprint: CommandFingerprint,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        failureMapper: (RuntimeException) -> RuntimeException = { it },
        terminalEffect: (Connection, BatchCommandException) -> Unit = { _, _ -> },
        createRecovery: ((Connection) -> T?)? = null,
        effect: (Connection) -> T,
    ): MutationResult<T> {
        requireIdempotencyKey(rawKey)
        /**
         * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val scope = CommandScope(tenantId, operation)

        return when (val decision = transaction(lane) { idempotency.acquire(scope, rawKey, fingerprint) }) {
            is IdempotencyDecision.Execute    -> executeOwned(
                decision.owner,
                effectId,
                status,
                outcome,
                lane,
                failureMapper,
                terminalEffect,
                createRecovery,
                effect,
            )
            is IdempotencyDecision.Replay     -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired    -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(BatchCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    /**
     * executeOwned은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failureMapper 업무 처리에 필요한 failureMapper 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param terminalEffect 업무 처리에 필요한 terminalEffect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param createRecovery 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param effect 업무 처리에 필요한 effect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> executeOwned(
        owner: IdempotencyOwner,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        failureMapper: (RuntimeException) -> RuntimeException,
        terminalEffect: (Connection, BatchCommandException) -> Unit,
        createRecovery: ((Connection) -> T?)?,
        effect: (Connection) -> T,
    ): MutationResult<T> =
        try {
            /**
             * `applied`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val applied = transaction(lane) {
                idempotency.lockOwnerForExecution(owner)
                /**
                 * `value`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val value = effect(currentConnection())
                idempotency.finalize(
                    owner,
                    SafeResponseDescriptor.success(status, outcome, effectId, aggregateRevision(value as Any)),
                    EffectReference.effect(effectId),
                )
                value
            }
            MutationResult.Applied(applied)
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            /**
             * `mapped`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val mapped = failureMapper(failure)
            if (mapped is BatchCommandException && mapped.reason == BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT && createRecovery != null) {
                recoverCreate(
                    owner,
                    effectId,
                    status,
                    outcome,
                    lane,
                    createRecovery
                )?.let { return MutationResult.Applied(it) }
            }
            when {
                mapped is BatchCommandException && mapped.reason.isTerminal() -> finalizeTerminal(
                    owner,
                    lane,
                    mapped,
                    terminalEffect
                )
                mapped.isRetryableMutationFailure()                           -> releaseRetryable(owner, lane)
            }
            throw mapped
        }

    /**
     * recoverCreate은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param effectId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param recovery 업무 처리에 필요한 recovery 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> recoverCreate(
        owner: IdempotencyOwner,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        recovery: (Connection) -> T?,
    ): T? = transaction(lane) {
        idempotency.lockOwnerForExecution(owner)
        val recovered = recovery(currentConnection()) ?: return@transaction null
        idempotency.finalize(
            owner = owner,
            descriptor = SafeResponseDescriptor.success(status, outcome, effectId, aggregateRevision(recovered)),
            effect = EffectReference.effect(effectId),
        )
        recovered
    }

    /**
     * aggregateRevision은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun aggregateRevision(value: Any): Long = when (value) {
        is CampaignSnapshot -> value.revision
        is BatchSnapshot -> value.revision
        else             -> error("idempotent mutation result requires an aggregate revision")
    }

    /**
     * finalizeTerminal은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param terminalEffect 업무 처리에 필요한 terminalEffect 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun finalizeTerminal(
        owner: IdempotencyOwner,
        lane: MutationLane,
        failure: BatchCommandException,
        terminalEffect: (Connection, BatchCommandException) -> Unit,
    ) {
        transaction(lane) {
            idempotency.lockOwnerForExecution(owner)
            terminalEffect(currentConnection(), failure)
            idempotency.finalize(
                owner = owner,
                descriptor = SafeResponseDescriptor.terminal(HTTP_CONFLICT, VoucherPoolErrorCode.BATCH_FAILED_TERMINAL),
                effect = EffectReference.terminal(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL),
            )
        }
    }

    /**
     * releaseRetryable은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun releaseRetryable(owner: IdempotencyOwner, lane: MutationLane) {
        releaseRetryableOwner {
            transaction(lane) { idempotency.releaseRetryable(owner) }
        }
    }

    /**
     * transaction은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param lane 업무 처리에 필요한 lane 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun <T> transaction(lane: MutationLane, block: () -> T): T = when (lane) {
        MutationLane.OPERATOR -> executor.operatorTransaction(block)
        MutationLane.WORKER -> executor.workerTransaction(block)
    }

    /**
     * markTerminalBatchFailure은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param batchId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedManifest 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param expectedFirstOrdinal 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param missingBatchFactory 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun markTerminalBatchFailure(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        failure: BatchCommandException,
        expectedManifest: DigestValue,
        expectedFirstOrdinal: Long,
        missingBatchFactory: ((CampaignRecord) -> BatchRecord)? = null,
    ) {
        if (failure.reason !in setOf(
                BatchCommandFailure.DUPLICATE_CODE,
                BatchCommandFailure.VALIDATION_REJECTED
            )) return
        /**
         * `campaign`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val campaign = repository.lockCampaignForShare(tenantId, campaignId)
        /**
         * `requestedBatch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val requestedBatch = missingBatchFactory?.invoke(campaign)
        /**
         * `existingBatch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val existingBatch = repository.lockBatchForUpdate(tenantId, batchId)
        /**
         * `lockedBatch`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val lockedBatch = when {
            existingBatch == null -> requestedBatch?.let { repository.createBatch(it) }
            !matchesTerminalAuthority(
                existingBatch,
                campaignId,
                expectedManifest,
                expectedFirstOrdinal,
                requestedBatch,
            )    -> return
            else -> existingBatch
        }

        lockedBatch?.let { batch ->
            if (batch.state == BatchState.STAGING) {
                /**
                 * `rejected`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                 *
                 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                 */
                val rejected = failure as? PreparedChunkRejectedException
                check(rejected == null || rejected.nextSourceOrdinal == batch.nextSourceOrdinal + rejected.rejectedCount)
                repository.markBatchTerminalFailure(
                    batch,
                    rejected?.rejectedCount ?: 0L,
                    rejected?.evidenceCode ?: "DUPLICATE_CODE",
                )
            }
        }
    }

    /**
     * matchesTerminalAuthority은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param existingBatch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedManifest 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param expectedFirstOrdinal 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param requestedBatch 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun matchesTerminalAuthority(
        existingBatch: BatchRecord,
        campaignId: UUID,
        expectedManifest: DigestValue,
        expectedFirstOrdinal: Long,
        requestedBatch: BatchRecord?,
    ): Boolean {
        if (existingBatch.campaignId != campaignId) return false
        if (!existingBatch.provenanceDigest.secureEquals(expectedManifest)) return false
        if (existingBatch.nextSourceOrdinal != expectedFirstOrdinal) return false
        if (requestedBatch == null) return true
        if (!existingBatch.sameCreateAuthority(requestedBatch) || !existingBatch.hasNoProgress()) return false
        return repository.batchOrdinalCoverage(
            existingBatch.tenantId,
            existingBatch.batchId,
        ).entryCount == 0L
    }

    /**
     * classifyIntegrityFailure은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun classifyIntegrityFailure(failure: RuntimeException): RuntimeException {
        if (failure.sqlState() != POSTGRES_UNIQUE_VIOLATION) return failure
        return when (failure.postgresConstraintName()) {
            in STABLE_DEDUP_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.DUPLICATE_CODE)
            in RETRYABLE_COLLISION_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION)
            in CREATE_CONSTRAINTS       -> BatchCommandException(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
            in ORDINAL_CONSTRAINTS      -> BatchCommandException(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
            else                        -> failure
        }
    }

    /**
     * currentConnection은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "campaign batch commands require an active VoucherPoolJdbcExecutor transaction"
    }.connection.connection as Connection

    /**
     * requireBatchScope은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param batch 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun requireBatchScope(batch: BatchRecord, campaignId: UUID) {
        if (batch.campaignId != campaignId) fail(BatchCommandFailure.SCOPE_NOT_FOUND)
    }

    /**
     * requireRevision은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actual 업무 처리에 필요한 actual 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requireRevision(actual: Long, expected: Long) {
        if (actual != expected) fail(BatchCommandFailure.STALE_REVISION)
    }

    /**
     * snapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun CampaignRecord.snapshot(): CampaignSnapshot =
        CampaignSnapshot(tenantId, campaignId, state, policyVersion, revision)

    /**
     * snapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun BatchRecord.snapshot(): BatchSnapshot =
        BatchSnapshot(
            tenantId,
            batchId,
            campaignId,
            state,
            nextSourceOrdinal,
            expectedCount,
            acceptedCount,
            rejectedCount,
            checkpointDigest,
            lastFailureCode,
            revision,
        )

    /**
     * requestedCampaign은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun requestedCampaign(command: CreateCampaignCommand): CampaignRecord =
        CampaignRecord(
            tenantId = command.tenantId,
            campaignId = command.campaignId,
            state = CampaignState.DRAFT,
            startsAt = command.startsAt,
            endsAt = command.endsAt,
            perUserLimit = command.policy.perUserLimit,
            reservationTtlSeconds = command.policy.reservationTtl.inWholeSeconds,
            allocationTtlSeconds = command.policy.allocationTtl.inWholeSeconds,
            replacementAllowance = command.policy.replacementAllowance,
            userIdentityKeyVersion = digests.currentUserIdentityKeyVersion,
            policyVersion = 1,
            revision = 0,
        )

    /**
     * requestedBatch은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param existingPolicyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun requestedBatch(command: CreateImportBatchCommand, existingPolicyVersion: Long): BatchRecord =
        BatchRecord(
            tenantId = command.tenantId,
            batchId = command.batchId,
            campaignId = command.campaignId,
            state = BatchState.STAGING,
            sourceKind = command.sourceKind.name,
            provenanceDigest = command.manifestDigest,
            requestFingerprint = command.requestFingerprint,
            policyVersion = existingPolicyVersion,
            activatesAt = command.activatesAt,
            expiresAt = command.expiresAt,
            expectedCount = command.expectedCount,
            revision = 0,
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun CreateCampaignCommand.fingerprint(): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_CREATE_CAMPAIGN,
            mapOf(
                "campaignId" to campaignId.toString(),
                "startsAt" to startsAt.toString(),
                "endsAt" to endsAt.toString(),
                "perUserLimit" to policy.perUserLimit.toString(),
                "reservationTtlSeconds" to policy.reservationTtl.inWholeSeconds.toString(),
                "allocationTtlSeconds" to policy.allocationTtl.inWholeSeconds.toString(),
                "replacementAllowance" to policy.replacementAllowance.toString(),
            ),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun UpdateCampaignPolicyCommand.fingerprint(): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_UPDATE_CAMPAIGN_POLICY,
            mapOf(
                "campaignId" to campaignId.toString(),
                "expectedRevision" to expectedRevision.toString(),
                "perUserLimit" to policy.perUserLimit.toString(),
                "reservationTtlSeconds" to policy.reservationTtl.inWholeSeconds.toString(),
                "allocationTtlSeconds" to policy.allocationTtl.inWholeSeconds.toString(),
                "replacementAllowance" to policy.replacementAllowance.toString(),
            ),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun CampaignRevisionCommand.fingerprint(operation: String): CommandFingerprint =
        VoucherPoolFingerprint.command(
            operation,
            mapOf("campaignId" to campaignId.toString(), "expectedRevision" to expectedRevision.toString()),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun CreateImportBatchCommand.fingerprint(prepared: PreparedChunk): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_CREATE_BATCH,
            mapOf(
                "batchId" to batchId.toString(),
                "campaignId" to campaignId.toString(),
                "sourceKind" to sourceKind.name,
                "manifestDigest" to manifestDigest.copyBytes().toHex(),
                "requestFingerprint" to requestFingerprint.copyBytes().toHex(),
                "expectedCount" to expectedCount.toString(),
                "activatesAt" to activatesAt.toString(),
                "expiresAt" to expiresAt?.toString(),
                "initialChunk" to prepared.requestDigest(),
            ),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param prepared 업무 처리에 필요한 prepared 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun ImportChunkCommand.fingerprint(prepared: PreparedChunk): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_IMPORT_CHUNK,
            mapOf(
                "batchId" to batchId.toString(),
                "campaignId" to campaignId.toString(),
                "firstOrdinal" to firstOrdinal.toString(),
                "manifestDigest" to manifestDigest.copyBytes().toHex(),
                "chunk" to prepared.requestDigest(),
                "expectedRevision" to expectedRevision.toString(),
            ),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun GenerateChunkCommand.fingerprint(): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_GENERATE_CHUNK,
            mapOf(
                "batchId" to batchId.toString(),
                "campaignId" to campaignId.toString(),
                "firstOrdinal" to firstOrdinal.toString(),
                "manifestDigest" to manifestDigest.copyBytes().toHex(),
                "count" to count.toString(),
                "expectedRevision" to expectedRevision.toString(),
            ),
        )

    /**
     * fingerprint은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun BatchRevisionCommand.fingerprint(operation: String = OP_ACTIVATE_BATCH): CommandFingerprint =
        VoucherPoolFingerprint.command(
            operation,
            mapOf(
                "batchId" to batchId.toString(),
                "campaignId" to campaignId.toString(),
                "expectedRevision" to expectedRevision.toString(),
            ),
        )

    /**
     * requestDigest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun PreparedChunk.requestDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHUNK_FINGERPRINT_DOMAIN.toUtf8Bytes())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(firstOrdinal).array())
        entries.forEach {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it.sourceOrdinal).array())
            digest.update(it.stableDedupDigest.copyBytes())
        }
        rejections.forEach {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it.sourceOrdinal).array())
            digest.update(it.payloadDigest.copyBytes())
        }
        return digest.digest().toHex()
    }
/**
 * PreparedChunk은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property firstOrdinal 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property totalCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property entries 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property rejections 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    private data class PreparedChunk(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val firstOrdinal: Long,
        /**
         * `totalCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val totalCount: Int,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val entries: List<PreparedVoucherEntryRecord>,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val rejections: List<RejectedVoucherEntryRecord>,
    )

    /**
     * `MutationLane`은 업무 분기용 제한된 값 집합입니다.
     * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
     */
    private enum class MutationLane {
        /**
         * `MutationLane.OPERATOR`은 `operator` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
         * 사용합니다.
         */
        OPERATOR,
        /**
         * `MutationLane.WORKER`은 `worker` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
         * 사용합니다.
         */
        WORKER
    }
}

/**
 * evidenceCode은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun RejectedVoucherEntryRecord.evidenceCode(): String =
    "$reasonCode@$sourceOrdinal:${payloadDigest.copyBytes().take(EVIDENCE_DIGEST_BYTES).toByteArray().toHex()}"
        .take(MAX_FAILURE_CODE_LENGTH)

/**
 * sqlState은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Throwable.sqlState(): String? {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val pending = ArrayDeque<Throwable>().apply { add(this@sqlState) }
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

    repeat(MAX_EXCEPTION_CHAIN) {
        val candidate = pending.pollFirst() ?: return null
        if (!visited.add(candidate)) return@repeat
        if (candidate is SQLException && candidate.sqlState != null) return candidate.sqlState
        candidate.cause?.let(pending::addLast)
        if (candidate is SQLException) candidate.nextException?.let(pending::addLast)
    }
    return null
}

/**
 * postgresConstraintName은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun Throwable.postgresConstraintName(): String? {
    val pending = ArrayDeque<Throwable>().apply { add(this@postgresConstraintName) }
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

    repeat(MAX_EXCEPTION_CHAIN) {
        val candidate = pending.pollFirst() ?: return null
        if (!visited.add(candidate)) return@repeat
        if (candidate is PSQLException) {
            val metadata: ServerErrorMessage? = candidate.serverErrorMessage
            val constraintName: String? = metadata?.constraint
            if (constraintName != null) return constraintName
        }
        candidate.cause?.let(pending::addLast)
        if (candidate is SQLException) candidate.nextException?.let(pending::addLast)
    }
    return null
}

/**
 * isRetryableMutationFailure은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게
 * 재처리합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Throwable.isRetryableMutationFailure(): Boolean =
    (this is BatchCommandException && reason in RELEASE_COMMAND_FAILURES) ||
            sqlState() in RETRYABLE_SQL_STATES ||
            this::class.simpleName in RETRYABLE_FAILURE_TYPES

/**
 * isTerminal은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun BatchCommandFailure.isTerminal(): Boolean = this in TERMINAL_COMMAND_FAILURES

/**
 * sameCreateAuthority은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun CampaignRecord.sameCreateAuthority(other: CampaignRecord): Boolean =
    tenantId == other.tenantId && campaignId == other.campaignId && state == CampaignState.DRAFT &&
            startsAt.sameDatabaseInstant(other.startsAt) && endsAt.sameDatabaseInstant(other.endsAt) &&
            perUserLimit == other.perUserLimit &&
            reservationTtlSeconds == other.reservationTtlSeconds && allocationTtlSeconds == other.allocationTtlSeconds &&
            replacementAllowance == other.replacementAllowance

/**
 * sameCreateAuthority은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun BatchRecord.sameCreateAuthority(other: BatchRecord): Boolean =
    tenantId == other.tenantId && batchId == other.batchId && campaignId == other.campaignId &&
            sourceKind == other.sourceKind && provenanceDigest.secureEquals(other.provenanceDigest) &&
            requestFingerprint.secureEquals(other.requestFingerprint) && policyVersion == other.policyVersion &&
            activatesAt.sameDatabaseInstant(other.activatesAt) && expiresAt.sameDatabaseInstant(other.expiresAt) &&
            expectedCount == other.expectedCount

/**
 * hasNoProgress은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun BatchRecord.hasNoProgress(): Boolean =
    acceptedCount == 0L && rejectedCount == 0L && checkpointDigest == null && lastFailureCode == null

/**
 * sameDatabaseInstant은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun Instant?.sameDatabaseInstant(other: Instant?): Boolean =
    this?.toPostgresInstant() == other?.toPostgresInstant()

/**
 * toPostgresInstant은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Instant.toPostgresInstant(): Instant {
    val roundedMicros = (nano.toLong() + NANOS_PER_MICRO / 2) / NANOS_PER_MICRO
    val normalizedSeconds = epochSecond + roundedMicros / MICROS_PER_SECOND
    val microsOfSecond = roundedMicros % MICROS_PER_SECOND
    return Instant.ofEpochSecond(normalizedSeconds, microsOfSecond * NANOS_PER_MICRO)
}

/**
 * secureEquals은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun DigestValue.secureEquals(other: DigestValue): Boolean =
    MessageDigest.isEqual(copyBytes(), other.copyBytes())

/**
 * toHex은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

/**
 * validateChunkPayload은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param codes 업무 처리에 필요한 codes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param allowEmpty 업무 처리에 필요한 allowEmpty 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun validateChunkPayload(codes: List<String>, allowEmpty: Boolean = false) {
    if (!allowEmpty) require(codes.isNotEmpty()) { "chunk must not be empty" }
    require(codes.size <= MAX_CHUNK_ENTRIES) { "chunk must contain at most $MAX_CHUNK_ENTRIES entries" }
    val payloadBytes = codes.sumOf { it.toUtf8Bytes().size.toLong() }
    require(payloadBytes <= MAX_JSON_PAYLOAD_BYTES) { "chunk payload exceeds $MAX_JSON_PAYLOAD_BYTES bytes" }
}

/**
 * requireTenant은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 */
private fun requireTenant(tenantId: String) {
    require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH) { "tenantId is invalid" }
}

/**
 * requireIdempotencyKey은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param rawKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
 */
private fun requireIdempotencyKey(rawKey: String) {
    require(rawKey.length in MIN_IDEMPOTENCY_KEY_LENGTH..MAX_IDEMPOTENCY_KEY_LENGTH) { "idempotency key is invalid" }
    require(rawKey.none(Char::isISOControl)) { "idempotency key is invalid" }
}

/**
 * fail은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun fail(reason: BatchCommandFailure): Nothing = throw BatchCommandException(reason)

/**
 * `MAX_TENANT_LENGTH`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
 *
 * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
 */
private const val MAX_TENANT_LENGTH = 64
/**
 * `MIN_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
/**
 * `MAX_IDEMPOTENCY_KEY_LENGTH`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
/**
 * `MAX_CHUNK_ENTRIES`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
 *
 * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
 */
private const val MAX_CHUNK_ENTRIES = 500
/**
 * `MAX_BATCH_ENTRIES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val MAX_BATCH_ENTRIES = 10_000L
/**
 * `NANOS_PER_MICRO`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val NANOS_PER_MICRO = 1_000L
/**
 * `MICROS_PER_SECOND`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MICROS_PER_SECOND = 1_000_000L
/**
 * `MAX_JSON_PAYLOAD_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val MAX_JSON_PAYLOAD_BYTES = 4L * 1_024 * 1_024
/**
 * `GENERATED_ENTROPY_BYTES`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val GENERATED_ENTROPY_BYTES = 16
/**
 * `EVIDENCE_DIGEST_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val EVIDENCE_DIGEST_BYTES = 8
/**
 * `MAX_FAILURE_CODE_LENGTH`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val MAX_FAILURE_CODE_LENGTH = 64
/**
 * `MAX_EXCEPTION_CHAIN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_EXCEPTION_CHAIN = 32
/**
 * `POSTGRES_UNIQUE_VIOLATION`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val POSTGRES_UNIQUE_VIOLATION = "23505"
/**
 * `FINAL_CHECKPOINT_DOMAIN`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val FINAL_CHECKPOINT_DOMAIN = "voucher-pool-batch-final-checkpoint-v1"
/**
 * `CHECKPOINT_STEP_DOMAIN`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val CHECKPOINT_STEP_DOMAIN = "voucher-pool-batch-checkpoint-step-v1"
/**
 * `CHECKPOINT_WINDOW_SIZE`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val CHECKPOINT_WINDOW_SIZE = 500
/**
 * `CHUNK_FINGERPRINT_DOMAIN`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private const val CHUNK_FINGERPRINT_DOMAIN = "voucher-pool-batch-chunk-fingerprint-v1"
/**
 * `INVALID_CODE_AUDIT_OPERATION`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private const val INVALID_CODE_AUDIT_OPERATION = "voucher-batch-invalid-code"
/**
 * `HTTP_OK`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HTTP_OK = 200
/**
 * `HTTP_CREATED`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val HTTP_CREATED = 201
/**
 * `HTTP_CONFLICT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val HTTP_CONFLICT = 409
/**
 * `OP_CREATE_CAMPAIGN`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_CREATE_CAMPAIGN = "campaign-create"
/**
 * `OP_UPDATE_CAMPAIGN_POLICY`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_UPDATE_CAMPAIGN_POLICY = "campaign-policy-update"
/**
 * `OP_ACTIVATE_CAMPAIGN`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_ACTIVATE_CAMPAIGN = "campaign-activate"
/**
 * `OP_PAUSE_CAMPAIGN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val OP_PAUSE_CAMPAIGN = "campaign-pause"
/**
 * `OP_RESUME_CAMPAIGN`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val OP_RESUME_CAMPAIGN = "campaign-resume"
/**
 * `OP_CREATE_BATCH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_CREATE_BATCH = "batch-create"
/**
 * `OP_IMPORT_CHUNK`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val OP_IMPORT_CHUNK = "batch-import-chunk"
/**
 * `OP_GENERATE_CHUNK`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_GENERATE_CHUNK = "batch-generate-chunk"
/**
 * `OP_ACTIVATE_BATCH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_ACTIVATE_BATCH = "batch-activate"
/**
 * `OP_PAUSE_BATCH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_PAUSE_BATCH = "batch-pause"
/**
 * `OP_RESUME_BATCH`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OP_RESUME_BATCH = "batch-resume"
/**
 * `STABLE_DEDUP_CONSTRAINTS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val STABLE_DEDUP_CONSTRAINTS: Set<String> = setOf(
    "pk_voucher_pool_code_dedup",
    "uq_voucher_pool_dedup",
    "uq_voucher_pool_entry_stable_dedup",
)
/**
 * `RETRYABLE_COLLISION_CONSTRAINTS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val RETRYABLE_COLLISION_CONSTRAINTS = setOf(
    "pk_voucher_pool_entries",
    "uq_voucher_pool_entry_identity",
    "uq_voucher_pool_entry_code_nonce",
    "uq_voucher_pool_entry_wrap_nonce",
)
/**
 * `CREATE_CONSTRAINTS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private val CREATE_CONSTRAINTS = setOf(
    "pk_voucher_pool_campaigns",
    "pk_voucher_pool_batches",
    "uq_voucher_pool_batch_identity",
)
/**
 * `ORDINAL_CONSTRAINTS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private val ORDINAL_CONSTRAINTS: Set<String> = setOf("uq_voucher_pool_entry_batch_ordinal")
/**
 * `RETRYABLE_SQL_STATES`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private val RETRYABLE_SQL_STATES: Set<String> = setOf("40001", "40P01", "55P03", "57014")
/**
 * `RETRYABLE_FAILURE_TYPES`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
 *
 * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
 */
private val RETRYABLE_FAILURE_TYPES: Set<String> = setOf("PoolBusyException", "VoucherPoolJdbcTimeoutException")
/**
 * `TERMINAL_COMMAND_FAILURES`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
private val TERMINAL_COMMAND_FAILURES: Set<BatchCommandFailure> = setOf(
    BatchCommandFailure.DUPLICATE_CODE,
    BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT,
    BatchCommandFailure.VALIDATION_REJECTED,
)
/**
 * `RELEASE_COMMAND_FAILURES`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
private val RELEASE_COMMAND_FAILURES: Set<BatchCommandFailure> = setOf(
    BatchCommandFailure.SCOPE_NOT_FOUND,
    BatchCommandFailure.STALE_REVISION,
    BatchCommandFailure.INVALID_STATE,
    BatchCommandFailure.ORDINAL_GAP,
    BatchCommandFailure.ACTIVATION_INCOMPLETE,
    BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION,
)
