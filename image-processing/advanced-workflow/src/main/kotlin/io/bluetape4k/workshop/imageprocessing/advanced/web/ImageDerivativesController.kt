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
     * `POST /api/images/derivatives` — Upload an image and produce derivatives.
     *
     * Returns 201 Created with [ImageProcessingResponse] on success.
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
     * `GET /api/images/{imageId}` — Retrieve asset detail (original + variants).
     *
     * Returns 200 OK with [ImageAssetDetailResponse], or 404 if no asset found.
     */
    @GetMapping("/{imageId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAssetDetail(@PathVariable imageId: String): ImageAssetDetailResponse {
        return withContext(Dispatchers.IO) {
            persistenceService.findAssetByExternalId(imageId)
        } ?: throw ImageAssetNotFoundException(imageId)
    }

    /**
     * `GET /api/images/{imageId}/history` — Retrieve full job + event history for an asset.
     *
     * Returns 200 OK with [ImageAssetHistoryResponse], or 404 if no asset found.
     */
    @GetMapping("/{imageId}/history", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAssetHistory(@PathVariable imageId: String): ImageAssetHistoryResponse {
        return withContext(Dispatchers.IO) {
            persistenceService.findAssetHistory(imageId)
        } ?: throw ImageAssetNotFoundException(imageId)
    }
}
