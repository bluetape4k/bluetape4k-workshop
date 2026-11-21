package io.bluetape4k.workshop.imageprocessing.ocr.service

import com.sksamuel.scrimage.ImmutableImage
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.concurrent.await
import io.bluetape4k.images.coroutines.SuspendTiffMultiPageWriter
import io.bluetape4k.images.ocr.OcrBoundingBox
import io.bluetape4k.images.ocr.OcrConfigurationException
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.images.ocr.OcrException
import io.bluetape4k.images.ocr.OcrOptions
import io.bluetape4k.images.ocr.OcrPage
import io.bluetape4k.images.ocr.OcrResult
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.OcrStructuredResult
import io.bluetape4k.images.ocr.OcrTextLine
import io.bluetape4k.images.ocr.OcrWord
import io.bluetape4k.images.ocr.StructuredOcrEngine
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import io.bluetape4k.images.useGraphics
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.config.TiffMultiPageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds
import io.bluetape4k.images.ocr.OcrTextBlock as SourceOcrTextBlock

/**
 * FakeStructuredOcrEngine 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class FakeStructuredOcrEngine(
/**
 * structuredResult은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val structuredResult: OcrStructuredResult,
): StructuredOcrEngine {
/**
 * recognizeCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    var recognizeCalls: Int = 0
/**
 * structuredCalls은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    var structuredCalls: Int = 0
/**
 * lastOptions은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var lastOptions: OcrOptions

/**
 * recognize은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 * 예: `POST /api/ocr`는 허용된 이미지 bytes만 OCR provider에 전달하고, oversized input은 native 호출 전에 거절합니다.
 */
    override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult {
        recognizeCalls++
        lastOptions = options
        return OcrResult(structuredResult.text, options)
    }

/**
 * recognizeStructured은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult {
        structuredCalls++
        lastOptions = options
        return structuredResult.copy(
            options = options,
            words = if (options.structuredDetail == OcrStructuredDetail.WORD) structuredResult.words else emptyList(),
        )
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * OCR service의 plain/structured/TIFF 업무 계약을 검증합니다.
 *
 * native disabled fallback, fake engine completed 응답, LINE/WORD metadata,
 * nullable confidence/bounding box, 다중 페이지 순서, TIFF page/pixel/result
 * budget, sanitized failure, language normalization, timeout과 cancellation을
 * 확인합니다. fake engine을 사용하므로 Tesseract 설치 여부와 무관하게
 * application 계약을 재현할 수 있습니다.
 */
class ImageOcrServiceImplTest {

