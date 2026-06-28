package io.bluetape4k.workshop.imageprocessing.ocr.config

import java.io.Serializable
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the OCR API workshop module.
 */
@ConfigurationProperties(prefix = "workshop.ocr")
data class ImageOcrProperties(
    val nativeEnabled: Boolean = false,
    val maxUploadBytes: Long = 5_242_880,
    val maxImagePixels: Long = 12_000_000,
    val timeout: Duration = Duration.ofSeconds(10),
    val languages: List<String> = listOf("eng"),
    val tessdataPath: String? = null,
) : Serializable {
    val effectiveNativeEnabled: Boolean
        get() = nativeEnabled || System.getProperty("ocr.enabled")?.equals("true", ignoreCase = true) == true

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
