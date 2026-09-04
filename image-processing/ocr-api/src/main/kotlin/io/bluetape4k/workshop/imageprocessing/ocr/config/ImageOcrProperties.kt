package io.bluetape4k.workshop.imageprocessing.ocr.config

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OCR API 워크숍 모듈의 설정 속성입니다.
 */
@ConfigurationProperties(prefix = "workshop.ocr")
data class ImageOcrProperties(
    val nativeEnabled: Boolean = false,
    val maxUploadBytes: Long = 5_242_880,
    val maxImagePixels: Long = 12_000_000,
    val timeout: Duration = Duration.ofSeconds(10),
    val languages: List<String> = listOf("eng"),
    val tessdataPath: String? = null,
    val tiff: TiffMultiPageOcrProperties = TiffMultiPageOcrProperties(maxEncodedBytes = maxUploadBytes),
) : Serializable {
    val effectiveNativeEnabled: Boolean
        get() = nativeEnabled || System.getProperty("ocr.enabled")?.equals("true", ignoreCase = true) == true

    init {
        maxUploadBytes.requirePositiveNumber("maxUploadBytes")
        maxImagePixels.requirePositiveNumber("maxImagePixels")
        timeout.requireGt(Duration.ZERO, "timeout")
        languages.requireNotEmpty("languages")
        tessdataPath?.requireNotBlank("tessdataPath")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 다중 페이지 TIFF OCR에 적용하는 encoded input, page, pixel, metadata, result budget입니다.
 *
 * `ImageOcrProperties.maxUploadBytes`는 multipart/service 공통 상한이고, 이 값들은 TIFF
 * reader가 preflight와 page 결과를 처리할 때의 더 세밀한 상한입니다.
 */
data class TiffMultiPageOcrProperties(
    val maxEncodedBytes: Long = 5_242_880,
    val maxPages: Int = 16,
    val maxPixelsPerPage: Long = 12_000_000,
    val maxTotalPixels: Long = 64_000_000,
    val maxDecodedSide: Int = 8_192,
    val maxMetadataBytes: Long = 2L * 1024L * 1024L,
    val maxResultTextChars: Int = 1_000_000,
    val maxResultEntries: Int = 100_000,
) : Serializable {

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
        private const val serialVersionUID: Long = 1L
    }
}
