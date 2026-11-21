package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Instant

/**
 * `FIRST_REBUILD_GENERATION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
internal const val FIRST_REBUILD_GENERATION = 1L
/**
 * `FIRST_REBUILD_FENCING_TOKEN`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
 *
 * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
 */
internal const val FIRST_REBUILD_FENCING_TOKEN = 1L
/**
 * `FIRST_ACTIVE_POINTER_REVISION`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
internal const val FIRST_ACTIVE_POINTER_REVISION = 1L

/**
 * `ProjectionGenerationState`은 수명주기 상태입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class ProjectionGenerationState {
    /**
     * `ProjectionGenerationState.BUILDING`은 projection·artifact를 생성 중인 단계입니다.
     */
    BUILDING,
    /**
     * `ProjectionGenerationState.VALIDATING`은 `validating` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를
     * 확인합니다.
     */
    VALIDATING,
    /**
     * `ProjectionGenerationState.ACTIVE`은 해당 기능이나 정책이 현재 요청 처리에 적용되는 값입니다.
     */
    ACTIVE,
    /**
     * `ProjectionGenerationState.CANCELLING`은 `cancelling` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를
     * 확인합니다.
     */
    CANCELLING,
    /**
     * `ProjectionGenerationState.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED,
    /**
     * `ProjectionGenerationState.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED,
    /**
     * `ProjectionGenerationState.RETIRED`은 더 이상 신규 요청에 사용하지 않고 이력만 보존하는 값입니다.
     */
    RETIRED,
}

@ConsistentCopyVisibility
/**
 * ProjectionGeneration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class ProjectionGeneration private constructor(
    /**
     * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ProjectionKey,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: ProjectionGenerationState,
    /**
     * `progress`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val progress: ProjectionGenerationProgress,
    /**
     * `canonicalDigest`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val canonicalDigest: ReceiptDigest?,
    /**
     * `retryableFailure`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryableFailure: Boolean,
): Serializable {
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */

    val targetPosition: Long get() = progress.targetPosition
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */

    val currentPosition: Long get() = progress.currentPosition
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */

    val fencingToken: Long get() = progress.fencingToken
    /**
     * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
     */

    val cancellationRevision: Long get() = progress.cancellationRevision

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
         * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
         * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param progress 업무 처리에 필요한 progress 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param canonicalDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param retryableFailure 업무 처리에 필요한 retryableFailure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        operator fun invoke(
            key: ProjectionKey,
            state: ProjectionGenerationState,
            progress: ProjectionGenerationProgress,
            canonicalDigest: ReceiptDigest?,
            retryableFailure: Boolean,
        ): ProjectionGeneration = ProjectionGeneration(key, state, progress, canonicalDigest, retryableFailure)
    }
}

@ConsistentCopyVisibility
/**
 * ProjectionGenerationProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class ProjectionGenerationProgress private constructor(
    /**
     * `targetPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val targetPosition: Long,
    /**
     * `currentPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentPosition: Long,
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
    /**
     * `cancellationRevision`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val cancellationRevision: Long,
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
         * @param targetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param currentPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param cancellationRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            targetPosition: Long,
            currentPosition: Long,
            fencingToken: Long,
            cancellationRevision: Long,
        ): ProjectionGenerationProgress {
            targetPosition.requireGe(currentPosition, "targetPosition")
            fencingToken.requirePositiveNumber("fencingToken")
            cancellationRevision.requireZeroOrPositiveNumber("cancellationRevision")

            return ProjectionGenerationProgress(targetPosition, currentPosition, fencingToken, cancellationRevision)
        }
    }
}

@ConsistentCopyVisibility
/**
 * ActiveProjectionGeneration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class ActiveProjectionGeneration private constructor(
    /**
     * `projection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projection: String,
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Long,
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

        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            projection: String,
            generation: Long,
            revision: Long,
        ): ActiveProjectionGeneration {
            return ActiveProjectionGeneration(
                projection = projection.requireNotBlank("projection"),
                generation = generation.requirePositiveNumber("generation"),
                revision = revision.requirePositiveNumber("revision"),
            )
        }
    }
}

@ConsistentCopyVisibility
/**
 * ProjectionRebuildCursor은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class ProjectionRebuildCursor private constructor(
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
    /**
     * `cancellationRevision`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val cancellationRevision: Long,
    /**
     * `expectedPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val expectedPosition: Long,
    /**
     * `position`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val position: Long,
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
         * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param cancellationRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param expectedPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param position 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            fencingToken: Long,
            cancellationRevision: Long,
            expectedPosition: Long,
            position: Long,
        ): ProjectionRebuildCursor {
            fencingToken.requirePositiveNumber("fencingToken")
            cancellationRevision.requireZeroOrPositiveNumber("cancellationRevision")
            expectedPosition.requireZeroOrPositiveNumber("expectedPosition")
            position.requireGt(expectedPosition, "position")
            return ProjectionRebuildCursor(fencingToken, cancellationRevision, expectedPosition, position)
        }
    }
}

@ConsistentCopyVisibility
/**
 * ProjectionValidationTargetExtension은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이,
 * 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data class ProjectionValidationTargetExtension private constructor(
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
    /**
     * `cancellationRevision`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val cancellationRevision: Long,
    /**
     * `currentTargetPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentTargetPosition: Long,
    /**
     * `newTargetPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val newTargetPosition: Long,
) {
    companion object {
        /**
         * invoke은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
         * @param cancellationRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param currentTargetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         * @param newTargetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
         */
        operator fun invoke(
            fencingToken: Long,
            cancellationRevision: Long,
            currentTargetPosition: Long,
            newTargetPosition: Long,
        ): ProjectionValidationTargetExtension =
            ProjectionValidationTargetExtension(
                fencingToken = fencingToken.requirePositiveNumber("fencingToken"),
                cancellationRevision = cancellationRevision.requireZeroOrPositiveNumber("cancellationRevision"),
                currentTargetPosition = currentTargetPosition.requireZeroOrPositiveNumber("currentTargetPosition"),
                newTargetPosition = newTargetPosition.requireGt(currentTargetPosition, "newTargetPosition"),
            )
    }
}