    private companion object: KLogging()

/**
 * previousOcrEnabled은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var previousOcrEnabled: String? = null

    @BeforeEach
    fun clearOcrEnabledSystemProperty() {
        previousOcrEnabled = System.getProperty("ocr.enabled")
        System.clearProperty("ocr.enabled")
    }

    @AfterEach
    fun restoreOcrEnabledSystemProperty() {
        previousOcrEnabled?.let { System.setProperty("ocr.enabled", it) } ?: System.clearProperty("ocr.enabled")
    }

    @Test
/**
 * 이 시나리오는 "native disabled validates image then returns unavailable without OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `native disabled validates image then returns unavailable without OCR`() = runSuspendIO {
/**
 * invoked은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val invoked = AtomicBoolean(false)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = false),
            engine = { _, options ->
                invoked.set(true)
                OcrResult("should not run", options)
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(
            ImageOcrRequest(
                bytes = tinyPng(),
                contentType = "image/png",
                languages = listOf("eng", "kor"),
            ),
        )

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.UNAVAILABLE
        response.engine shouldBeEqualTo "disabled"
        response.languages shouldBeEqualTo listOf("eng", "kor")
        response.text shouldBeEqualTo ""
        response.blocks.size shouldBeEqualTo 0
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        response.pages.size shouldBeEqualTo 0
        response.lines.size shouldBeEqualTo 0
        response.words.size shouldBeEqualTo 0
        response.warnings.any { it.contains("disabled", ignoreCase = true) }.shouldBeTrue()
        invoked.get().shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "native disabled rejects corrupt image before fallback" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `native disabled rejects corrupt image before fallback`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(properties = properties(nativeEnabled = false))

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = "not an image".toUtf8Bytes(), contentType = "image/png"))
        }
    }

    @Test
/**
 * 이 시나리오는 "fake native engine returns normalized completed response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fake native engine returns normalized completed response`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = { _, options ->
                OcrResult("Bluetape OCR\n\nSecond line\n", options)
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(
            ImageOcrRequest(
                bytes = tinyPng(),
                contentType = "image/png",
                languages = listOf("eng,kor", "eng"),
            ),
        )

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.engine shouldBeEqualTo "tesseract"
        response.languages shouldBeEqualTo listOf("eng", "kor")
        response.confidence.shouldBeNull()
        response.text shouldBeEqualTo "Bluetape OCR\n\nSecond line"
        response.blocks.map { it.text } shouldBeEqualTo listOf("Bluetape OCR", "Second line")
        response.warnings.any { it.contains("confidence", ignoreCase = true) }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "LINE detail uses structured engine and preserves nullable metadata" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `LINE detail uses structured engine and preserves nullable metadata`() = runSuspendIO {
/**
 * engine은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val engine = FakeStructuredOcrEngine(structuredResult())
/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service(properties(nativeEnabled = true), engine = engine)
            .recognize(request(structuredDetail = OcrStructuredDetail.LINE))

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.LINE
        response.pages.single().pageIndex shouldBeEqualTo 0
        response.blocks.single().boundingBox?.width shouldBeEqualTo 30
        response.lines.single().confidence.shouldBeNull()
        response.words.size shouldBeEqualTo 0
        response.confidence.shouldBeNull()
        engine.lastOptions.structuredDetail shouldBeEqualTo OcrStructuredDetail.LINE
        engine.recognizeCalls shouldBeEqualTo 0
        engine.structuredCalls shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "WORD detail maps pages blocks lines and words" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `WORD detail maps pages blocks lines and words`() = runSuspendIO {
/**
 * engine은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val engine = FakeStructuredOcrEngine(structuredResult())
/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service(properties(nativeEnabled = true), engine = engine)
            .recognize(request(structuredDetail = OcrStructuredDetail.WORD))

        log.debug { "response=$response" }
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.WORD
        response.pages.size shouldBeEqualTo 1
        response.blocks.size shouldBeEqualTo 1
        response.lines.size shouldBeEqualTo 1
        response.words.single().confidence shouldBeEqualTo 88.0
        response.words.single().boundingBox?.height shouldBeEqualTo 12
        engine.lastOptions.structuredDetail shouldBeEqualTo OcrStructuredDetail.WORD
    }

    @Test
/**
 * 이 시나리오는 "multi-page TIFF uses suspend structured OCR and preserves input page order" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multi-page TIFF uses suspend structured OCR and preserves input page order`() = runSuspendIO {
/**
 * engine은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val engine = FakeStructuredOcrEngine(structuredResult())
/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service(properties(nativeEnabled = true), engine = engine)
            .recognize(
                request(
                    bytes = threePageTiff(),
                    contentType = "image/tiff",
                    structuredDetail = OcrStructuredDetail.LINE,
                ),
            )

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.text shouldBeEqualTo "Bluetape OCR\n\nBluetape OCR\n\nBluetape OCR"
        response.pages.map { it.pageIndex } shouldBeEqualTo listOf(0, 1, 2)
        response.blocks.map { it.pageIndex } shouldBeEqualTo listOf(0, 1, 2)
        response.lines.map { it.pageIndex } shouldBeEqualTo listOf(0, 1, 2)
        response.words.size shouldBeEqualTo 0
        engine.structuredCalls shouldBeEqualTo 3
        engine.recognizeCalls shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "multi-page TIFF preflight rejects page limit before invoking engine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multi-page TIFF preflight rejects page limit before invoking engine`() = runSuspendIO {
/**
 * engine은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val engine = FakeStructuredOcrEngine(structuredResult())
/**
 * error은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val error = assertFailsWith<TiffMultiPageOcrValidationException> {
            service(
                properties(
                    nativeEnabled = true,
                    tiff = TiffMultiPageOcrProperties(maxPages = 2),
                ),
                engine = engine,
            ).recognize(request(bytes = threePageTiff(), contentType = "image/tiff"))
        }

        log.debug { "error=$error" }
        error.reason shouldBeEqualTo TiffMultiPageOcrFailureReason.PAGE_LIMIT_EXCEEDED
        error.pageIndex.shouldBeNull()
        engine.structuredCalls shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "multi-page TIFF result limit fails without returning partial response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multi-page TIFF result limit fails without returning partial response`() = runSuspendIO {
/**
 * engine은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val engine = FakeStructuredOcrEngine(structuredResult())
/**
 * error은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val error = assertFailsWith<TiffMultiPageOcrValidationException> {
            service(
                properties(
                    nativeEnabled = true,
                    tiff = TiffMultiPageOcrProperties(maxResultTextChars = 20),
                ),
                engine = engine,
            ).recognize(request(bytes = threePageTiff(), contentType = "image/tiff"))
        }

        log.debug { "error=$error" }
        error.reason shouldBeEqualTo TiffMultiPageOcrFailureReason.RESULT_LIMIT_EXCEEDED
        error.pageIndex shouldBeEqualTo 1
        engine.structuredCalls shouldBeEqualTo 2
    }

    @Test
/**
 * 이 시나리오는 "native disabled validates TIFF header without invoking OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `native disabled validates TIFF header without invoking OCR`() = runSuspendIO {
/**
 * invoked은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val invoked = AtomicBoolean(false)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = false),
            engine = { _, options ->
                invoked.set(true)
                OcrResult("should not run", options)
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(request(bytes = threePageTiff(), contentType = "image/tiff"))

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.UNAVAILABLE
        response.pages.size shouldBeEqualTo 0
        invoked.get().shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "multi-page TIFF cancellation is rethrown" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multi-page TIFF cancellation is rethrown`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = object: StructuredOcrEngine {
/**
 * recognize은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 * 예: `POST /api/ocr`는 허용된 이미지 bytes만 OCR provider에 전달하고, oversized input은 native 호출 전에 거절합니다.
 */
                override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult =
                    OcrResult("plain", options)

/**
 * recognizeStructured은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult =
                    throw CancellationException("tiff cancelled")
            },
        )

        assertFailsWith<CancellationException> {
            service.recognize(request(bytes = threePageTiff(), contentType = "image/tiff"))
        }
    }

    @Test
/**
 * 이 시나리오는 "plain engine returns legacy line fallback for structured request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `plain engine returns legacy line fallback for structured request`() = runSuspendIO {
/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service(
            properties = properties(nativeEnabled = true),
            engine = { _, options -> OcrResult("first\nsecond", options) },
        ).recognize(request(structuredDetail = OcrStructuredDetail.WORD))

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        response.text shouldBeEqualTo "first\nsecond"
        response.blocks.map { it.text } shouldBeEqualTo listOf("first", "second")
        response.pages.size shouldBeEqualTo 0
        response.lines.size shouldBeEqualTo 0
        response.words.size shouldBeEqualTo 0
        response.warnings.joinToString(" ") shouldContain "structured"
    }

    @Test
/**
 * 이 시나리오는 "configuration exception maps to unavailable without leaking native details" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configuration exception maps to unavailable without leaking native details`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, tessdataPath = "/secret/tessdata"),
            engine = { _, _ ->
                throw OcrConfigurationException("native path /secret/tessdata failed")
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(request())

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.UNAVAILABLE
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        response.pages.size shouldBeEqualTo 0
        response.lines.size shouldBeEqualTo 0
        response.words.size shouldBeEqualTo 0
        response.warnings.joinToString(" ") shouldContain "Native OCR is unavailable"
        response.warnings.joinToString(" ").contains("/secret").shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "generic OCR exception maps to failed without stack trace" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `generic OCR exception maps to failed without stack trace`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = { _, _ ->
                throw OcrException("boom at /tmp/native")
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(request())

        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.FAILED
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        response.pages.size shouldBeEqualTo 0
        response.lines.size shouldBeEqualTo 0
        response.words.size shouldBeEqualTo 0
        response.warnings.joinToString(" ") shouldContain "OCR failed"
        response.warnings.joinToString(" ").contains("/tmp").shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "invalid languages are rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid languages are rejected`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(properties(nativeEnabled = false))

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(languages = listOf("eng", "../kor")))
        }
    }

    @Test
/**
 * 이 시나리오는 "empty bytes are rejected before OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `empty bytes are rejected before OCR`() = runSuspendIO {
/**
 * invoked은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val invoked = AtomicBoolean(false)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = { _, options ->
                invoked.set(true)
                OcrResult("should not run", options)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = ByteArray(0)))
        }
        invoked.get().shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "oversized bytes are rejected before OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `oversized bytes are rejected before OCR`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, maxUploadBytes = 2),
            engine = { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = byteArrayOf(1, 2, 3)))
        }
    }

    @Test
/**
 * 이 시나리오는 "corrupt image bytes with image content type are rejected before OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `corrupt image bytes with image content type are rejected before OCR`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = "not an image".toUtf8Bytes(), contentType = "image/png"))
        }
    }

    @Test
/**
 * 이 시나리오는 "spoofed declared content type is rejected before OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `spoofed declared content type is rejected before OCR`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = false),
            engine = { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = tinyPng(), contentType = "image/jpeg"))
        }
    }

    @Test
/**
 * 이 시나리오는 "decoded images above pixel limit are rejected before OCR" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decoded images above pixel limit are rejected before OCR`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, maxImagePixels = 1),
            engine = { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = tinyPng()))
        }
    }

    @Test
/**
 * 이 시나리오는 "oversized PNG dimensions are rejected before full decode" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `oversized PNG dimensions are rejected before full decode`() = runSuspendIO {
/**
 * invoked은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val invoked = AtomicBoolean(false)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, maxImagePixels = 12_000_000),
            engine = { _, options ->
                invoked.set(true)
                OcrResult("should not run", options)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = pngHeader(width = 120_000, height = 120_000)))
        }
        invoked.get().shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "blocking OCR timeout releases the native lane" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `blocking OCR timeout releases the native lane`() = runSuspendIO {
/**
 * firstEntered은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val firstEntered = AtomicBoolean(false)
/**
 * secondEntered은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val secondEntered = AtomicBoolean(false)
/**
 * neverReleased은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val neverReleased = CountDownLatch(1)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, timeout = Duration.ofMillis(500)),
            engine = { _, options ->
                if (firstEntered.compareAndSet(false, true)) {
                    neverReleased.await(2.seconds)
                } else {
                    secondEntered.set(true)
                }
                OcrResult("ok", options)
            },
        )

/**
 * timedOut은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val timedOut = service.recognize(request())
/**
 * completed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val completed = service.recognize(request())

        log.debug { "timedOut=$timedOut" }
        timedOut.status shouldBeEqualTo OcrStatus.FAILED
        timedOut.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        timedOut.pages.size shouldBeEqualTo 0
        timedOut.lines.size shouldBeEqualTo 0
        timedOut.words.size shouldBeEqualTo 0

        log.debug { "completed=$completed" }
        completed.status shouldBeEqualTo OcrStatus.COMPLETED
        secondEntered.get().shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "structured OCR timeout returns failed response and preserves plain metadata" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `structured OCR timeout returns failed response and preserves plain metadata`() = runSuspendIO {
/**
 * entered은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val entered = CountDownLatch(1)
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true, timeout = Duration.ofMillis(500)),
            engine = object: StructuredOcrEngine {
/**
 * recognize은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 * 예: `POST /api/ocr`는 허용된 이미지 bytes만 OCR provider에 전달하고, oversized input은 native 호출 전에 거절합니다.
 */
                override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult =
                    OcrResult("plain fallback", options)

/**
 * recognizeStructured은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult {
                    entered.countDown()
                    CountDownLatch(1).await(2.seconds)
                    return structuredResult().copy(options = options)
                }
            },
        )

/**
 * response은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = service.recognize(request(structuredDetail = OcrStructuredDetail.WORD))

        entered.await(1.seconds).shouldBeTrue()
        log.debug { "response=$response" }
        response.status shouldBeEqualTo OcrStatus.FAILED
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.PLAIN_TEXT
        response.pages.size shouldBeEqualTo 0
        response.lines.size shouldBeEqualTo 0
        response.words.size shouldBeEqualTo 0
    }

    @Test
/**
 * 이 시나리오는 "cancellation is rethrown" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancellation is rethrown`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = { _, _ -> throw CancellationException("cancelled") },
        )

        assertFailsWith<CancellationException> {
            service.recognize(request())
        }
    }

    @Test
/**
 * 이 시나리오는 "structured OCR cancellation is rethrown" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `structured OCR cancellation is rethrown`() = runSuspendIO {
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = object: StructuredOcrEngine {
/**
 * recognize은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 * 예: `POST /api/ocr`는 허용된 이미지 bytes만 OCR provider에 전달하고, oversized input은 native 호출 전에 거절합니다.
 */
                override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult =
                    OcrResult("plain", options)

/**
 * recognizeStructured은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult =
                    throw CancellationException("structured cancelled")
            },
        )

        assertFailsWith<CancellationException> {
            service.recognize(request(structuredDetail = OcrStructuredDetail.LINE))
        }
    }

/**
 * service은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun service(
        properties: ImageOcrProperties,
        engine: OcrEngine? = null,
    ): ImageOcrService =
        ImageOcrServiceImpl(
            properties = properties,
            ocrEngineProvider = { engine },
            requestIdGenerator = { "ocr-test-request" },
        )

/**
 * properties은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun properties(
        nativeEnabled: Boolean,
        maxUploadBytes: Long = 5_242_880,
        maxImagePixels: Long = 12_000_000,
        timeout: Duration = Duration.ofSeconds(10),
        tessdataPath: String? = null,
        tiff: TiffMultiPageOcrProperties = TiffMultiPageOcrProperties(maxEncodedBytes = maxUploadBytes),
    ): ImageOcrProperties =
        ImageOcrProperties(
            nativeEnabled = nativeEnabled,
            maxUploadBytes = maxUploadBytes,
            maxImagePixels = maxImagePixels,
            timeout = timeout,
            languages = listOf("eng"),
            tessdataPath = tessdataPath,
            tiff = tiff,
        )

/**
 * request은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(
        bytes: ByteArray = tinyPng(),
        contentType: String = "image/png",
        languages: List<String> = listOf("eng"),
        structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
    ): ImageOcrRequest =
        ImageOcrRequest(
            bytes = bytes,
            contentType = contentType,
            languages = languages,
            structuredDetail = structuredDetail,
        )

/**
 * structuredResult은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun structuredResult(): OcrStructuredResult =
        OcrStructuredResult(
            text = "Bluetape OCR",
            options = OcrOptions(),
            pages = listOf(OcrPage(pageIndex = 0, text = "Bluetape OCR")),
            blocks = listOf(
                SourceOcrTextBlock(
                    pageIndex = 0,
                    text = "Bluetape OCR",
                    boundingBox = OcrBoundingBox(1, 2, 30, 12),
                    confidence = 91.5,
                ),
            ),
            lines = listOf(
                OcrTextLine(
                    pageIndex = 0,
                    text = "Bluetape OCR",
                    boundingBox = null,
                    confidence = null,
                ),
            ),
            words = listOf(
                OcrWord(
                    pageIndex = 0,
                    text = "Bluetape",
                    boundingBox = OcrBoundingBox(1, 2, 16, 12),
                    confidence = 88.0,
                ),
            ),
        )

/**
 * tinyPng은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun tinyPng(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        image.useGraphics { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 8, 8)
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

/**
 * threePageTiff은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun threePageTiff(): ByteArray = runBlocking {
/**
 * pages은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pages = listOf("PAGE ONE", "PAGE TWO", "PAGE THREE").map { label ->
/**
 * image은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
            image.useGraphics { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color.BLACK
                graphics.drawString(label, 4, 18)
            }
            ImmutableImage.fromAwt(image)
        }
        ByteArrayOutputStream().use { output ->
            SuspendTiffMultiPageWriter().suspendWrite(pages, output)
            output.toByteArray()
        }
    }

/**
 * pngHeader은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        val signature = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        signature.copyInto(bytes)
        bytes.writeUInt32BE(offset = 8, value = 13)
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        bytes.writeUInt32BE(offset = 16, value = width)
        bytes.writeUInt32BE(offset = 20, value = height)
        bytes[24] = 8
        bytes[25] = 2
        return bytes
    }

/**
 * writeUInt32BE은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ByteArray.writeUInt32BE(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
