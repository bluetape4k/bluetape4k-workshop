package io.bluetape4k.workshop.imageprocessing.advanced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetNotFoundException
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingResponse
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceService
import io.bluetape4k.workshop.imageprocessing.advanced.service.ImageDerivativeWorkflowService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
/**
 * ImageDerivativesController은 이미지 처리에서 사용하는 업무 객체입니다.
 * @property service 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property persistenceService 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/images")
class ImageDerivativesController(
    /**
     * `service`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: ImageDerivativeWorkflowService,
    /**
     * `persistenceService`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val persistenceService: ImagePersistenceService,
) {

    private companion object: KLogging()

    /**
     * `POST /api/images/derivatives` — 이미지를 업로드하고 파생 이미지를 생성합니다.
     *
     * 성공 시 [ImageProcessingResponse]와 함께 201 Created를 반환합니다.
     * 같은 checksum으로 이미 `READY`인 asset을 다시 올리면 기존 결과를
     * 재사용할 수 있습니다. 검증 실패는 storage/native 처리 전에 거부됩니다.
     * ```bash
     * curl -i -F "file=@photo.jpg;type=image/jpeg" \
     *   http://localhost:8080/api/images/derivatives
     * ```
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
     * `404`는 처리 실패가 아니라 해당 external id가 persistence에 없다는 뜻입니다.
     * ```bash
     * curl http://localhost:8080/api/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4
     * ```
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
     * `VALIDATION`, `VIPS_PROCESSING`, `S3_UPLOAD`, 최종 job 상태를 읽어
     * 부분 업로드나 native 실패가 어느 단계에서 발생했는지 설명할 수 있습니다.
     * ```bash
     * curl http://localhost:8080/api/images/2bb4f9c9-7133-46f2-bcc8-71a7e43ec1c4/history
     * ```
     */
    @GetMapping("/{imageId}/history", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getAssetHistory(@PathVariable imageId: String): ImageAssetHistoryResponse {
        return withContext(Dispatchers.IO) {
            persistenceService.findAssetHistory(imageId)
        } ?: throw ImageAssetNotFoundException(imageId)
    }
}
