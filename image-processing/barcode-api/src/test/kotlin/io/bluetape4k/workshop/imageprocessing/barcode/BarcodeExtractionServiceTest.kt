package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import io.bluetape4k.images.barcode.BarcodeFormat
import io.bluetape4k.images.barcode.zxing.ZxingBarcodeReader
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class BarcodeExtractionServiceTest {

    private val fixtures = BarcodeExampleFixtures()

    @Test
    fun `extracts QR fixture through provider neutral API`() = kotlinx.coroutines.test.runTest {
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
    fun `returns empty result for a valid image without barcode`() = kotlinx.coroutines.test.runTest {
        val result = BarcodeExtractionService(
            reader = ZxingBarcodeReader(),
            properties = BarcodeExampleProperties(),
        ).extract(fixtures.bytes(BarcodeExampleFixture.NO_RESULT))

        result shouldBeEqualTo BarcodeExtractionResponse(count = 0, results = emptyList())
    }

    @Test
    fun `normalizes malformed bytes without exposing input detail`() = kotlinx.coroutines.test.runTest {
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
    fun `rejects unsupported multipart input before decoding`() = kotlinx.coroutines.test.runTest {
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
}
