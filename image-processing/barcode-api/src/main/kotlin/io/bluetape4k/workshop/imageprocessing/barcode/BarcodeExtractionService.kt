package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.ImageDimensions
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.readImageMetadataReport
import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import io.bluetape4k.images.barcode.BarcodeReader
import io.bluetape4k.images.barcode.extractBarcodes
import io.bluetape4k.images.immutableImageOf
import io.bluetape4k.images.probeImageDimensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

/**
 * multipart 입력을 안전하게 읽고 provider-neutral barcode 결과로 변환합니다.
 *
 * 입력 크기 검증은 provider를 호출하기 전에 수행하고, 이미지 차원 probe가
 * 실패한 WebP는 bounded metadata reader로 한 번 더 확인합니다.
 */
internal class BarcodeExtractionService(
    private val reader: BarcodeReader,
    private val properties: BarcodeExampleProperties,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val dimensionProbe: (ByteArray) -> ImageDimensions? = ::probeImageDimensions,
    private val metadataDimensionProbe: (ByteArray, Int) -> ImageDimensions? = { bytes, maxBytes ->
        readImageMetadataReport(bytes, ImageMetadataReadOptions(maxBytes = maxBytes)).dimensions
    },
) {

    suspend fun extract(file: MultipartFile): BarcodeExtractionResponse {
        if (file.isEmpty) {
            throw requestError(HttpStatus.BAD_REQUEST, "empty_input", "The uploaded file is empty.")
        }

        val contentType = file.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (contentType !in ALLOWED_BARCODE_CONTENT_TYPES) {
            throw requestError(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                "The uploaded content type is not supported.",
            )
        }
        requireEncodedSize(file.size)

        val bytes = try {
            withContext(ioDispatcher) { file.bytes }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BarcodeException(
                reason = BarcodeFailureReason.DECODE_FAILED,
                message = "Unable to read the uploaded file.",
                cause = e,
            )
        }
        requireEncodedSize(bytes.size.toLong())
        return extract(bytes)
    }

    suspend fun extract(bytes: ByteArray): BarcodeExtractionResponse = withContext(cpuDispatcher) {
        try {
            if (bytes.isEmpty()) {
                throw requestError(HttpStatus.BAD_REQUEST, "empty_input", "The uploaded file is empty.")
            }
            requireEncodedSize(bytes.size.toLong())

            val dimensions = dimensionProbe(bytes)
                ?: metadataDimensionProbe(bytes, properties.maxInputBytes.toInt())
                ?: throw BarcodeException(
                    reason = BarcodeFailureReason.MALFORMED_INPUT,
                    message = "The uploaded file is not a decodable image.",
                )
            requireDecodedSize(dimensions)

            val results = immutableImageOf(bytes).extractBarcodes(reader).map { result ->
                BarcodeResultResponse(
                    text = result.text,
                    format = result.format,
                    provider = result.provider.name,
                )
            }
            BarcodeExtractionResponse(count = results.size, results = results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BarcodeRequestException) {
            throw e
        } catch (e: BarcodeException) {
            throw e
        } catch (e: Exception) {
            throw BarcodeException(
                reason = BarcodeFailureReason.MALFORMED_INPUT,
                message = "The uploaded file is not a decodable image.",
                cause = e,
            )
        }
    }

    private fun requireEncodedSize(size: Long) {
        if (size > properties.maxInputBytes) {
            throw requestError(
                HttpStatus.CONTENT_TOO_LARGE,
                "payload_too_large",
                "The uploaded file exceeds the configured size limit.",
            )
        }
    }

    private fun requireDecodedSize(dimensions: ImageDimensions) {
        if (dimensions.width > properties.maxInputSide || dimensions.height > properties.maxInputSide) {
            throw requestError(
                HttpStatus.CONTENT_TOO_LARGE,
                "payload_too_large",
                "The decoded image exceeds the configured side limit.",
            )
        }
        if (dimensions.pixelCount > properties.maxInputPixels) {
            throw requestError(
                HttpStatus.CONTENT_TOO_LARGE,
                "payload_too_large",
                "The decoded image exceeds the configured pixel limit.",
            )
        }
    }

    private fun requestError(status: HttpStatus, error: String, message: String): BarcodeRequestException =
        BarcodeRequestException(status, error, message)
}
