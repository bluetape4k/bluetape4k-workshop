package io.bluetape4k.workshop.imageprocessing.profile.model

import io.bluetape4k.images.privacy.PrivacyDerivativePayload
import io.bluetape4k.images.privacy.PrivacyDerivativeReport
import io.bluetape4k.images.spring.ImageObjectKey
import java.io.Serializable
import java.time.Instant

/**
 * 예제 API가 반환하는 유효 프로필 이미지 URL의 생명주기 상태입니다.
 *
 * - [NO_IMAGE] — 업로드가 없어 안정적인 기본 이미지를 사용합니다.
 * - [PENDING_MODERATION] — 현재 upload가 접수되었지만 판정 전입니다. blurred
 *   derivative만 유효 URL이고 원본은 여전히 private입니다.
 * - [APPROVED] — 현재 `userId + uploadId`가 policy를 통과해 approved
 *   derivative를 공개할 수 있습니다.
 * - [REJECTED] — policy가 금지 콘텐츠를 찾아 기본 이미지로 되돌립니다.
 * - [MODERATION_FAILED] — provider/timeout/derivative 처리 실패로 기본
 *   이미지에 fail closed 합니다.
 * ```kotlin
 * if (view.status == ProfileImageStatus.PENDING_MODERATION) {
 *     // effectiveUrl을 표시하되 moderation 완료로 간주하지 않음
 * }
 * ```
 */
enum class ProfileImageStatus {
    /** 업로드가 없어 기본 이미지만 표시하는 안정적인 초기 상태입니다. */
    /** 업로드가 없어 기본 이미지만 표시하는 안정적인 초기 상태입니다. */
    NO_IMAGE,
    /** 업로드는 접수됐지만 검수 전이므로 blurred derivative만 공개합니다. */
    /** 업로드는 접수됐지만 검수 전이므로 blurred derivative만 공개합니다. */
    PENDING_MODERATION,
    /** 검수 통과 후 approved derivative를 공개할 수 있습니다. */
    /** 검수 통과 후 approved derivative를 공개할 수 있습니다. */
    APPROVED,
    /** 금지 콘텐츠 판정으로 원본과 파생 이미지를 공개하지 않습니다. */
    /** 금지 콘텐츠 판정으로 원본과 파생 이미지를 공개하지 않습니다. */
    REJECTED,
    /** provider·timeout·derivative 실패로 fail closed하고 기본 이미지를 사용합니다. */
    /** provider·timeout·derivative 실패로 fail closed하고 기본 이미지를 사용합니다. */
    MODERATION_FAILED,
}

/**
 * 로컬 모의 제공자와 서비스 처리 흐름이 사용하는 최소 검수 판정입니다.
 *
 * `APPROVED`는 현재 upload를 공개할 수 있다는 뜻이고 `REJECTED`는 기본
 * 이미지로 되돌려야 한다는 뜻입니다. 이 enum은 외부 모델의 품질이나
 * confidence를 표현하지 않으며, 실제 운영 provider의 adapter가 이 두 결과로
 * 매핑해야 합니다.
 */
enum class ModerationDecision {
    /** 현재 upload를 공개해도 된다는 provider 판정입니다. */
    /** 현재 upload를 공개해도 된다는 provider 판정입니다. */
    APPROVED,
    /** 정책에 걸려 기본 이미지로 되돌려야 한다는 provider 판정입니다. */
    /** 정책에 걸려 기본 이미지로 되돌려야 한다는 provider 판정입니다. */
    REJECTED,
}

/**
 * 현재 유효 URL을 포함한 API 대상 프로필 이미지 표현입니다.
 *
 * [effectiveUrl]은 profile page가 지금 표시할 주소이며 private original key는
 * 절대 포함하지 않습니다. [pendingUrl]과 [approvedUrl]은 현재 status에 맞는
 * 값만 공개하고 다른 상태에서는 `null`로 숨깁니다. [reason]은 bounded한
 * 사용자 안내용 문구이므로 provider 원문이나 raw filename을 넣지 않아야 합니다.
 * ```json
 * { "userId": "alice", "status": "PENDING_MODERATION",
 *   "effectiveUrl": "/public-images/profile-images/alice/upload-a/pending/blurred.jpg",
 *   "approvedUrl": null }
 * ```
 * @property userId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property uploadId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property effectiveUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property pendingUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property approvedUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property defaultImageUrl 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property reason 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 * @property updatedAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */
