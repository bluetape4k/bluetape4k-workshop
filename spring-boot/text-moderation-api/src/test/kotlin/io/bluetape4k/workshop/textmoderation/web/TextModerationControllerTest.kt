package io.bluetape4k.workshop.textmoderation.web

import io.bluetape4k.workshop.textmoderation.TextModerationApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [TextModerationApplication::class])
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextModerationControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {

    @Test
    fun `POST analyze returns normalized moderation response`() {
        mockMvc.perform(
            post("/api/moderation/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Please block spam from this English request."}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.detectedLanguage").value("ENGLISH"))
            .andExpect(jsonPath("$.matchedTerms[0]").value("spam"))
            .andExpect(jsonPath("$.maskedText").value("Please block **** from this English request."))
            .andExpect(jsonPath("$.warnings[0]").value("ABUSE_WORD_MATCHED"))
    }

    @Test
    fun `POST analyze detects Korean text`() {
        mockMvc.perform(
            post("/api/moderation/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"안녕하세요. 오늘 날씨가 좋고 안전한 문장입니다."}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.detectedLanguage").value("KOREAN"))
            .andExpect(jsonPath("$.matchedTerms").isEmpty)
    }

    @Test
    fun `POST analyze returns bad request for blank text`() {
        mockMvc.perform(
            post("/api/moderation/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("text must not be blank"))
    }

    @Test
    fun `POST analyze returns bad request for missing text`() {
        mockMvc.perform(
            post("/api/moderation/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `POST analyze returns payload too large for oversized text`() {
        mockMvc.perform(
            post("/api/moderation/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"${"x".repeat(2_001)}"}"""),
        )
            .andExpect(status().isContentTooLarge)
            .andExpect(jsonPath("$.status").value(413))
            .andExpect(jsonPath("$.error").value("Content Too Large"))
            .andExpect(jsonPath("$.message").value("text exceeds 2000 characters"))
    }
}
