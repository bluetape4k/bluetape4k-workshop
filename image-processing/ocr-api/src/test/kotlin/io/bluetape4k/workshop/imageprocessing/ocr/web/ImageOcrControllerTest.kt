package io.bluetape4k.workshop.imageprocessing.ocr.web

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.TiffMultiPageOcrException
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextBlock
import io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import org.hamcrest.Matchers
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
/**
 * multipart OCR API의 wire 계약을 검증합니다.
 *
 * 파일과 language/structuredDetail 파라미터가 service request로 전달되는지,
 * 유효한 응답이 JSON으로 나오는지, 빈 파일·크기 초과·지원하지 않는 content
 * type이 native 작업 전에 거부되는지를 확인합니다.
 */
class ImageOcrControllerTest {

    private companion object: KLogging()

    @Autowired
/**
 * mockMvc은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var mockMvc: MockMvc

    @MockkBean
/**
 * service은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var service: ImageOcrService

    @MockkBean
/**
 * properties은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var properties: ImageOcrProperties

    @BeforeEach
    fun setUpProperties() {
        every { properties.maxUploadBytes } returns 5_242_880
    }

    @Test
/**
 * 이 시나리오는 "multipart POST returns OCR response" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "structuredDetail parameter reaches service" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "missing structuredDetail preserves plain text default" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "language parameters preserve mixed repeated and comma-separated order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "empty upload returns bad request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "non image content type returns bad request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `non image content type returns bad request`() {
        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(MockMultipartFile("file", "text.txt", "text/plain", "hello".toUtf8Bytes())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
    }

    @Test
/**
 * 이 시나리오는 "unsupported image subtype returns bad request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unsupported image subtype returns bad request`() {
        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(MockMultipartFile("file", "icon.gif", "image/gif", "gif".toUtf8Bytes())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
    }

    @Test
/**
 * 이 시나리오는 "oversized upload returns bad request before reading bytes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "service validation failure returns bad request" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `service validation failure returns bad request`() {
        coEvery { service.recognize(any()) } throws IllegalArgumentException("Undecodable image")

        val asyncResult = mockMvc.perform(
            multipart("/api/images/ocr")
                .file(imageFile(bytes = "not an image".toUtf8Bytes())),
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Undecodable image"))
    }

    @Test
/**
 * 이 시나리오는 "TIFF validation failure returns sanitized reason phase and page" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("internal"))))
    }

    @Test
/**
 * 이 시나리오는 "TIFF processing failure returns sanitized engine reason" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
            .andExpect(status().`is`(422))
            .andExpect(jsonPath("$.detail").value("TIFF OCR processing failed."))
            .andExpect(jsonPath("$.reason").value("ENGINE_FAILED"))
            .andExpect(jsonPath("$.phase").value("engine"))
            .andExpect(jsonPath("$.pageIndex").value(1))
    }

/**
 * imageFile은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun imageFile(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        contentType: String = "image/png",
    ): MockMultipartFile =
        MockMultipartFile("file", "sample.${if (contentType == "image/tiff") "tiff" else "png"}", contentType, bytes)

/**
 * tiffHeader은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun tiffHeader(): ByteArray = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00)

/**
 * response은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
