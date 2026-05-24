package io.bluetape4k.workshop.imageprocessing.advanced.web

import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingResponse
import io.bluetape4k.workshop.imageprocessing.advanced.service.ImageDerivativeWorkflowService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
) {

    @PostMapping(
        "/derivatives",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createDerivatives(@RequestPart("file") file: MultipartFile): ImageProcessingResponse =
        service.processUpload(file)
}
