package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.images.ocr.OcrConfigurationException
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.images.ocr.OcrException
import io.bluetape4k.images.ocr.OcrResult
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
            properties = properties(nativeEnabled = true, timeout = Duration.ofMillis(100)),
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
    ): ImageOcrRequest =
        ImageOcrRequest(
            bytes = bytes,
            contentType = contentType,
            languages = languages,
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
