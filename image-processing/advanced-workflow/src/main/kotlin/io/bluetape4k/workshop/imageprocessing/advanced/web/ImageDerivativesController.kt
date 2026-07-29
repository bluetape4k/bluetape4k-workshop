package io.bluetape4k.workshop.imageprocessing.advanced.web

import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetNotFoundException
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingResponse
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.service.ImageDerivativeWorkflowService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/images")
class ImageDerivativesController(
    private val service: ImageDerivativeWorkflowService,
    private val persistenceService: ImagePersistenceService,
) {

    /**
     * `POST /api/images/derivatives` — 이미지를 업로드하고 파생 이미지를 생성합니다.
     *
     * 성공 시 [ImageProcessingResponse]와 함께 201 Created를 반환합니다.
     */
    @PostMapping(
        "/derivatives",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createDerivatives(@RequestPart("file") file: MultipartFile): ImageProcessingResponse =
        service.processUpload(file)

    /**
     * `GET /api/images/{imageId}` — asset 상세(원본 + 변형)를 조회합니다.
     *
     * asset을 찾으면 [ImageAssetDetailResponse]와 함께 200 OK를, 없으면 404를 반환합니다.
     */
    @GetMapping("/{imageId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAssetDetail(@PathVariable imageId: String): ImageAssetDetailResponse {
        return withContext(Dispatchers.IO) {
            persistenceService.findAssetByExternalId(imageId)
        } ?: throw ImageAssetNotFoundException(imageId)
    }

    /**
     * `GET /api/images/{imageId}/history` — asset의 전체 job + 이벤트 이력을 조회합니다.
     *
     * asset을 찾으면 [ImageAssetHistoryResponse]와 함께 200 OK를, 없으면 404를 반환합니다.
     */
    @GetMapping("/{imageId}/history", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAssetHistory(@PathVariable imageId: String): ImageAssetHistoryResponse {
        return withContext(Dispatchers.IO) {
            persistenceService.findAssetHistory(imageId)
        } ?: throw ImageAssetNotFoundException(imageId)
    }
}
