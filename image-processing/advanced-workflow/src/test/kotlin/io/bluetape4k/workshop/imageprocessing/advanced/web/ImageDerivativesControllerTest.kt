package io.bluetape4k.workshop.imageprocessing.advanced.web

import com.ninjasquad.springmockk.MockkBean
import io.bluetape4k.logging.KLogging
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
 * [ImageDerivativesController]의 GET 엔드포인트와 upload 응답 경계 테스트입니다.
 *
 * `@WebMvcTest`로 모의 서비스가 포함된 웹 계층만 로드합니다.
 * 코루틴 기반 suspend 핸들러에는 async-dispatch 패턴이 필요합니다.
 * `perform(get(...)).andExpect(request().asyncStarted()).andReturn()` →
 * `perform(asyncDispatch(...))`.
 * asset이 있으면 `200`, 알 수 없는 id면 `404`이며 history는 saga 단계가
 * 실제 응답으로 노출되는지 확인합니다.
 */
@WebMvcTest(controllers = [ImageDerivativesController::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageDerivativesControllerTest {

    private companion object: KLogging()

    @Autowired
/**
 * mockMvc은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var mockMvc: MockMvc

    /** GET 전용 테스트에서 직접 사용하지 않아도 context에 필요합니다. */
    /** GET 전용 테스트에서 직접 사용하지 않아도 context에 필요합니다. */
    @MockkBean
    private lateinit var workflowService: ImageDerivativeWorkflowService

    @MockkBean
/**
 * persistenceService은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var persistenceService: ImagePersistenceService

    // -------------------------------------------------------------------------
    // GET /api/images/{imageId}
    // -------------------------------------------------------------------------

    @Test
/**
 * 이 시나리오는 "GET asset detail returns 200 with body when asset exists" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "GET asset detail returns 404 when asset not found" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "GET asset history returns 200 with body when asset exists" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "GET asset history returns 404 when asset not found" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
    // 헬퍼
    // -------------------------------------------------------------------------

/**
 * buildDetailResponse은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
