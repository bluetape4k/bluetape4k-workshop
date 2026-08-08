package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.barcode.BarcodeReader
import io.bluetape4k.images.barcode.zxing.ZxingBarcodeReader
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import java.io.Serializable

internal val ALLOWED_BARCODE_CONTENT_TYPES: Set<String> = setOf(
    MediaType.IMAGE_PNG_VALUE,
    MediaType.IMAGE_JPEG_VALUE,
    "image/webp",
)

/**
 * 예제 API가 입력을 읽기 전에 적용하는 encoded/decoded 크기 제한입니다.
 */
@ConfigurationProperties(prefix = "workshop.barcode")
data class BarcodeExampleProperties(
    val maxInputBytes: Long = 2L * 1024L * 1024L,
    val maxInputPixels: Long = 12_000_000L,
    val maxInputSide: Int = 4_096,
) : Serializable {

    init {
        maxInputBytes.requirePositiveNumber("maxInputBytes")
        require(maxInputBytes <= Int.MAX_VALUE) { "maxInputBytes must fit Int" }
        maxInputPixels.requirePositiveNumber("maxInputPixels")
        maxInputSide.requirePositiveNumber("maxInputSide")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BarcodeExampleProperties::class)
internal class BarcodeApiConfiguration {

    @Bean
    fun barcodeReader(): BarcodeReader = ZxingBarcodeReader()

    @Bean
    fun barcodeExampleFixtures(): BarcodeExampleFixtures = BarcodeExampleFixtures()

    @Bean
    fun barcodeExtractionService(
        reader: BarcodeReader,
        properties: BarcodeExampleProperties,
    ): BarcodeExtractionService = BarcodeExtractionService(reader, properties)
}
