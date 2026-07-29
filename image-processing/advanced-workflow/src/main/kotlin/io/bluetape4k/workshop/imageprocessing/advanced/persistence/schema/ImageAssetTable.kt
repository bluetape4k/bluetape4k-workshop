package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

/**
 * 이미지 asset용 Exposed 테이블입니다.
 *
 * 각 행은 [checksum]으로 식별되는 고유 업로드 이미지 하나를 나타냅니다.
 * 중복 제거는 [checksum]의 UNIQUE 인덱스로 강제합니다.
 *
 * ## 상속된 감사 컬럼([AuditableLongIdTable]에서 제공)
 * - `created_by` — INSERT 시 [UserContext]로 설정됩니다.
 * - `created_at` — INSERT 시 DB `CURRENT_TIMESTAMP`입니다.
 * - `updated_by` — audited UPDATE 시 설정됩니다.
 * - `updated_at` — audited UPDATE 시 DB `CURRENT_TIMESTAMP`입니다.
 */
object ImageAssetTable : AuditableLongIdTable("image_assets") {

    /** 공개 이미지 식별자로 호출자에게 노출하는 UUID v4 문자열입니다. */
    val externalId = varchar("external_id", 36).uniqueIndex()

    /** 업로더가 제공한 원본 파일 이름입니다. 제공되지 않으면 null입니다. */
    val originalFilename = varchar("original_filename", 255).nullable()

    /** 업로드 파일의 MIME 유형입니다(예: `image/jpeg`). null일 수 있습니다. */
    val contentType = varchar("content_type", 100).nullable()

    /** 원본 업로드의 원시 바이트 크기입니다. null일 수 있습니다. */
    val byteSize = long("byte_size").nullable()

    /** 원본 이미지의 픽셀 너비입니다. null일 수 있습니다. */
    val width = integer("width").nullable()

    /** 원본 이미지의 픽셀 높이입니다. null일 수 있습니다. */
    val height = integer("height").nullable()

    /** 중복 제거에 사용하는 SHA-256 또는 MD5 16진 digest입니다. */
    val checksum = varchar("checksum", 64).uniqueIndex()

    /** 이 asset의 현재 생명주기 상태입니다. */
    val status = enumerationByName("status", 20, ImageAssetStatus::class)
        .default(ImageAssetStatus.PROCESSING)
}
