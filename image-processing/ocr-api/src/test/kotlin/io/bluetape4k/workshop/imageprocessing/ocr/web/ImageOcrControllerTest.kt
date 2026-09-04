package io.bluetape4k.workshop.imageprocessing.ocr.web

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.TiffMultiPageOcrException
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextBlock
import io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [ImageOcrController::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageOcrControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: ImageOcrService

    @MockkBean
    private lateinit var properties: ImageOcrProperties

    @BeforeEach
    fun setUpProperties() {
        every { properties.maxUploadBytes } returns 5_242_880
    }

    @Test
    fun `multipart POST returns OCR response`() {
        coEvery { service.recognize(any()) } returns response()

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile())
                .param("language", "eng"),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.requestId").value("ocr-test-request"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.blocks[0].text").value("Bluetape OCR"))
            .andExpect(jsonPath("$.effectiveStructuredDetail").value("PLAIN_TEXT"))
            .andExpect(jsonPath("$.pages").isArray)
            .andExpect(jsonPath("$.pages").isEmpty)
    }

    @Test
    fun `structuredDetail parameter reaches service`() {
        coEvery { service.recognize(any()) } returns response()

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile())
                .param("structuredDetail", "WORD"),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)

        coVerify {
            service.recognize(match { it.structuredDetail == OcrStructuredDetail.WORD })
        }
    }

    @Test
    fun `missing structuredDetail preserves plain text default`() {
        coEvery { service.recognize(any()) } returns response()

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile()),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)

        coVerify {
            service.recognize(match { it.structuredDetail == OcrStructuredDetail.PLAIN_TEXT })
        }
    }

    @Test
    fun `language parameters preserve mixed repeated and comma-separated order`() {
        coEvery { service.recognize(any()) } returns response(languages = listOf("eng", "kor", "jpn"))

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile())
                .param("language", "eng,kor")
                .param("language", "eng")
                .param("language", "jpn"),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)

        coVerify {
            service.recognize(
                match {
                    it.languages == listOf("eng,kor", "eng", "jpn")
                },
            )
        }
    }

    @Test
    fun `empty upload returns bad request`() {
        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(MockMultipartFile("file", "empty.png", "image/png", ByteArray(0))),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `non image content type returns bad request`() {
        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(MockMultipartFile("file", "text.txt", "text/plain", "hello".toByteArray())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `unsupported image subtype returns bad request`() {
        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(MockMultipartFile("file", "icon.gif", "image/gif", "gif".toByteArray())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `oversized upload returns bad request before reading bytes`() {
        every { properties.maxUploadBytes } returns 2

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile(bytes = byteArrayOf(1, 2, 3))),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Image upload exceeds 2 bytes"))

        coVerify(exactly = 0) { service.recognize(any()) }
    }

    @Test
    fun `service validation failure returns bad request`() {
        coEvery { service.recognize(any()) } throws IllegalArgumentException("Undecodable image")

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile(bytes = "not an image".toByteArray())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Undecodable image"))
    }

    @Test
    fun `TIFF validation failure returns sanitized reason phase and page`() {
        coEvery { service.recognize(any()) } throws TiffMultiPageOcrValidationException(
            reason = TiffMultiPageOcrFailureReason.PIXELS_PER_PAGE_LIMIT_EXCEEDED,
            pageIndex = 2,
            message = "internal payload and path must not leak",
        )

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile(bytes = tiffHeader(), contentType = "image/tiff")),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("TIFF OCR input was rejected."))
            .andExpect(jsonPath("$.reason").value("PIXELS_PER_PAGE_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.phase").value("metadata"))
            .andExpect(jsonPath("$.pageIndex").value(2))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal"))))
    }

    @Test
    fun `TIFF processing failure returns sanitized engine reason`() {
        coEvery { service.recognize(any()) } throws TiffMultiPageOcrException(
            reason = TiffMultiPageOcrFailureReason.ENGINE_FAILED,
            pageIndex = 1,
            message = "native path /secret must not leak",
        )

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile(bytes = tiffHeader(), contentType = "image/tiff")),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.detail").value("TIFF OCR processing failed."))
            .andExpect(jsonPath("$.reason").value("ENGINE_FAILED"))
            .andExpect(jsonPath("$.phase").value("engine"))
            .andExpect(jsonPath("$.pageIndex").value(1))
    }

    private fun imageFile(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        contentType: String = "image/png",
    ): MockMultipartFile =
        MockMultipartFile("file", "sample.${if (contentType == "image/tiff") "tiff" else "png"}", contentType, bytes)

    private fun tiffHeader(): ByteArray = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00)

    private fun response(languages: List<String> = listOf("eng")): ImageOcrResponse =
        ImageOcrResponse(
            requestId = "ocr-test-request",
            status = OcrStatus.COMPLETED,
            engine = "tesseract",
            languages = languages,
            confidence = null,
            text = "Bluetape OCR",
            blocks = listOf(OcrTextBlock(index = 0, text = "Bluetape OCR", confidence = null)),
            warnings = listOf("Confidence is not available from the current OCR engine."),
        )
}
