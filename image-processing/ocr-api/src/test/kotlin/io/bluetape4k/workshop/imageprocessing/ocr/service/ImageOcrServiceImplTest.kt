package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.images.ocr.OcrConfigurationException
import io.bluetape4k.images.ocr.OcrBoundingBox
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.images.ocr.OcrException
import io.bluetape4k.images.ocr.OcrOptions
import io.bluetape4k.images.ocr.OcrPage
import io.bluetape4k.images.ocr.OcrResult
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.OcrStructuredResult
import io.bluetape4k.images.ocr.OcrTextBlock as SourceOcrTextBlock
import io.bluetape4k.images.ocr.OcrTextLine
import io.bluetape4k.images.ocr.OcrWord
import io.bluetape4k.images.ocr.StructuredOcrEngine
import com.sksamuel.scrimage.ImmutableImage
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private class FakeStructuredOcrEngine(
    private val structuredResult: OcrStructuredResult,
) : StructuredOcrEngine {
    var recognizeCalls: Int = 0
    var structuredCalls: Int = 0
    lateinit var lastOptions: OcrOptions

    override fun recognize(image: ImmutableImage, options: OcrOptions): OcrResult {
        recognizeCalls++
        lastOptions = options
        return OcrResult(structuredResult.text, options)
    }

    override fun recognizeStructured(image: ImmutableImage, options: OcrOptions): OcrStructuredResult {
        structuredCalls++
        lastOptions = options
        return structuredResult.copy(options = options)
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageOcrServiceImplTest {

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
    fun `native disabled validates image then returns unavailable without OCR`() = runSuspendIO {
        val invoked = AtomicBoolean(false)
        val service = service(
            properties = properties(nativeEnabled = false),
            engine = OcrEngine { _, options ->
                invoked.set(true)
                OcrResult("should not run", options)
            },
        )

        val response = service.recognize(
            ImageOcrRequest(
                bytes = tinyPng(),
                contentType = "image/png",
                languages = listOf("eng", "kor"),
            ),
        )

        response.status shouldBeEqualTo OcrStatus.UNAVAILABLE
        response.engine shouldBeEqualTo "disabled"
        response.languages shouldBeEqualTo listOf("eng", "kor")
        response.text shouldBeEqualTo ""
        response.blocks.size shouldBeEqualTo 0
        response.warnings.any { it.contains("disabled", ignoreCase = true) }.shouldBeTrue()
        invoked.get().shouldBeFalse()
    }

    @Test
    fun `native disabled rejects corrupt image before fallback`() = runSuspendIO {
        val service = service(properties = properties(nativeEnabled = false))

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = "not an image".toByteArray(), contentType = "image/png"))
        }
    }

    @Test
    fun `fake native engine returns normalized completed response`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, options ->
                OcrResult("Bluetape OCR\n\nSecond line\n", options)
            },
        )

        val response = service.recognize(
            ImageOcrRequest(
                bytes = tinyPng(),
                contentType = "image/png",
                languages = listOf("eng,kor", "eng"),
            ),
        )

        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.engine shouldBeEqualTo "tesseract"
        response.languages shouldBeEqualTo listOf("eng", "kor")
        response.confidence shouldBeEqualTo null
        response.text shouldBeEqualTo "Bluetape OCR\n\nSecond line"
        response.blocks.map { it.text } shouldBeEqualTo listOf("Bluetape OCR", "Second line")
        response.warnings.any { it.contains("confidence", ignoreCase = true) }.shouldBeTrue()
    }

    @Test
    fun `LINE detail uses structured engine and preserves nullable metadata`() = runSuspendIO {
        val engine = FakeStructuredOcrEngine(structuredResult())
        val response = service(properties(nativeEnabled = true), engine = engine)
            .recognize(request(structuredDetail = OcrStructuredDetail.LINE))

        response.status shouldBeEqualTo OcrStatus.COMPLETED
        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.LINE
        response.pages.single().pageIndex shouldBeEqualTo 0
        response.blocks.single().boundingBox?.width shouldBeEqualTo 30
        response.lines.single().confidence shouldBeEqualTo null
        response.words.size shouldBeEqualTo 0
        response.confidence shouldBeEqualTo null
        engine.lastOptions.structuredDetail shouldBeEqualTo OcrStructuredDetail.LINE
        engine.recognizeCalls shouldBeEqualTo 0
        engine.structuredCalls shouldBeEqualTo 1
    }

    @Test
    fun `WORD detail maps pages blocks lines and words`() = runSuspendIO {
        val engine = FakeStructuredOcrEngine(structuredResult())
        val response = service(properties(nativeEnabled = true), engine = engine)
            .recognize(request(structuredDetail = OcrStructuredDetail.WORD))

        response.effectiveStructuredDetail shouldBeEqualTo OcrStructuredDetail.WORD
        response.pages.size shouldBeEqualTo 1
        response.blocks.size shouldBeEqualTo 1
        response.lines.size shouldBeEqualTo 1
        response.words.single().confidence shouldBeEqualTo 88.0
        response.words.single().boundingBox?.height shouldBeEqualTo 12
        engine.lastOptions.structuredDetail shouldBeEqualTo OcrStructuredDetail.WORD
    }

    @Test
    fun `plain engine returns legacy line fallback for structured request`() = runSuspendIO {
        val response = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, options -> OcrResult("first\nsecond", options) },
        ).recognize(request(structuredDetail = OcrStructuredDetail.WORD))

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
    fun `configuration exception maps to unavailable without leaking native details`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true, tessdataPath = "/secret/tessdata"),
            engine = OcrEngine { _, _ ->
                throw OcrConfigurationException("native path /secret/tessdata failed")
            },
        )

        val response = service.recognize(request())

        response.status shouldBeEqualTo OcrStatus.UNAVAILABLE
        response.warnings.joinToString(" ") shouldContain "Native OCR is unavailable"
        response.warnings.joinToString(" ").contains("/secret").shouldBeFalse()
    }

    @Test
    fun `generic OCR exception maps to failed without stack trace`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, _ ->
                throw OcrException("boom at /tmp/native")
            },
        )

        val response = service.recognize(request())

        response.status shouldBeEqualTo OcrStatus.FAILED
        response.warnings.joinToString(" ") shouldContain "OCR failed"
        response.warnings.joinToString(" ").contains("/tmp").shouldBeFalse()
    }

    @Test
    fun `invalid languages are rejected`() = runSuspendIO {
        val service = service(properties(nativeEnabled = false))

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(languages = listOf("eng", "../kor")))
        }
    }

    @Test
    fun `empty bytes are rejected before OCR`() = runSuspendIO {
        val invoked = AtomicBoolean(false)
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, options ->
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
    fun `oversized bytes are rejected before OCR`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true, maxUploadBytes = 2),
            engine = OcrEngine { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = byteArrayOf(1, 2, 3)))
        }
    }

    @Test
    fun `corrupt image bytes with image content type are rejected before OCR`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = "not an image".toByteArray(), contentType = "image/png"))
        }
    }

    @Test
    fun `spoofed declared content type is rejected before OCR`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = false),
            engine = OcrEngine { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = tinyPng(), contentType = "image/jpeg"))
        }
    }

    @Test
    fun `decoded images above pixel limit are rejected before OCR`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true, maxImagePixels = 1),
            engine = OcrEngine { _, options -> OcrResult("should not run", options) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.recognize(request(bytes = tinyPng()))
        }
    }

    @Test
    fun `oversized PNG dimensions are rejected before full decode`() = runSuspendIO {
        val invoked = AtomicBoolean(false)
        val service = service(
            properties = properties(nativeEnabled = true, maxImagePixels = 12_000_000),
            engine = OcrEngine { _, options ->
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
    fun `blocking OCR timeout releases the native lane`() = runSuspendIO {
        val firstEntered = AtomicBoolean(false)
        val secondEntered = AtomicBoolean(false)
        val neverReleased = CountDownLatch(1)
        val service = service(
            properties = properties(nativeEnabled = true, timeout = Duration.ofMillis(500)),
            engine = OcrEngine { _, options ->
                if (firstEntered.compareAndSet(false, true)) {
                    neverReleased.await(2, TimeUnit.SECONDS)
                } else {
                    secondEntered.set(true)
                }
                OcrResult("ok", options)
            },
        )

        val timedOut = service.recognize(request())
        val completed = service.recognize(request())

        timedOut.status shouldBeEqualTo OcrStatus.FAILED
        completed.status shouldBeEqualTo OcrStatus.COMPLETED
        secondEntered.get().shouldBeTrue()
    }

    @Test
    fun `cancellation is rethrown`() = runSuspendIO {
        val service = service(
            properties = properties(nativeEnabled = true),
            engine = OcrEngine { _, _ -> throw CancellationException("cancelled") },
        )

        assertFailsWith<CancellationException> {
            service.recognize(request())
        }
    }

    private fun service(
        properties: ImageOcrProperties,
        engine: OcrEngine? = null,
    ): ImageOcrService =
        ImageOcrServiceImpl(
            properties = properties,
            ocrEngineProvider = OcrEngineProvider { engine },
            requestIdGenerator = RequestIdGenerator { "ocr-test-request" },
        )

    private fun properties(
        nativeEnabled: Boolean,
        maxUploadBytes: Long = 5_242_880,
        maxImagePixels: Long = 12_000_000,
        timeout: Duration = Duration.ofSeconds(10),
        tessdataPath: String? = null,
    ): ImageOcrProperties =
        ImageOcrProperties(
            nativeEnabled = nativeEnabled,
            maxUploadBytes = maxUploadBytes,
            maxImagePixels = maxImagePixels,
            timeout = timeout,
            languages = listOf("eng"),
            tessdataPath = tessdataPath,
        )

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

    private fun tinyPng(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, 8, 8)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

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

    private fun ByteArray.writeUInt32BE(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
