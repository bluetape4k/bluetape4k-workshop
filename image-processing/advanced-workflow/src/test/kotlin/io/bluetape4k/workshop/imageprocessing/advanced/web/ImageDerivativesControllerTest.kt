package io.bluetape4k.workshop.imageprocessing.advanced.web

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.service.ImageDerivativeWorkflowService
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Slice test for [ImageDerivativesController] GET endpoints.
 *
 * Uses `@WebMvcTest` to load only the web layer with mocked services.
 * Coroutine-backed suspend handlers require the async-dispatch pattern:
 * `perform(get(...)).andExpect(request().asyncStarted()).andReturn()` → `perform(asyncDispatch(...))`.
 */
@WebMvcTest(controllers = [ImageDerivativesController::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageDerivativesControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    /** Required in context even when not exercised in GET-only tests. */
    @MockkBean
    private lateinit var workflowService: ImageDerivativeWorkflowService

    @MockkBean
    private lateinit var persistenceService: ImagePersistenceService

    // -------------------------------------------------------------------------
    // GET /api/images/{imageId}
    // -------------------------------------------------------------------------

    @Test
    fun `GET asset detail returns 200 with body when asset exists`() {
        val imageId = "test-image-id"
        every { persistenceService.findAssetByExternalId(imageId) } returns buildDetailResponse(imageId)

        val asyncResult = mockMvc.perform(get("/api/images/{imageId}", imageId))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.imageId").value(imageId))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.original.s3Key").value("images/original.jpg"))
    }

    @Test
    fun `GET asset detail returns 404 when asset not found`() {
        val imageId = "missing-id"
        every { persistenceService.findAssetByExternalId(imageId) } returns null

        val asyncResult = mockMvc.perform(get("/api/images/{imageId}", imageId))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // GET /api/images/{imageId}/history
    // -------------------------------------------------------------------------

    @Test
    fun `GET asset history returns 200 with body when asset exists`() {
        val imageId = "test-image-id"
        every { persistenceService.findAssetHistory(imageId) } returns ImageAssetHistoryResponse(
            imageId = imageId,
            jobs = emptyList(),
        )

        val asyncResult = mockMvc.perform(get("/api/images/{imageId}/history", imageId))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.imageId").value(imageId))
            .andExpect(jsonPath("$.jobs").isArray)
    }

    @Test
    fun `GET asset history returns 404 when asset not found`() {
        val imageId = "missing-id"
        every { persistenceService.findAssetHistory(imageId) } returns null

        val asyncResult = mockMvc.perform(get("/api/images/{imageId}/history", imageId))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildDetailResponse(imageId: String): ImageAssetDetailResponse =
        ImageAssetDetailResponse(
            imageId = imageId,
            status = ImageAssetStatus.READY,
            original = ImageObjectDTO(
                id = 1L,
                imageAssetId = 1L,
                kind = ImageObjectKind.ORIGINAL,
                variantName = null,
                s3Key = "images/original.jpg",
                publicUrl = "http://localhost:8080/public-images/images/original.jpg",
                width = 640,
                height = 480,
                byteSize = 4096L,
                format = "image/jpeg",
            ),
            variants = emptyList(),
        )
}
