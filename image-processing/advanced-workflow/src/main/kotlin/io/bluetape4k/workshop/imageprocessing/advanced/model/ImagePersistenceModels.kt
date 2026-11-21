package io.bluetape4k.workshop.imageprocessing.advanced.model

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import java.io.Serializable
import java.time.LocalDateTime

// ---------------------------------------------------------------------------
// 입력 값 객체
// ---------------------------------------------------------------------------

/**
 * 새 이미지 처리 작업을 시작할 때 호출자가 제공하는 메타데이터입니다.
 *
 * ## 동작 / 계약
 * - [checksum]은 비어 있으면 안 되며 중복 제거 키로 사용합니다.
 * - 업로드 시점에 크기 정보를 알 수 없으면 [dimensions]는 null일 수 있습니다.
 * @property checksum 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property originalFilename 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property byteSize 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property dimensions 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 */
data class AssetMetadataInput(
    /**
     * `checksum`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val checksum: String,
    /**
     * `originalFilename`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val originalFilename: String?,
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String?,
    /**
     * `byteSize`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val byteSize: Long?,
    /**
     * `dimensions`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val dimensions: ImageDimensions?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 이미지의 픽셀 크기입니다.
 *
 * 인접한 두 `Int` 파라미터의 위치 혼동을 막기 위한 래퍼입니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 */
data class ImageDimensions(
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Int,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Int,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 인접한 두 `Long` 파라미터(jobId, assetId)의 위치 혼동을 막기 위한 래퍼입니다.
 *
 * ## 동작 / 계약
 * - [jobId]와 [assetId]는 모두 양수(> 0)여야 합니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property assetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */
data class JobIdentity(
    /**
     * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: Long,
    /**
     * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val assetId: Long,
): Serializable {
    init {
        jobId.requirePositiveNumber("jobId")
        assetId.requirePositiveNumber("assetId")
    }

    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 작업 실패를 설명하는 인접한 두 `String` 파라미터를 감싸는 래퍼입니다.
 *
 * ## 동작 / 계약
 * - [errorCode]는 비어 있으면 안 되며 최대 100자입니다.
 * - [errorMessage]는 비어 있으면 안 됩니다.
 * @property errorCode 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property errorMessage 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 */
data class JobFailureReason(
    /**
     * `errorCode`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val errorCode: String,
    /**
     * `errorMessage`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val errorMessage: String,
): Serializable {
    init {
        errorCode.requireNotBlank("errorCode")
        errorCode.length.requireInRange(1, 100, "errorCode.length")
        errorMessage.requireNotBlank("errorMessage")
    }

    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 영속화할 이미지 객체 하나(원본 또는 변형)의 입력 설명자입니다.
 *
 * ## 동작 / 계약
 * - [kind] = [ImageObjectKind.ORIGINAL]이면 [variantName]은 보통 null입니다.
 * - [kind] = [ImageObjectKind.VARIANT]이면 [variantName]이 변형을 식별합니다(예: `"thumbnail"`).
 * @property kind 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property variantName 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property s3Key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property publicUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property byteSize 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property format 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */
data class ImageObjectInput(
    /**
     * `kind`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: ImageObjectKind,
    /**
     * `variantName`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val variantName: String?,
    /**
     * `s3Key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val s3Key: String,
    /**
     * `publicUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val publicUrl: String,
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Int?,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Int?,
    /**
     * `byteSize`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val byteSize: Long?,
    /**
     * `format`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val format: String?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// 봉인된 JobStartResult
// ---------------------------------------------------------------------------

/**
 * `ImagePersistenceService.startJob()`이 반환하는 전체 결과 집합입니다.
 *
 * ## 변형
 * - [NewAsset]              — 새 asset 행을 만들었으며 호출자는 처리를 계속해야 합니다.
 * - [AlreadyReady]          — READY 상태의 asset을 찾았으며 호출자는 캐시된 응답을 반환해야 합니다.
 *   `jobId = -1L` — 새 job을 만들지 않았다는 뜻입니다.
 * - [ConcurrentProcessing]  — 다른 요청이 이미 asset을 처리 중입니다.
 * - [RecoveredFromFailed]   — 이전 FAILED asset을 PROCESSING으로 되돌리고 새 job을 만들었습니다.
 * ## 동작 / 계약
 * - [externalId]는 POST 응답의 `imageId`로 사용하는 UUID v4 문자열입니다.
 * - [AlreadyReady]는 이벤트를 발행하지 않는 순수 단축 경로입니다.
 */
sealed interface JobStartResult: Serializable {
    /**
     * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val assetId: Long
    /**
     * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: Long

    /** UUID v4 문자열이며 API 응답의 `imageId`로 사용합니다. */
    /** UUID v4 문자열이며 API 응답의 `imageId`로 사용합니다. */
    val externalId: String

    /**
     * 새 asset 행이 생성되었으므로 처리를 정상적으로 계속해야 합니다.
     * @property assetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property externalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     */
    data class NewAsset(
        /**
         * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val assetId: Long,
        /**
         * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val jobId: Long,
        /**
         * `externalId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val externalId: String,
    ): JobStartResult {
        companion object {
            /**
             * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * READY 상태의 asset이 이미 있으므로 호출자는 처리를 건너뛰고
     * 캐시된 응답을 반환해야 합니다. [jobId] = -1L은 새 job을 만들지 않았다는 뜻입니다.
     * 이 단축 경로에서는 이벤트를 발행하지 않습니다.
     * @property assetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property externalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     */
    data class AlreadyReady(
        /**
         * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val assetId: Long,
        /**
         * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val jobId: Long = -1L,
        /**
         * `externalId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val externalId: String,
    ): JobStartResult {
        companion object {
            /**
             * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * asset 행이 있으며 다른 요청이 현재 처리 중입니다.
     * @property assetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property externalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     */
    data class ConcurrentProcessing(
        /**
         * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val assetId: Long,
        /**
         * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val jobId: Long,
        /**
         * `externalId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val externalId: String,
    ): JobStartResult {
        companion object {
            /**
             * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }

    /**
     * 이전 FAILED asset을 복구해 상태를 PROCESSING으로 되돌리고 새 job을 만들었습니다.
     * 호출자는 전체 처리를 계속해야 합니다.
     * @property assetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     * @property externalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
     */
    data class RecoveredFromFailed(
        /**
         * `assetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val assetId: Long,
        /**
         * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val jobId: Long,
        /**
         * `externalId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        override val externalId: String,
    ): JobStartResult {
        companion object {
            /**
             * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
}

// ---------------------------------------------------------------------------
// DTO([ResultRow] → DTO 매퍼는 ImagePersistenceMappers.kt에 있음)
// ---------------------------------------------------------------------------

/**
 * `image_assets` 행의 읽기 모델 DTO입니다.
 *
 * [AuditableIdTable]이 nullable로 선언하므로 [createdAt]과 [updatedAt]도 nullable입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property externalId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property originalFilename 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property byteSize 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property checksum 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property createdAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 * @property updatedAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */
data class ImageAssetDTO(
    /**
     * `id`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `externalId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val externalId: String,
    /**
     * `originalFilename`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val originalFilename: String?,
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String?,
    /**
     * `byteSize`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val byteSize: Long?,
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Int?,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Int?,
    /**
     * `checksum`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val checksum: String,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ImageAssetStatus,
    /**
     * `createdAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: LocalDateTime?,
    /**
     * `updatedAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: LocalDateTime?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `image_objects` 행의 읽기 모델 DTO입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property imageAssetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property kind 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property variantName 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property s3Key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property publicUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property byteSize 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property format 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */
data class ImageObjectDTO(
    /**
     * `id`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `imageAssetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val imageAssetId: Long,
    /**
     * `kind`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: ImageObjectKind,
    /**
     * `variantName`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val variantName: String?,
    /**
     * `s3Key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val s3Key: String,
    /**
     * `publicUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val publicUrl: String,
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Int?,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Int?,
    /**
     * `byteSize`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val byteSize: Long?,
    /**
     * `format`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val format: String?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `image_processing_jobs` 행의 읽기 모델 DTO입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property imageAssetId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property requestedVariants 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한
 * 정보만 포함합니다.
 * @property startedAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 * @property finishedAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 * @property durationMs 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property errorCode 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property errorMessage 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 */
data class ImageProcessingJobDTO(
    /**
     * `id`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `imageAssetId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val imageAssetId: Long,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ImageJobStatus,
    /**
     * `requestedVariants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val requestedVariants: List<String>,
    /**
     * `startedAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val startedAt: LocalDateTime,
    /**
     * `finishedAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val finishedAt: LocalDateTime?,
    /**
     * `durationMs`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val durationMs: Long?,
    /**
     * `errorCode`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val errorCode: String?,
    /**
     * `errorMessage`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val errorMessage: String?,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `image_processing_events` 행의 읽기 모델 DTO입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property jobId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property step 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property message 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property payloadJson 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property createdAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */
data class ImageProcessingEventDTO(
    /**
     * `id`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `jobId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val jobId: Long,
    /**
     * `step`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val step: ImageProcessingStep,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ImageProcessingEventStatus,
    /**
     * `message`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val message: String?,
    /**
     * `payloadJson`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val payloadJson: Map<String, Any?>?,
    /**
     * `createdAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val createdAt: LocalDateTime,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// GET 엔드포인트 응답 DTO
// ---------------------------------------------------------------------------

/**
 * `GET /images/{imageId}` 응답입니다. 원본과 변형을 포함한 asset 상세를 담습니다.
 *
 * [imageId]는 asset의 [externalId](UUID v4 문자열)와 같습니다.
 * @property imageId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property original 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property variants 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 */
data class ImageAssetDetailResponse(
    /**
     * `imageId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val imageId: String,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ImageAssetStatus,
    /**
     * `original`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val original: ImageObjectDTO?,
    /**
     * `variants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val variants: List<ImageObjectDTO>,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 모든 단계 이벤트와 묶인 job입니다.
 * @property job 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property events 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */
data class ImageJobWithEventsDTO(
    /**
     * `job`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val job: ImageProcessingJobDTO,
    /**
     * `events`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<ImageProcessingEventDTO>,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `GET /images/{imageId}/history` 응답입니다. asset의 모든 job과 이벤트를 담습니다.
 *
 * [imageId]는 asset의 [externalId](UUID v4 문자열)와 같습니다.
 * @property imageId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property jobs 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */
data class ImageAssetHistoryResponse(
    /**
     * `imageId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val imageId: String,
    /**
     * `jobs`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val jobs: List<ImageJobWithEventsDTO>,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

// ---------------------------------------------------------------------------
// 사용자 정의 예외
// ---------------------------------------------------------------------------

/**
 * 지정한 [checksum]에 해당하는 [ImageAssetTable] 행을 찾지 못했을 때 던집니다.
 * @property checksum 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */
class ImageAssetNotFoundException(val checksum: String):
    NoSuchElementException("ImageAsset not found for checksum: $checksum")
