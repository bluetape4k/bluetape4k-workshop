package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import org.jetbrains.exposed.v1.core.ReferenceOption

/**
 * S3에 저장된 이미지 객체(원본과 변형 파일)용 Exposed 테이블입니다.
 *
 * 각 행은 ON DELETE CASCADE가 적용된 [imageAssetId]로 [ImageAssetTable] 행에 연결됩니다.
 *
 * `(image_asset_id, kind, variant_name) NULLS NOT DISTINCT` 부분 unique 제약은
 * [ImagePersistenceDatabaseInitializer]에서 raw SQL로 생성합니다. 여기에 Exposed
 * `uniqueIndex()`를 추가하면 안 됩니다. Exposed가 NULLS NOT DISTINCT를 표현할 수 없기 때문입니다.
 *
 * ## 상속된 감사 컬럼([AuditableLongIdTable]에서 제공)
 * - `created_by`, `created_at`, `updated_by`, `updated_at`
 */
object ImageObjectTable : AuditableLongIdTable("image_objects") {

    /** 부모 [ImageAssetTable]에 대한 FK이며 삭제를 cascade합니다. */
    val imageAssetId = reference("image_asset_id", ImageAssetTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** 이 객체가 [ImageObjectKind.ORIGINAL]인지 [ImageObjectKind.VARIANT]인지 나타냅니다. */
    val kind = enumerationByName("kind", 20, ImageObjectKind::class)

    /** 변형 이름입니다(예: `"thumbnail"`, `"webp-2x"`). 원본이면 null입니다. */
    val variantName = varchar("variant_name", 100).nullable()

    /** 파일이 저장된 S3 객체 키입니다. */
    val s3Key = varchar("s3_key", 512)

    /** 객체에 공개적으로 접근할 수 있는 URL입니다. */
    val publicUrl = text("public_url")

    /** 픽셀 너비입니다. null일 수 있습니다. */
    val width = integer("width").nullable()

    /** 픽셀 높이입니다. null일 수 있습니다. */
    val height = integer("height").nullable()

    /** 이 객체의 바이트 크기입니다. null일 수 있습니다. */
    val byteSize = long("byte_size").nullable()

    /** 이미지 형식 문자열입니다(예: `"jpeg"`, `"webp"`, `"png"`). null일 수 있습니다. */
    val format = varchar("format", 20).nullable()
}
