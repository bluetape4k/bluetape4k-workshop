package io.bluetape4k.workshop.imageprocessing.profile.config

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

@ConfigurationProperties(prefix = "workshop.profile-image-moderation")
/**
 * 프로필 이미지 검수 워크숍 예제의 설정 속성입니다.
 * @property publicBaseUrl 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property defaultImageUrl 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property allowInsecurePublicBaseUrl 이미지 업로드·검사·파생물 처리 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와
 * 기본값을
 * 계약으로 유지해야 합니다.
 * @property allowLocalStorageRemotePublicBaseUrl 이미지 업로드·검사·파생물 처리 정책을 켜고 끄는 플래그입니다. true/false
 * 각각의 처리
 * 경로와 기본값을 계약으로 유지해야 합니다.
 * @property maxInputBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxPixels 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property maxWidth 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property maxHeight 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property requestConcurrency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지
 * 않습니다.
 * @property moderationConcurrency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로
 * 계산하지 않습니다.
 * @property processingTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property moderationTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property decisionDelay 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property rejectedFilenameMarker 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에
 * 반환합니다.
 * @property privacy 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class ProfileImageModerationProperties(
    /**
     * `publicBaseUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val publicBaseUrl: String = "http://localhost:8080/public-images",
    /**
     * `defaultImageUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val defaultImageUrl: String = "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
    /**
     * `allowInsecurePublicBaseUrl`은 이미지 업로드·파생 처리에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val allowInsecurePublicBaseUrl: Boolean = false,
    /**
     * `allowLocalStorageRemotePublicBaseUrl`은 이미지 업로드·파생 처리에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val allowLocalStorageRemotePublicBaseUrl: Boolean = false,
    /**
     * `maxInputBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxInputBytes: Long = 10 * 1024 * 1024L,
    /**
     * `maxPixels`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxPixels: Long = 25_000_000L,
    /**
     * `maxWidth`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxWidth: Int = 6_000,
    /**
     * `maxHeight`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxHeight: Int = 6_000,
    /**
     * `requestConcurrency`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val requestConcurrency: Int = 4,
    /**
     * `moderationConcurrency`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val moderationConcurrency: Int = 2,
    /**
     * `processingTimeout`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val processingTimeout: Duration = Duration.ofSeconds(10),
    /**
     * `moderationTimeout`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val moderationTimeout: Duration = Duration.ofSeconds(3),
    /**
     * `decisionDelay`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val decisionDelay: Duration = Duration.ofSeconds(1),
    /**
     * `rejectedFilenameMarker`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val rejectedFilenameMarker: String = "reject",
    /**
     * `privacy`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val privacy: ProfileImagePrivacyProperties = ProfileImagePrivacyProperties(),
): Serializable {

    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }

    init {
        publicBaseUrl.requireNotBlank("publicBaseUrl")
        defaultImageUrl.requireNotBlank("defaultImageUrl")
        maxInputBytes.requirePositiveNumber("maxInputBytes")
        maxPixels.requirePositiveNumber("maxPixels")
        maxWidth.requirePositiveNumber("maxWidth")
        maxHeight.requirePositiveNumber("maxHeight")
        requestConcurrency.requirePositiveNumber("requestConcurrency")
        moderationConcurrency.requirePositiveNumber("moderationConcurrency")
        processingTimeout.requirePositiveDuration("processingTimeout")
        moderationTimeout.requirePositiveDuration("moderationTimeout")
        decisionDelay.requireZeroOrPositiveDuration("decisionDelay")
        rejectedFilenameMarker.requireNotBlank("rejectedFilenameMarker")
    }
}

/**
 * 공개 프로필 derivative에 적용하는 privacy 정책과 bounded metadata reader 설정입니다.
 * @property stripMetadata 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property removeGps 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property normalizeOrientation 이미지 업로드·검사·파생물 처리 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을
 * 계약으로
 * 유지해야 합니다.
 * @property maxMetadataBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 */
data class ProfileImagePrivacyProperties(
    /**
     * `stripMetadata`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val stripMetadata: Boolean = true,
    /**
     * `removeGps`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val removeGps: Boolean = true,
    /**
     * `normalizeOrientation`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val normalizeOrientation: Boolean = true,
    /**
     * `maxMetadataBytes`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxMetadataBytes: Int = 5 * 1024 * 1024,
): Serializable {

    init {
        maxMetadataBytes.requirePositiveNumber("maxMetadataBytes")
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
