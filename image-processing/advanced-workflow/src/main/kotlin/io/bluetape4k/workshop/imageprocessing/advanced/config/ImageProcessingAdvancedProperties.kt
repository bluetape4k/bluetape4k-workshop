package io.bluetape4k.workshop.imageprocessing.advanced.config

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.utils.Runtimex
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

@ConfigurationProperties(prefix = "workshop.images.advanced")
/**
 * ImageProcessingAdvancedProperties 업무 데이터 모델입니다.
 * @property publicBaseUrl publicBaseUrl 업무 값입니다.
 * @property allowInsecurePublicBaseUrl allowInsecurePublicBaseUrl 업무 값입니다.
 * @property allowLocalStorageRemotePublicBaseUrl allowLocalStorageRemotePublicBaseUrl 업무 값입니다.
 * @property maxInputBytes maxInputBytes 업무 값입니다.
 * @property maxPixels maxPixels 업무 값입니다.
 * @property requestConcurrency requestConcurrency 업무 값입니다.
 * @property vipsConcurrency vipsConcurrency 업무 값입니다.
 * @property variantConcurrency variantConcurrency 업무 값입니다.
 * @property processingTimeout processingTimeout 업무 값입니다.
 * @property variants variants 업무 값입니다.
 */

data class ImageProcessingAdvancedProperties(
    /**
     * `publicBaseUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val publicBaseUrl: String = "http://localhost:8080/public-images",
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
    val maxInputBytes: Long = 25 * 1024 * 1024L,
    /**
     * `maxPixels`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val maxPixels: Long = 100_000_000L,
    /**
     * `requestConcurrency`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val requestConcurrency: Int = 2,
    /**
     * `vipsConcurrency`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val vipsConcurrency: Int = Runtimex.availableProcessors.coerceAtLeast(1),
    /**
     * `variantConcurrency`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val variantConcurrency: Int = 2,
    /**
     * `processingTimeout`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val processingTimeout: Duration = 30.seconds(),
    /**
     * `variants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val variants: List<ImageVariantProperties> = listOf(
        ImageVariantProperties(name = "thumb-128", maxDimension = 128, primaryThumbnail = true),
        ImageVariantProperties(name = "card-320", maxDimension = 320),
        ImageVariantProperties(name = "detail-1024", maxDimension = 1024),
    ),
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
        maxInputBytes.requirePositiveNumber("maxInputBytes")
        maxPixels.requirePositiveNumber("maxPixels")
        requestConcurrency.requirePositiveNumber("requestConcurrency")
        vipsConcurrency.requirePositiveNumber("vipsConcurrency")
        variantConcurrency.requirePositiveNumber("variantConcurrency")
        processingTimeout.requirePositiveDuration("processingTimeout")
        variants.requireNotEmpty("variants")
        variants.count { it.primaryThumbnail }.requireEquals(1, "primaryThumbnailCount")
    }
}

/**
 * ImageVariantProperties 업무 데이터 모델입니다.
 * @property name name 업무 값입니다.
 * @property maxDimension maxDimension 업무 값입니다.
 * @property contentType contentType 업무 값입니다.
 * @property extension extension 업무 값입니다.
 * @property primaryThumbnail primaryThumbnail 업무 값입니다.
 */

data class ImageVariantProperties(
    /**
     * `name`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `maxDimension`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxDimension: Int,
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String = "image/webp",
    /**
     * `extension`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val extension: String = "webp",
    /**
     * `primaryThumbnail`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val primaryThumbnail: Boolean = false,
): Serializable {

    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val VALID_NAME = Regex("^[A-Za-z0-9._-]+$")
    }

    init {
        name.requireNotBlank("name")
        maxDimension.requirePositiveNumber("maxDimension")
        contentType.requireNotBlank("contentType")
        extension.requireNotBlank("extension")
        name.requireMatches(VALID_NAME, "name")
        extension.requireMatches(VALID_NAME, "extension")
        contentType.requireEquals("image/webp", "contentType")
        extension.requireEquals("webp", "extension")
    }
}
