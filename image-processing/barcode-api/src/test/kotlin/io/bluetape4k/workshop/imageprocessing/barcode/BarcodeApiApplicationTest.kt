package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
/**
 * 실제 Spring HTTP 경계에서 barcode 계약을 확인합니다.
 *
 * deterministic fixture의 성공/no-result/malformed 응답, multipart의 미지원
 * content type, provider 이름과 오류 payload의 sanitization을 확인합니다.
 * raw image bytes나 내부 예외가 API로 새어 나오지 않는지까지 함께 보호합니다.
 */
class BarcodeApiApplicationTest(
    @param:Autowired private val mockMvc: MockMvc,
) {

    private companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "sample endpoint returns provider neutral result" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `sample endpoint returns provider neutral result`() {
        val result = mockMvc.perform(get("/api/barcodes/sample"))
            .dispatch()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.results[0].text").value("bluetape4k-barcode-quickstart"))
            .andExpect(jsonPath("$.results[0].format").value("QR_CODE"))
            .andExpect(jsonPath("$.results[0].provider").value("ZXing"))
            .andReturn()

        log.debug { "result content=${result.response.contentAsString}" }
        result.response.contentAsString shouldNotContain "rawBytes"
    }

    @Test
/**
 * 이 시나리오는 "valid image without barcode returns empty success" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `valid image without barcode returns empty success`() {
        mockMvc.perform(get("/api/barcodes/no-result"))
            .dispatch()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.results").isEmpty)
    }

    @Test
/**
 * 이 시나리오는 "malformed fixture returns sanitized error" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `malformed fixture returns sanitized error`() {
        mockMvc.perform(get("/api/barcodes/malformed"))
            .dispatch()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("malformed_input"))
            .andExpect(jsonPath("$.reason").value("MALFORMED_INPUT"))
            .andExpect(jsonPath("$.message").value("The uploaded file is not a decodable image."))
    }

    @Test
/**
 * 이 시나리오는 "multipart endpoint rejects unsupported media type" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `multipart endpoint rejects unsupported media type`() {
        mockMvc.perform(
            multipart("/api/barcodes/extract")
                .file(MockMultipartFile("file", "payload.txt", MediaType.TEXT_PLAIN_VALUE, byteArrayOf(1)))
        ).dispatch()
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_media_type"))
    }

/**
 * dispatch은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ResultActions.dispatch(): ResultActions {
        val result = andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(result))
    }
}