data class ProfileImageView(
    /**
     * `userId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userId: String,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ProfileImageStatus,
    /**
     * `uploadId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val uploadId: String?,
    /**
     * `effectiveUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val effectiveUrl: String,
    /**
     * `pendingUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val pendingUrl: String?,
    /**
     * `approvedUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val approvedUrl: String?,
    /**
     * `defaultImageUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val defaultImageUrl: String,
    /**
     * `reason`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String?,
    /**
     * `updatedAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant,
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
 * 프로필 이미지 업로드 하나에서 생성되는 객체 스토리지 키입니다.
 *
 * [original]은 private 경로이고 [pending]/[approved]는 privacy-safe JPEG
 * derivative 경로입니다. key 자체는 주소 식별자일 뿐 접근 권한이 아니므로
 * public controller가 다시 path를 검증해야 합니다.
 * @property original 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property pending 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property approved 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class ProfileImageKeys(
    /**
     * `original`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val original: ImageObjectKey,
    /**
     * `pending`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pending: ImageObjectKey,
    /**
     * `approved`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val approved: ImageObjectKey,
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
 * 검수 시작 전에 업로드 이미지에서 생성되는 JPEG 파생 이미지입니다.
 *
 * [pendingBytes]는 UX용 blurred 결과이고 [approvedBytes]는 moderation 통과
 * 뒤 공개할 결과입니다. 두 bytes 모두 privacy pipeline을 거친 뒤 storage에
 * 씁니다. runtime report는 transient이며 payload snapshot은 재시작/메시지
 * 경계에서 사용할 bounded metadata입니다.
 *
 * ```kotlin
 * val processed = processor.processPrivacySafe(bytes, sourceId = uploadId)
 * storage.upload(keys.pending, processed.pendingBytes, UploadOptions(contentType = "image/jpeg"))
 * ```
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property pendingBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property approvedBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property pendingPrivacyReport 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를
 * 보존해야 합니다.
 * @property approvedPrivacyReport 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를
 * 보존해야 합니다.
 * @property pendingPrivacyPayload 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의
 * 노출을 제한합니다.
 * @property approvedPrivacyPayload 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의
 * 노출을 제한합니다.
 */
data class ProcessedProfileImage(
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
    /**
     * `pendingBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val pendingBytes: ByteArray,
    /**
     * `approvedBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val approvedBytes: ByteArray,
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String = "image/jpeg",
    @Transient val pendingPrivacyReport: PrivacyDerivativeReport? = null,
    @Transient val approvedPrivacyReport: PrivacyDerivativeReport? = null,
    /**
     * pending 산출물과 bounded privacy report를 함께 보존하는 영속화 payload입니다.
     *
     * runtime report는 진단용으로만 transient하게 유지하고, 재시작·메시지 경계에서는
     * 이 snapshot을 사용합니다.
     */
    val pendingPrivacyPayload: PrivacyDerivativePayload? = null,
    /** approved public 산출물과 bounded privacy report를 함께 보존하는 영속화 payload입니다. */
    /** approved public 산출물과 bounded privacy report를 함께 보존하는 영속화 payload입니다. */
    val approvedPrivacyPayload: PrivacyDerivativePayload? = null,
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
 * 인메모리 예제 저장소가 영속화하는 변경 가능한 프로필 이미지 상태입니다.
 *
 * [toView]는 status에 따라 `effectiveUrl`을 선택합니다. moderation 완료를
 * 적용할 때 repository는 `userId + uploadId`를 compare-and-set하여 오래된
 * worker가 최신 업로드를 덮지 않도록 해야 합니다.
 * @property userId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property uploadId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property keys 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property pendingUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property approvedUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property defaultImageUrl 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property reason 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 * @property originalFilename 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property updatedAt 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와
 * clock
 * 정책을 유지합니다.
 */
data class ProfileImageState(
    /**
     * `userId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userId: String,
    /**
     * `uploadId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val uploadId: String,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: ProfileImageStatus,
    /**
     * `keys`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keys: ProfileImageKeys,
    /**
     * `pendingUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val pendingUrl: String,
    /**
     * `approvedUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val approvedUrl: String,
    /**
     * `defaultImageUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val defaultImageUrl: String,
    /**
     * `reason`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String? = null,
    /**
     * `originalFilename`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val originalFilename: String? = null,
    /**
     * `updatedAt`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val updatedAt: Instant = Instant.now(),
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }

    /**
     * 내부 상태를 private key 없이 외부 API 표현으로 바꿉니다.
     *
     * ```kotlin
     * val view = state.toView()
     * // REJECTED / MODERATION_FAILED -> defaultImageUrl
     * ```
     */
    fun toView(): ProfileImageView {
        val effectiveUrl = when (status) {
            ProfileImageStatus.PENDING_MODERATION -> pendingUrl
            ProfileImageStatus.APPROVED -> approvedUrl
            ProfileImageStatus.REJECTED,
            ProfileImageStatus.MODERATION_FAILED,
            ProfileImageStatus.NO_IMAGE,
                                        -> defaultImageUrl
        }
        return ProfileImageView(
            userId = userId,
            status = status,
            uploadId = uploadId,
            effectiveUrl = effectiveUrl,
            pendingUrl = pendingUrl.takeIf { status == ProfileImageStatus.PENDING_MODERATION },
            approvedUrl = approvedUrl.takeIf { status == ProfileImageStatus.APPROVED },
            defaultImageUrl = defaultImageUrl,
            reason = reason,
            updatedAt = updatedAt,
        )
    }
}

/**
 * 비동기 검수 실행기에 전달되는 작업 항목입니다.
 *
 * [originalKey]는 provider adapter가 원본을 읽어야 할 때 사용하는 private
 * 주소입니다. 이 값을 API response나 public URL로 그대로 반환해서는 안 됩니다.
 * @property userId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property uploadId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property originalFilename 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property originalKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 */
data class ModerationRequest(
    /**
     * `userId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val userId: String,
    /**
     * `uploadId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val uploadId: String,
    /**
     * `originalFilename`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val originalFilename: String?,
    /**
     * `originalKey`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val originalKey: ImageObjectKey,
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
 * 이미지 검수 제공자가 반환하는 결과입니다.
 *
 * [reason]은 사용자에게 노출될 수 있는 제한된 설명으로 정규화해야 하며,
 * native stack trace·원본 filename·민감한 provider payload를 포함하지 않습니다.
 * ```kotlin
 * ModerationResult(ModerationDecision.REJECTED, "policy match")
 * ```
 * @property decision 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property reason 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */
data class ModerationResult(
    /**
     * `decision`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val decision: ModerationDecision,
    /**
     * `reason`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String,
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
