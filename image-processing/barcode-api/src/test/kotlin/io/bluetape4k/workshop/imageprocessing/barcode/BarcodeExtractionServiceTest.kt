package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.images.ImageDimensions
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.readImageMetadataReport
import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import io.bluetape4k.images.barcode.BarcodeFormat
import io.bluetape4k.images.barcode.BarcodeReader
import io.bluetape4k.images.barcode.zxing.ZxingBarcodeReader
import io.bluetape4k.images.probeImageDimensions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile

class BarcodeExtractionServiceTest {

    private val fixtures = BarcodeExampleFixtures()

    @Test
    fun `extracts QR fixture through provider neutral API`() = runTest {
        val result = BarcodeExtractionService(
            reader = ZxingBarcodeReader(),
            properties = BarcodeExampleProperties(),
        ).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))

        result.count shouldBeEqualTo 1
        result.results.single().text shouldBeEqualTo "bluetape4k-barcode-quickstart"
        result.results.single().format shouldBeEqualTo BarcodeFormat.QR_CODE
        result.results.single().provider shouldBeEqualTo "ZXing"
    }

    @Test
    fun `returns empty result for a valid image without barcode`() = runTest {
        val result = BarcodeExtractionService(
            reader = ZxingBarcodeReader(),
            properties = BarcodeExampleProperties(),
        ).extract(fixtures.bytes(BarcodeExampleFixture.NO_RESULT))

        result shouldBeEqualTo BarcodeExtractionResponse(count = 0, results = emptyList())
    }

    @Test
    fun `accepts JPEG and WebP uploads`() = runTest {
        val jpeg = jpegBytes()
        val webp = webpBytes()
        val uploads = listOf(
            "image/jpeg" to jpeg,
            "image/webp" to webp,
        )

        uploads.forEach { (contentType, bytes) ->
            val response = service().extract(multipart(contentType, bytes))

            response.count shouldBeEqualTo if (contentType == "image/jpeg") 1 else 0
            if (contentType == "image/jpeg") {
                response.results.single().format shouldBeEqualTo BarcodeFormat.QR_CODE
            }
        }
    }

    @Test
    fun `uses bounded metadata dimensions when primary WebP probe is unavailable`() = runTest {
        val webp = webpBytes()

        val response = service(dimensionProbe = { null }).extract(multipart("image/webp", webp))

        response.count shouldBeEqualTo 0
    }

    @Test
    fun `rejects empty unsupported and missing content type uploads`() = runTest {
        val empty = assertFailsWith<BarcodeRequestException> {
            service().extract(multipart("image/png", ByteArray(0)))
        }
        empty.status shouldBeEqualTo HttpStatus.BAD_REQUEST
        empty.error shouldBeEqualTo "empty_input"

        listOf("text/plain", null).forEach { contentType ->
            val error = assertFailsWith<BarcodeRequestException> {
                service().extract(multipart(contentType, byteArrayOf(1)))
            }
            error.status shouldBeEqualTo HttpStatus.UNSUPPORTED_MEDIA_TYPE
            error.error shouldBeEqualTo "unsupported_media_type"
        }
    }

    @Test
    fun `rejects reported and actual encoded byte overflow before decode`() = runTest {
        val readerCalls = java.util.concurrent.atomic.AtomicInteger()
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
        val bytes = fixtures.bytes(BarcodeExampleFixture.SAMPLE)
        val properties = BarcodeExampleProperties(maxInputBytes = bytes.size.toLong() - 1)

        val multipartError = assertFailsWith<BarcodeRequestException> {
            service(reader, properties).extract(multipart("image/png", bytes))
        }
        multipartError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE

        val byteArrayError = assertFailsWith<BarcodeRequestException> {
            service(reader, properties).extract(bytes)
        }
        byteArrayError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `rejects decoded side and pixel overflow before provider invocation`() = runTest {
        val readerCalls = java.util.concurrent.atomic.AtomicInteger()
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
        val bytes = fixtures.bytes(BarcodeExampleFixture.SAMPLE)

        val sideError = assertFailsWith<BarcodeRequestException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputSide = 100),
            ).extract(bytes)
        }
        sideError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE

        val pixelError = assertFailsWith<BarcodeRequestException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputPixels = 40_000),
            ).extract(bytes)
        }
        pixelError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `strict loader rechecks dimensions after injected probes`() = runTest {
        val readerCalls = java.util.concurrent.atomic.AtomicInteger()
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
        val webp = webpBytes()

        val error = assertFailsWith<BarcodeException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputSide = 100),
                dimensionProbe = { ImageDimensions(1, 1) },
                metadataDimensionProbe = { _, _ -> ImageDimensions(1, 1) },
            ).extract(webp)
        }

        error.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `normalizes malformed bytes and unknown dimensions`() = runTest {
        val readerCalls = java.util.concurrent.atomic.AtomicInteger()
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
        val malformed = assertFailsWith<BarcodeException> {
            service(reader).extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
        }
        malformed.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT

        val unknownDimensions = assertFailsWith<BarcodeException> {
            service(
                reader = reader,
                dimensionProbe = { ImageDimensions(1, 1) },
                metadataDimensionProbe = { _, _ -> ImageDimensions(1, 1) },
            ).extract(byteArrayOf(1, 2, 3))
        }
        unknownDimensions.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `preserves provider neutral failures`() = runTest {
        listOf(
            BarcodeFailureReason.UNSUPPORTED_FORMAT,
            BarcodeFailureReason.PROVIDER_UNAVAILABLE,
            BarcodeFailureReason.DECODE_FAILED,
        ).forEach { reason ->
            val expected = BarcodeException(reason, "provider detail")
            val reader = BarcodeReader { _, _ -> throw expected }

            val actual = assertFailsWith<BarcodeException> {
                service(reader).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))
            }
            actual shouldBeSameInstanceAs expected
        }
    }

    @Test
    fun `rethrows cancellation unchanged`() = runTest {
        val expected = CancellationException("cancel extraction")
        val reader = BarcodeReader { _, _ -> throw expected }

        val actual = assertFailsWith<CancellationException> {
            service(reader).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))
        }

        actual.message shouldBeEqualTo expected.message
    }

    @Test
    fun `normalizes malformed bytes without exposing input detail`() = runTest {
        val exception = assertFailsWith<BarcodeException> {
            BarcodeExtractionService(
                reader = ZxingBarcodeReader(),
                properties = BarcodeExampleProperties(),
            ).extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
        }

        exception.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        exception.message.orEmpty().contains("malformed.bin") shouldBeEqualTo false
    }

    @Test
    fun `rejects unsupported multipart input before decoding`() = runTest {
        val exception = assertFailsWith<BarcodeRequestException> {
            BarcodeExtractionService(
                reader = ZxingBarcodeReader(),
                properties = BarcodeExampleProperties(),
            ).extract(
                org.springframework.mock.web.MockMultipartFile(
                    "file",
                    "payload.txt",
                    "text/plain",
                    byteArrayOf(1),
                )
            )
        }

        exception.status shouldBeEqualTo HttpStatus.UNSUPPORTED_MEDIA_TYPE
    }

    private fun service(
        reader: BarcodeReader = ZxingBarcodeReader(),
        properties: BarcodeExampleProperties = BarcodeExampleProperties(),
        dimensionProbe: (ByteArray) -> ImageDimensions? = ::probeImageDimensions,
        metadataDimensionProbe: (ByteArray, Int) -> ImageDimensions? = { bytes, maxBytes ->
            readImageMetadataReport(
                bytes,
                ImageMetadataReadOptions(maxBytes = maxBytes),
            ).dimensions
        },
    ): BarcodeExtractionService = BarcodeExtractionService(
        reader = reader,
        properties = properties,
        dimensionProbe = dimensionProbe,
        metadataDimensionProbe = metadataDimensionProbe,
    )

    private fun multipart(contentType: String?, bytes: ByteArray): MockMultipartFile =
        MockMultipartFile("file", "upload.bin", contentType, bytes)

    private fun jpegBytes(): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(fixtures.bytes(BarcodeExampleFixture.SAMPLE)))
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "jpeg", output)) { "JPEG writer is not available." }
            output.toByteArray()
        }
    }

    private fun webpBytes(): ByteArray = Base64.getDecoder().decode(
        requireNotNull(javaClass.classLoader.getResourceAsStream("barcodes/sample.webp.b64")) {
            "Required barcode WebP fixture is missing."
        }.use { it.readBytes().toString(Charsets.US_ASCII).trim() },
    )
}