/**
 * ProjectionActivationResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface ProjectionActivationResult {
    /**
     * Activated은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Activated: ProjectionActivationResult

    /**
     * StalePointer은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object StalePointer: ProjectionActivationResult

    /**
     * CandidateNotReady은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object CandidateNotReady: ProjectionActivationResult
}

/**
 * durable generation authority입니다. candidate가 검증된 target head에 도달한 뒤에만 active pointer가 바뀝니다.
 * 모든 caller mutation은 foreground PostgreSQL transaction에서 실행됩니다.
 */
internal class ProjectionRebuildRepository {

    companion object: KLogging()

    /**
     * initializeActive은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun initializeActive(
        projection: String,
        now: Instant,
    ): ActiveProjectionGeneration {
        TransactionManager.current()
        projection.requireNotBlank("projection")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val inserted = ActiveProjectionGenerations.insertIgnore { row ->
            row[ActiveProjectionGenerations.projection] = projection
            row[ActiveProjectionGenerations.generation] = FIRST_REBUILD_GENERATION
            row[ActiveProjectionGenerations.revision] = FIRST_ACTIVE_POINTER_REVISION
            row[ActiveProjectionGenerations.updatedAt] = now
        }.insertedCount == 1

        if (inserted) {
            insertInitialProjectionGeneration(projection, now)
        }
        return findActive(projection).checkNotNull { "active projection pointer disappeared" }
    }

    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param targetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun start(
        projection: String,
        targetPosition: Long,
        now: Instant,
    ): ProjectionGeneration {
        TransactionManager.current()
        targetPosition.requireZeroOrPositiveNumber("targetPosition")
        requireLockedActive(projection)
        findInProgressGeneration(projection).requireNull("inProgressGeneration")
        val generation = latestProjectionGeneration(projection) + 1
        ProjectionGenerations.insert { row ->
            row[ProjectionGenerations.projection] = projection
            row[ProjectionGenerations.generation] = generation
            row[ProjectionGenerations.state] = ProjectionGenerationState.BUILDING
            row[ProjectionGenerations.targetPosition] = targetPosition
            row[ProjectionGenerations.currentPosition] = 0
            row[ProjectionGenerations.fencingToken] = FIRST_REBUILD_FENCING_TOKEN
            row[ProjectionGenerations.cancellationRevision] = 0
            row[ProjectionGenerations.canonicalDigest] = null
            row[ProjectionGenerations.retryableFailure] = false
            row[ProjectionGenerations.createdAt] = now
            row[ProjectionGenerations.updatedAt] = now
        }
        val key = ProjectionKey(projection, generation)
        return findGeneration(key).checkNotNull { "building generation was not persisted" }
    }

    /**
     * advance은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun advance(
        key: ProjectionKey,
        cursor: ProjectionRebuildCursor,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                        (ProjectionGenerations.fencingToken eq cursor.fencingToken) and
                        (ProjectionGenerations.cancellationRevision eq cursor.cancellationRevision) and
                        (ProjectionGenerations.currentPosition eq cursor.expectedPosition)
            },
        ) { row ->
            row[ProjectionGenerations.currentPosition] = cursor.position
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    /**
     * beginValidation은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param cancellationRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param canonicalDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun beginValidation(
        key: ProjectionKey,
        fencingToken: Long,
        cancellationRevision: Long,
        canonicalDigest: ReceiptDigest,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        cancellationRevision.requireZeroOrPositiveNumber("cancellationRevision")
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                        (ProjectionGenerations.fencingToken eq fencingToken) and
                        (ProjectionGenerations.cancellationRevision eq cancellationRevision) and
                        (ProjectionGenerations.currentPosition eq ProjectionGenerations.targetPosition)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.VALIDATING
            row[ProjectionGenerations.canonicalDigest] = canonicalDigest.value
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    /**
     * extendValidationTarget은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param extension 업무 처리에 필요한 extension 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun extendValidationTarget(
        key: ProjectionKey,
        extension: ProjectionValidationTargetExtension,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.VALIDATING) and
                        (ProjectionGenerations.fencingToken eq extension.fencingToken) and
                        (ProjectionGenerations.cancellationRevision eq extension.cancellationRevision) and
                        (ProjectionGenerations.targetPosition eq extension.currentTargetPosition) and
                        (ProjectionGenerations.currentPosition eq extension.currentTargetPosition)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.BUILDING
            row[ProjectionGenerations.targetPosition] = extension.newTargetPosition
            row[ProjectionGenerations.canonicalDigest] = null
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    /**
     * activate은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param expectedPointerRevision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param targetHead 업무 처리에 필요한 targetHead 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param canonicalDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun activate(
        key: ProjectionKey,
        expectedPointerRevision: Long,
        targetHead: Long,
        canonicalDigest: ReceiptDigest,
        now: Instant,
    ): ProjectionActivationResult {
        TransactionManager.current()
        val pointer: ActiveProjectionGeneration = requireLockedActive(key.projection)
        val candidate: ProjectionGeneration =
            lockProjectionGeneration(key).checkNotNull { "candidate generation disappeared" }

        return when {
            pointer.revision != expectedPointerRevision          -> ProjectionActivationResult.StalePointer

            candidate.state != ProjectionGenerationState.VALIDATING ||
                    candidate.targetPosition != targetHead ||
                    candidate.currentPosition != targetHead ||
                    candidate.canonicalDigest != canonicalDigest ->
                ProjectionActivationResult.CandidateNotReady

            else                                                 -> {
                retirePreviousProjectionGeneration(pointer, now)
                updateActiveProjectionPointer(key, pointer, now)
                ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                    row[ProjectionGenerations.state] = ProjectionGenerationState.ACTIVE
                    row[ProjectionGenerations.updatedAt] = now
                }
                ProjectionActivationResult.Activated
            }
        }
    }

    /**
     * requestCancellation은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun requestCancellation(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)
            ?.let { current ->
                if (current.state !in CANCELLABLE_STATES) {
                    current
                } else {
                    ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                        row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLING
                        row[ProjectionGenerations.fencingToken] = current.fencingToken + 1
                        row[ProjectionGenerations.cancellationRevision] = current.cancellationRevision + 1
                        row[ProjectionGenerations.updatedAt] = now
                    }
                    findGeneration(key).checkNotNull { "cancelling generation disappeared" }
                }
            }
    }

    /**
     * completeCancellation은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun completeCancellation(
        key: ProjectionKey,
        fencingToken: Long,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.CANCELLING) and
                        (ProjectionGenerations.fencingToken eq fencingToken)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.CANCELLED
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    /**
     * fail은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param fencingToken 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param retryable 업무 처리에 필요한 retryable 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun fail(
        key: ProjectionKey,
        fencingToken: Long,
        retryable: Boolean,
        now: Instant,
    ): Boolean {
        TransactionManager.current()
        return ProjectionGenerations.update(
            where = {
                generationPredicate(key) and
                        (ProjectionGenerations.state eq ProjectionGenerationState.BUILDING) and
                        (ProjectionGenerations.fencingToken eq fencingToken)
            },
        ) { row ->
            row[ProjectionGenerations.state] = ProjectionGenerationState.FAILED
            row[ProjectionGenerations.retryableFailure] = retryable
            row[ProjectionGenerations.updatedAt] = now
        } == 1
    }

    /**
     * resume은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun resume(
        key: ProjectionKey,
        now: Instant,
    ): ProjectionGeneration? {
        TransactionManager.current()
        return lockProjectionGeneration(key)
            ?.let { current ->
                if (current.state != ProjectionGenerationState.FAILED || !current.retryableFailure) {
                    current
                } else {
                    ProjectionGenerations.update(where = { generationPredicate(key) }) { row ->
                        row[ProjectionGenerations.state] = ProjectionGenerationState.BUILDING
                        row[ProjectionGenerations.fencingToken] = current.fencingToken + 1
                        row[ProjectionGenerations.updatedAt] = now
                    }
                    findGeneration(key).checkNotNull { "resumed generation disappeared" }
                }
            }
    }
}
