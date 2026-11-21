package io.bluetape4k.workshop.imageprocessing.ocr.config

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.support.equalsIgnoreCase
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * OCR API 워크숍 모듈의 설정 속성입니다.
 * @property nativeEnabled 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property maxUploadBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxImagePixels 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property timeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property languages 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property tessdataPath 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property tiff 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
@ConfigurationProperties(prefix = "workshop.ocr")
data class ImageOcrProperties(
    /**
     * `nativeEnabled`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val nativeEnabled: Boolean = false,
    /**
     * `maxUploadBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxUploadBytes: Long = 5_242_880,
    /**
     * `maxImagePixels`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxImagePixels: Long = 12_000_000,
    /**
     * `timeout`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val timeout: Duration = Duration.ofSeconds(10),
    /**
     * `languages`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val languages: List<String> = listOf("eng"),
    /**
     * `tessdataPath`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val tessdataPath: String? = null,
    /**
     * `tiff`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val tiff: TiffMultiPageOcrProperties = TiffMultiPageOcrProperties(maxEncodedBytes = maxUploadBytes),
): Serializable {
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val effectiveNativeEnabled: Boolean
        get() = nativeEnabled || System.getProperty("ocr.enabled")?.equalsIgnoreCase("true") == true

    init {
        maxUploadBytes.requirePositiveNumber("maxUploadBytes")
        maxImagePixels.requirePositiveNumber("maxImagePixels")
        timeout.requirePositiveDuration("timeout")
        languages.requireNotEmpty("languages")
        tessdataPath?.requireNotBlank("tessdataPath")
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
 * 다중 페이지 TIFF OCR에 적용하는 encoded input, page, pixel, metadata, result budget입니다.
 *
 * `ImageOcrProperties.maxUploadBytes`는 multipart/service 공통 상한이고, 이 값들은 TIFF
 * reader가 preflight와 page 결과를 처리할 때의 더 세밀한 상한입니다.
 * @property maxEncodedBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxPages 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property maxPixelsPerPage 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxTotalPixels 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxDecodedSide 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property maxMetadataBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을
 * 막습니다.
 * @property maxResultTextChars 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을
 * 제한합니다.
 * @property maxResultEntries 동시 처리량·할당량·집계 수량입니다. 음수와 한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 */
data class TiffMultiPageOcrProperties(
    /**
     * `maxEncodedBytes`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val maxEncodedBytes: Long = 5_242_880,
    /**
     * `maxPages`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxPages: Int = 16,
    /**
     * `maxPixelsPerPage`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxPixelsPerPage: Long = 12_000_000,
    /**
     * `maxTotalPixels`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxTotalPixels: Long = 64_000_000,
    /**
     * `maxDecodedSide`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val maxDecodedSide: Int = 8_192,
    /**
     * `maxMetadataBytes`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxMetadataBytes: Long = 2L * 1024L * 1024L,
    /**
     * `maxResultTextChars`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxResultTextChars: Int = 1_000_000,
    /**
     * `maxResultEntries`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val maxResultEntries: Int = 100_000,
): Serializable {

    init {
        maxEncodedBytes.requirePositiveNumber("tiff.maxEncodedBytes")
        maxPages.requirePositiveNumber("tiff.maxPages")
        maxPixelsPerPage.requirePositiveNumber("tiff.maxPixelsPerPage")
        maxTotalPixels.requirePositiveNumber("tiff.maxTotalPixels")
        maxDecodedSide.requirePositiveNumber("tiff.maxDecodedSide")
        maxMetadataBytes.requirePositiveNumber("tiff.maxMetadataBytes")
        maxResultTextChars.requirePositiveNumber("tiff.maxResultTextChars")
        maxResultEntries.requirePositiveNumber("tiff.maxResultEntries")
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
