package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.assertions.shouldNotContain
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
class BarcodeApiApplicationTest(
    @param:Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `sample endpoint returns provider neutral result`() {
        val result = mockMvc.perform(get("/api/barcodes/sample"))
            .dispatch()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.results[0].text").value("bluetape4k-barcode-quickstart"))
            .andExpect(jsonPath("$.results[0].format").value("QR_CODE"))
            .andExpect(jsonPath("$.results[0].provider").value("ZXing"))
            .andReturn()

        result.response.contentAsString.shouldNotContain("rawBytes")
    }

    @Test
    fun `valid image without barcode returns empty success`() {
        mockMvc.perform(get("/api/barcodes/no-result"))
            .dispatch()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.results").isEmpty)
    }

    @Test
    fun `malformed fixture returns sanitized error`() {
        mockMvc.perform(get("/api/barcodes/malformed"))
            .dispatch()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("malformed_input"))
            .andExpect(jsonPath("$.reason").value("MALFORMED_INPUT"))
            .andExpect(jsonPath("$.message").value("The uploaded file is not a decodable image."))
    }

    @Test
    fun `multipart endpoint rejects unsupported media type`() {
        mockMvc.perform(
            multipart("/api/barcodes/extract")
                .file(MockMultipartFile("file", "payload.txt", MediaType.TEXT_PLAIN_VALUE, byteArrayOf(1)))
        ).dispatch()
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error").value("unsupported_media_type"))
    }

    private fun ResultActions.dispatch(): ResultActions {
        val result = andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(result))
    }
}
