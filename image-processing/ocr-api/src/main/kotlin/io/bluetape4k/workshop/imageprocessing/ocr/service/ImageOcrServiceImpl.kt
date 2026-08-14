package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.images.immutableImageOf
import io.bluetape4k.images.ocr.OcrBoundingBox as SourceOcrBoundingBox
import io.bluetape4k.images.ocr.OcrConfigurationException
import io.bluetape4k.images.ocr.OcrException
import io.bluetape4k.images.ocr.OcrOptions
import io.bluetape4k.images.ocr.OcrPage as SourceOcrPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.OcrStructuredResult
import io.bluetape4k.images.ocr.OcrTextBlock as SourceOcrTextBlock
import io.bluetape4k.images.ocr.OcrTextLine as SourceOcrTextLine
import io.bluetape4k.images.ocr.OcrWord as SourceOcrWord
import io.bluetape4k.images.ocr.StructuredOcrEngine
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrBoundingBox
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrPage
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextBlock
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextLine
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrWord
import com.sksamuel.scrimage.ImmutableImage
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service

/**
 * 워크숍 API용 기본 OCR 서비스 구현입니다.
 */
@Service
class ImageOcrServiceImpl(
    private val properties: ImageOcrProperties,
    private val ocrEngineProvider: OcrEngineProvider,
    private val requestIdGenerator: RequestIdGenerator,
) : ImageOcrService {

    private val nativeSemaphore = Semaphore(permits = 1)

    override suspend fun recognize(request: ImageOcrRequest): ImageOcrResponse {
        val startedAtNanos = System.nanoTime()
        val requestId = requestIdGenerator.nextId()
        val languages = normalizeLanguages(request.languages.ifEmpty { properties.languages })

        validateBytes(request.bytes)
        val contentType = validateContentType(request.contentType)

        if (!properties.effectiveNativeEnabled) {
            decodeAndValidateImage(request.bytes, contentType)
            return recordOutcome(
                response = ImageOcrResponse(
                    requestId = requestId,
                    status = OcrStatus.UNAVAILABLE,
                    engine = DISABLED_ENGINE,
                    languages = languages,
                    confidence = null,
                    text = "",
                    blocks = emptyList(),
                    warnings = listOf("Native OCR is disabled. Enable workshop.ocr.native-enabled=true or -Docr.enabled=true."),
                ),
                nativeEnabled = false,
                failureCategory = "native-disabled",
                startedAtNanos = startedAtNanos,
            )
        }

        return try {
            nativeSemaphore.withPermit {
                withTimeout(properties.timeout.toMillis()) {
                    runInterruptible(Dispatchers.IO) {
                        val image = decodeAndValidateImage(request.bytes, contentType)
                        val engine = ocrEngineProvider.get() ?: throw OcrConfigurationException("Native OCR engine is not configured")
                        val options = OcrOptions(
                            languages = languages,
                            tessdataPath = properties.tessdataPath,
                            structuredDetail = request.structuredDetail,
                        )
                        val response = when {
                            request.structuredDetail == OcrStructuredDetail.PLAIN_TEXT ->
                                completed(requestId, languages, engine.recognize(image, options).text.trim())
                            engine is StructuredOcrEngine ->
                                completed(requestId, languages, engine.recognizeStructured(image, options))
                            else ->
                                plainFallback(
                                    requestId = requestId,
                                    languages = languages,
                                    requestedDetail = request.structuredDetail,
                                    text = engine.recognize(image, options).text.trim(),
                                )
                        }
                        recordOutcome(
                            response = response,
                            nativeEnabled = true,
                            failureCategory = "none",
                            startedAtNanos = startedAtNanos,
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            recordOutcome(
                response = failed(requestId, languages, "OCR failed: native OCR timed out."),
                nativeEnabled = true,
                failureCategory = "timeout",
                startedAtNanos = startedAtNanos,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrConfigurationException) {
            recordOutcome(
                response = unavailable(requestId, languages, "Native OCR is unavailable. Check Tesseract, tessdata, and language packs."),
                nativeEnabled = true,
                failureCategory = "configuration",
                startedAtNanos = startedAtNanos,
            )
        } catch (e: OcrException) {
            recordOutcome(
                response = failed(requestId, languages, "OCR failed. Check the uploaded image and native OCR setup."),
                nativeEnabled = true,
                failureCategory = "ocr",
                startedAtNanos = startedAtNanos,
            )
        }
    }

    private fun recordOutcome(
        response: ImageOcrResponse,
        nativeEnabled: Boolean,
        failureCategory: String,
        startedAtNanos: Long,
    ): ImageOcrResponse {
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
        log.info(
            "OCR request completed requestId=${response.requestId} status=${response.status} " +
                "engine=${response.engine} languages=${response.languages.joinToString(",")} " +
                "nativeEnabled=$nativeEnabled elapsedMillis=$elapsedMillis failureCategory=$failureCategory",
        )
        return response
    }

    private fun completed(requestId: String, languages: List<String>, text: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.COMPLETED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = text,
            blocks = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { index, line ->
                    OcrTextBlock(index = index, text = line, confidence = null)
                }
                .toList(),
            warnings = listOf("Confidence is not available from the current OCR engine."),
        )

    private fun completed(requestId: String, languages: List<String>, result: OcrStructuredResult): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.COMPLETED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = result.text.trim(),
            blocks = result.blocks.mapIndexed { index, block -> block.toModel(index) },
            warnings = listOf("Top-level confidence is not aggregated; inspect structured elements."),
            effectiveStructuredDetail = result.options.structuredDetail,
            pages = result.pages.map { it.toModel() },
            lines = result.lines.map { it.toModel() },
            words = result.words.map { it.toModel() },
        )

    private fun plainFallback(
        requestId: String,
        languages: List<String>,
        requestedDetail: OcrStructuredDetail,
        text: String,
    ): ImageOcrResponse =
        completed(
            requestId = requestId,
            languages = languages,
            text = text,
        ).copy(
            warnings = listOf(
                "Requested structured OCR detail $requestedDetail is unavailable from the configured engine; returned plain text.",
                "Confidence is not available from the current OCR engine.",
            ),
        )

    private fun unavailable(requestId: String, languages: List<String>, warning: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.UNAVAILABLE,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = "",
            blocks = emptyList(),
            warnings = listOf(warning),
        )

    private fun failed(requestId: String, languages: List<String>, warning: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.FAILED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = "",
            blocks = emptyList(),
            warnings = listOf(warning),
        )

    private fun SourceOcrPage.toModel(): OcrPage =
        OcrPage(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    private fun SourceOcrTextBlock.toModel(index: Int): OcrTextBlock =
        OcrTextBlock(
            index = index,
            text = text,
            confidence = confidence,
            pageIndex = pageIndex,
            boundingBox = boundingBox?.toModel(),
        )

    private fun SourceOcrTextLine.toModel(): OcrTextLine =
        OcrTextLine(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    private fun SourceOcrWord.toModel(): OcrWord =
        OcrWord(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    private fun SourceOcrBoundingBox.toModel(): OcrBoundingBox =
        OcrBoundingBox(
            x = x,
            y = y,
            width = width,
            height = height,
        )

    private fun validateBytes(bytes: ByteArray) {
        bytes.size.requirePositiveNumber("bytes.size")
        bytes.size.toLong().requireInRange(1L, properties.maxUploadBytes, "bytes.size")
    }

    private fun validateContentType(contentType: String?): String {
        val declaredContentType = requireNotNull(contentType) {
            "Unsupported image content type. Use JPEG, PNG, or WebP."
        }
        require(declaredContentType in SUPPORTED_CONTENT_TYPES) {
            "Unsupported image content type. Use JPEG, PNG, or WebP."
        }
        return declaredContentType
    }

    private fun decodeAndValidateImage(bytes: ByteArray, declaredContentType: String): ImmutableImage {
        val detectedContentType = detectContentType(bytes)
        require(detectedContentType == declaredContentType || declaredContentType == "image/jpg" && detectedContentType == "image/jpeg") {
            "Uploaded bytes do not match the declared image content type"
        }
        val dimensions = readDimensions(bytes, detectedContentType)
        validateDecodedPixels(dimensions.width, dimensions.height)
        val image = try {
            immutableImageOf(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException("Undecodable image upload", e)
        }
        validateDecodedPixels(image.width.toLong(), image.height.toLong())
        return image
    }

    private fun detectContentType(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        throw IllegalArgumentException("Undecodable image upload")
    }

    private fun readDimensions(bytes: ByteArray, contentType: String): ImageDimensions =
        when (contentType) {
            "image/png" -> readPngDimensions(bytes)
            "image/jpeg" -> readJpegDimensions(bytes)
            "image/webp" -> readWebpDimensions(bytes)
            else -> throw IllegalArgumentException("Unsupported image content type. Use JPEG, PNG, or WebP.")
        }

    private fun readPngDimensions(bytes: ByteArray): ImageDimensions {
        require(bytes.size >= 24) { "Undecodable image upload" }
        require(
            bytes[12] == 'I'.code.toByte() &&
                bytes[13] == 'H'.code.toByte() &&
                bytes[14] == 'D'.code.toByte() &&
                bytes[15] == 'R'.code.toByte(),
        ) {
            "Undecodable image upload"
        }

        return ImageDimensions(
            width = bytes.readUInt32BE(offset = 16),
            height = bytes.readUInt32BE(offset = 20),
        )
    }

    private fun readJpegDimensions(bytes: ByteArray): ImageDimensions {
        var offset = 2
        while (offset + 3 < bytes.size) {
            while (offset < bytes.size && bytes[offset] != 0xFF.toByte()) {
                offset++
            }
            while (offset < bytes.size && bytes[offset] == 0xFF.toByte()) {
                offset++
            }
            require(offset < bytes.size) { "Undecodable image upload" }

            val marker = bytes[offset].toInt() and 0xFF
            offset++
            if (marker == 0xD9 || marker == 0xDA) {
                break
            }

            require(offset + 1 < bytes.size) { "Undecodable image upload" }
            val segmentLength = bytes.readUInt16BE(offset).toInt()
            require(segmentLength >= 2 && offset + segmentLength <= bytes.size) { "Undecodable image upload" }
            if (marker in JPEG_DIMENSION_MARKERS) {
                require(segmentLength >= 7) { "Undecodable image upload" }
                return ImageDimensions(
                    width = bytes.readUInt16BE(offset + 5),
                    height = bytes.readUInt16BE(offset + 3),
                )
            }
            offset += segmentLength
        }

        throw IllegalArgumentException("Undecodable image upload")
    }

    private fun readWebpDimensions(bytes: ByteArray): ImageDimensions {
        require(bytes.size >= 16) { "Undecodable image upload" }
        return when {
            bytes.hasAscii(offset = 12, value = "VP8X") -> {
                require(bytes.size >= 30) { "Undecodable image upload" }
                ImageDimensions(
                    width = bytes.readUInt24LE(offset = 24) + 1,
                    height = bytes.readUInt24LE(offset = 27) + 1,
                )
            }
            bytes.hasAscii(offset = 12, value = "VP8L") -> {
                require(bytes.size >= 25) { "Undecodable image upload" }
                val bits = bytes.readUInt32LE(offset = 21)
                ImageDimensions(
                    width = (bits and 0x3FFF) + 1,
                    height = ((bits shr 14) and 0x3FFF) + 1,
                )
            }
            bytes.hasAscii(offset = 12, value = "VP8 ") -> {
                require(bytes.size >= 30) { "Undecodable image upload" }
                require(
                    bytes[23] == 0x9D.toByte() &&
                        bytes[24] == 0x01.toByte() &&
                        bytes[25] == 0x2A.toByte(),
                ) {
                    "Undecodable image upload"
                }
                ImageDimensions(
                    width = bytes.readUInt16LE(offset = 26) and 0x3FFF,
                    height = bytes.readUInt16LE(offset = 28) and 0x3FFF,
                )
            }
            else -> throw IllegalArgumentException("Undecodable image upload")
        }
    }

    private fun validateDecodedPixels(width: Long, height: Long) {
        width.requirePositiveNumber("width")
        height.requirePositiveNumber("height")
        width.requireInRange(1L, properties.maxImagePixels / height, "width")
    }

    private fun normalizeLanguages(rawLanguages: List<String>): List<String> {
        val normalized = rawLanguages
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val effective = normalized.ifEmpty { properties.languages }
        effective.requireNotEmpty("languages")
        effective.forEach { language ->
            require(LANGUAGE_PATTERN.matches(language)) {
                "Invalid OCR language: $language"
            }
        }
        return effective
    }

    companion object : KLogging() {
        private const val DISABLED_ENGINE = "disabled"
        private const val NATIVE_ENGINE = "tesseract"

        private val LANGUAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_+-]*")
        private val JPEG_DIMENSION_MARKERS = setOf(
            0xC0,
            0xC1,
            0xC2,
            0xC3,
            0xC5,
            0xC6,
            0xC7,
            0xC9,
            0xCA,
            0xCB,
            0xCD,
            0xCE,
            0xCF,
        )
        private val SUPPORTED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
        )
    }
}

private class ImageDimensions(
    val width: Long,
    val height: Long,
)

private fun ByteArray.readUInt16BE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) shl 8 or
        (this[offset + 1].toInt() and 0xFF)).toLong()

private fun ByteArray.readUInt16LE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)).toLong()

private fun ByteArray.readUInt24LE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)).toLong()

private fun ByteArray.readUInt32BE(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

private fun ByteArray.readUInt32LE(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.hasAscii(offset: Int, value: String): Boolean =
    size >= offset + value.length && value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
