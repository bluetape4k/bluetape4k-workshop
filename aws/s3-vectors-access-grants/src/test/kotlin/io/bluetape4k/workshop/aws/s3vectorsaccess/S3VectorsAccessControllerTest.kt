package io.bluetape4k.workshop.aws.s3vectorsaccess

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [S3VectorsAccessGrantsApplication::class])
@AutoConfigureMockMvc
class S3VectorsAccessControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `upsert and search endpoints return redacted access grant decision`() {
        val upsertResult = mockMvc.perform(
            post("/aws/s3-vectors/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "documentId": "doc-api-1",
                      "title": "Access Grants primer",
                      "objectKey": "docs/access-grants.md",
                      "vector": [0.7, 0.2, 0.1],
                      "metadata": { "topic": "security" }
                    }
                    """.trimIndent()
                )
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(upsertResult))
            .andExpect(status().isOk)

        val searchResult = mockMvc.perform(
            post("/aws/s3-vectors/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "query": [0.6, 0.3, 0.1],
                      "topK": 1,
                      "requireAccessGrant": true
                    }
                    """.trimIndent()
                )
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        val body = mockMvc.perform(asyncDispatch(searchResult))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        body shouldContain "\"documentId\":\"doc-api-1\""
        body shouldContain "\"state\":\"GRANTED\""
        body shouldContain "\"redacted\":true"
        body shouldNotContain "accessKeyId"
        body shouldNotContain "secretAccessKey"
        body shouldNotContain "sessionToken"
    }
}
