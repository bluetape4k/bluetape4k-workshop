package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageObjectDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageObjectTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * [ImageObjectTable] 행 저장소입니다.
 *
 * ## 동작 / 계약
 * - 모든 쓰기 메서드는 [io.bluetape4k.exposed.core.auditable.UserContext.withUser] 블록 안에서 호출해야 합니다..
 * - 모든 메서드는 Exposed `transaction {}` 또는 동등한 경계 안에서 호출해야 합니다.
 * - [batchUpsertObjects]는 NULLS NOT DISTINCT unique 인덱스로 충돌을 해소합니다.
 * [io.bluetape4k.workshop.imageprocessing.advanced.persistence.config.ImagePersistenceDatabaseInitializer]가
 * 만든 `(image_asset_id, kind, variant_name)` unique 인덱스입니다.
 */
@Repository
class ImageObjectRepository: LongAuditableJdbcRepository<ImageObjectDTO, ImageObjectTable> {

    private companion object: KLogging()
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    override val table = ImageObjectTable

    /**
     * extractId은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param entity 업무 처리에 필요한 entity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun extractId(entity: ImageObjectDTO): Long = entity.id

    /**
     * toEntity은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
     * 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun ResultRow.toEntity(): ImageObjectDTO = toImageObjectDTO()

    /**
     * [assetId]에 연결된 이미지 객체를 batch upsert합니다.
     *
     * 충돌 해소는 다음 위치의 unique 인덱스가 담당합니다.
     * `(image_asset_id, kind, variant_name)`에 NULLS NOT DISTINCT 의미를 적용합니다.
     * 중복 행을 찾으면 키가 아닌 모든 컬럼을 갱신합니다.
     * @param assetId 부모 asset의 기본 키입니다.
     * @param objects 영속화할 이미지 객체 목록입니다.
     */
    fun batchUpsertObjects(assetId: Long, objects: List<ImageObjectInput>) {
        if (objects.isEmpty()) return
        log.debug { "Batch upsert objects. assetId=$assetId, objects=$objects" }
        table.batchUpsert(
            objects,
            ImageObjectTable.imageAssetId,
            ImageObjectTable.kind,
            ImageObjectTable.variantName,
        ) { obj ->
            this[ImageObjectTable.imageAssetId] = assetId
            this[ImageObjectTable.kind] = obj.kind
            this[ImageObjectTable.variantName] = obj.variantName
            this[ImageObjectTable.s3Key] = obj.s3Key
            this[ImageObjectTable.publicUrl] = obj.publicUrl
            this[ImageObjectTable.width] = obj.width
            this[ImageObjectTable.height] = obj.height
            this[ImageObjectTable.byteSize] = obj.byteSize
            this[ImageObjectTable.format] = obj.format
        }
    }

    /**
     * [assetId]로 식별한 asset의 모든 이미지 객체를 반환합니다.
     */
    fun findByAssetId(assetId: Long): List<ImageObjectDTO> =
        table.selectAll()
            .where { ImageObjectTable.imageAssetId eq assetId }
            .map { it.toImageObjectDTO() }
            .onEach { log.debug { "assetId=$assetId, image object=$it" } }
}
