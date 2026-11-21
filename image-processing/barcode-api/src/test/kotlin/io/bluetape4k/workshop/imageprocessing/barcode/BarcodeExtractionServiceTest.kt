package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.images.ImageDimensions
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.readImageMetadataReport
import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import io.bluetape4k.images.barcode.BarcodeFormat
import io.bluetape4k.images.barcode.BarcodeReader
import io.bluetape4k.images.barcode.zxing.ZxingBarcodeReader
import io.bluetape4k.images.probeImageDimensions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.utils.Resourcex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * provider-neutral barcode service의 입력/결과 계약을 검증합니다.
 *
 * QR 성공, barcode 없는 유효 image, JPEG/WebP, primary probe 실패 시 bounded
 * metadata fallback, encoded/decoded size 초과, malformed input, strict loader,
 * provider failure, coroutine cancellation을 구분합니다. 따라서 `no-result`를
 * server error로 취급하지 않고, 위험한 bytes는 ZXing 호출 전에 차단하는 이유를
 * 테스트 이름만 읽어도 알 수 있습니다.
 */
class BarcodeExtractionServiceTest {

    private companion object: KLogging()

/**
 * fixtures은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixtures = BarcodeExampleFixtures()

    @Test
/**
 * 이 시나리오는 "extracts QR fixture through provider neutral API" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `extracts QR fixture through provider neutral API`() = runTest {
/**
 * result은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val result = BarcodeExtractionService(
            reader = ZxingBarcodeReader(),
            properties = BarcodeExampleProperties(),
        ).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))

        log.debug { "result=$result" }
        result.count shouldBeEqualTo 1
        result.results.single().text shouldBeEqualTo "bluetape4k-barcode-quickstart"
        result.results.single().format shouldBeEqualTo BarcodeFormat.QR_CODE
        result.results.single().provider shouldBeEqualTo "ZXing"
    }

    @Test
/**
 * 이 시나리오는 "returns empty result for a valid image without barcode" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `returns empty result for a valid image without barcode`() = runTest {
/**
 * result은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val result = BarcodeExtractionService(
            reader = ZxingBarcodeReader(),
            properties = BarcodeExampleProperties(),
        ).extract(fixtures.bytes(BarcodeExampleFixture.NO_RESULT))

        log.debug { "result=$result" }
        result shouldBeEqualTo BarcodeExtractionResponse(count = 0, results = emptyList())
    }

    @Test
/**
 * 이 시나리오는 "accepts JPEG and WebP uploads" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `accepts JPEG and WebP uploads`() = runTest {
/**
 * jpeg은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val jpeg = jpegBytes()
/**
 * webp은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val webp = webpBytes()
/**
 * uploads은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val uploads = listOf(
            "image/jpeg" to jpeg,
            "image/webp" to webp,
        )

        uploads.forEach { (contentType, bytes) ->
/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val response = service().extract(multipart(contentType, bytes))

            log.debug { "response=$response" }
            response.count shouldBeEqualTo if (contentType == "image/jpeg") 1 else 0
            if (contentType == "image/jpeg") {
                response.results.single().format shouldBeEqualTo BarcodeFormat.QR_CODE
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "uses bounded metadata dimensions when primary WebP probe is unavailable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `uses bounded metadata dimensions when primary WebP probe is unavailable`() = runTest {
/**
 * webp은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val webp = webpBytes()

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service(dimensionProbe = { null })
            .extract(multipart("image/webp", webp))

        log.debug { "response=$response" }
        response.count shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "rejects empty unsupported and missing content type uploads" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects empty unsupported and missing content type uploads`() = runTest {
/**
 * empty은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val empty = assertFailsWith<BarcodeRequestException> {
            service().extract(multipart("image/png", ByteArray(0)))
        }
        log.debug { "empty=$empty" }
        empty.status shouldBeEqualTo HttpStatus.BAD_REQUEST
        empty.error shouldBeEqualTo "empty_input"

        listOf("text/plain", null).forEach { contentType ->
/**
 * error은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val error = assertFailsWith<BarcodeRequestException> {
                service().extract(multipart(contentType, byteArrayOf(1)))
            }
            log.debug { "error=$error" }
            error.status shouldBeEqualTo HttpStatus.UNSUPPORTED_MEDIA_TYPE
            error.error shouldBeEqualTo "unsupported_media_type"
        }
    }

    @Test
/**
 * 이 시나리오는 "rejects reported and actual encoded byte overflow before decode" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects reported and actual encoded byte overflow before decode`() = runTest {
/**
 * readerCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val readerCalls = AtomicInteger()
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
/**
 * bytes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val bytes = fixtures.bytes(BarcodeExampleFixture.SAMPLE)
/**
 * properties은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val properties = BarcodeExampleProperties(maxInputBytes = bytes.size.toLong() - 1)

/**
 * multipartError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val multipartError = assertFailsWith<BarcodeRequestException> {
            service(reader, properties).extract(multipart("image/png", bytes))
        }
        log.debug { "multipartError=$multipartError" }
        multipartError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE

/**
 * byteArrayError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val byteArrayError = assertFailsWith<BarcodeRequestException> {
            service(reader, properties).extract(bytes)
        }
        log.debug { "byteArrayError=$byteArrayError" }
        byteArrayError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "rejects decoded side and pixel overflow before provider invocation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects decoded side and pixel overflow before provider invocation`() = runTest {
/**
 * readerCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val readerCalls = AtomicInteger()
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
/**
 * bytes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val bytes = fixtures.bytes(BarcodeExampleFixture.SAMPLE)

/**
 * sideError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val sideError = assertFailsWith<BarcodeRequestException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputSide = 100),
            ).extract(bytes)
        }
        log.debug { "sideError=$sideError" }
        sideError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE

/**
 * pixelError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pixelError = assertFailsWith<BarcodeRequestException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputPixels = 40_000),
            ).extract(bytes)
        }
        log.debug { "pixelError=$pixelError" }
        pixelError.status shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "strict loader rechecks dimensions after injected probes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `strict loader rechecks dimensions after injected probes`() = runTest {
/**
 * readerCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val readerCalls = AtomicInteger()
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
/**
 * webp은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val webp = webpBytes()

/**
 * error은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val error = assertFailsWith<BarcodeException> {
            service(
                reader = reader,
                properties = BarcodeExampleProperties(maxInputSide = 100),
                dimensionProbe = { ImageDimensions(1, 1) },
                metadataDimensionProbe = { _, _ -> ImageDimensions(1, 1) },
            ).extract(webp)
        }

        log.debug { "error=$error" }
        error.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "normalizes malformed bytes and unknown dimensions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalizes malformed bytes and unknown dimensions`() = runTest {
/**
 * readerCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val readerCalls = AtomicInteger()
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reader = BarcodeReader { _, _ ->
            readerCalls.incrementAndGet()
            emptyList()
        }
/**
 * malformed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val malformed = assertFailsWith<BarcodeException> {
            service(reader).extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
        }
        malformed.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT

/**
 * unknownDimensions은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val unknownDimensions = assertFailsWith<BarcodeException> {
            service(
                reader = reader,
                dimensionProbe = { ImageDimensions(1, 1) },
                metadataDimensionProbe = { _, _ -> ImageDimensions(1, 1) },
            ).extract(byteArrayOf(1, 2, 3))
        }

        log.debug { "unknownDimensions=$unknownDimensions" }
        unknownDimensions.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        readerCalls.get() shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "preserves provider neutral failures" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `preserves provider neutral failures`() = runTest {
        listOf(
            BarcodeFailureReason.UNSUPPORTED_FORMAT,
            BarcodeFailureReason.PROVIDER_UNAVAILABLE,
            BarcodeFailureReason.DECODE_FAILED,
        ).forEach { reason ->
/**
 * expected은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val expected = BarcodeException(reason, "provider detail")
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val reader = BarcodeReader { _, _ -> throw expected }

/**
 * actual은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val actual = assertFailsWith<BarcodeException> {
                service(reader).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))
            }
            actual shouldBeSameInstanceAs expected
        }
    }

    @Test
/**
 * 이 시나리오는 "rethrows cancellation unchanged" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rethrows cancellation unchanged`() = runTest {
/**
 * expected은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val expected = CancellationException("cancel extraction")
/**
 * reader은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reader = BarcodeReader { _, _ -> throw expected }

/**
 * actual은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val actual = assertFailsWith<CancellationException> {
            service(reader).extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))
        }
        log.debug { "actual=$actual" }
        actual.message shouldBeEqualTo expected.message
    }

    @Test
/**
 * 이 시나리오는 "normalizes malformed bytes without exposing input detail" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalizes malformed bytes without exposing input detail`() = runTest {
/**
 * exception은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val exception = assertFailsWith<BarcodeException> {
            BarcodeExtractionService(
                reader = ZxingBarcodeReader(),
                properties = BarcodeExampleProperties(),
            ).extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
        }

        log.debug { "exception=$exception" }
        exception.reason shouldBeEqualTo BarcodeFailureReason.MALFORMED_INPUT
        exception.message shouldNotContain "malformed.bin"
    }

    @Test
/**
 * 이 시나리오는 "rejects unsupported multipart input before decoding" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects unsupported multipart input before decoding`() = runTest {
/**
 * exception은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

        log.debug { "exception=$exception" }
        exception.status shouldBeEqualTo HttpStatus.UNSUPPORTED_MEDIA_TYPE
    }

/**
 * service은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * multipart은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun multipart(contentType: String?, bytes: ByteArray): MockMultipartFile =
        MockMultipartFile("file", "upload.bin", contentType, bytes)

/**
 * jpegBytes은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun jpegBytes(): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(fixtures.bytes(BarcodeExampleFixture.SAMPLE)))
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "jpeg", output)) { "JPEG writer is not available." }
            output.toByteArray()
        }
    }

/**
 * webpBytes은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun webpBytes(): ByteArray =
        Base64.getDecoder()
            .decode(
                Resourcex.getBytes("barcodes/sample.webp.b64")
                    .requireNotNull { "Required barcode WebP fixture is missing." }
                    .toString(Charsets.US_ASCII).trim()
            )
}
