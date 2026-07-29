package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.jackson3.jacksonb
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 이미지 처리 job용 Exposed 테이블입니다.
 *
 * 각 행은 [ImageAssetTable] 행 하나에 대한 처리 실행 하나를 추적합니다.
 * asset 하나에 여러 job이 있을 수 있습니다(예: [ImageJobStatus.FAILED] 상태 복구 뒤).
 *
 * 일반 [LongIdTable]을 사용합니다. 여기에는 감사 컬럼이 필요 없고 시간은
 * [startedAt]과 [finishedAt]으로 추적합니다.
 */
object ImageProcessingJobTable : LongIdTable("image_processing_jobs") {

    /** 부모 [ImageAssetTable]에 대한 FK이며 삭제를 cascade합니다. */
    val imageAssetId = reference("image_asset_id", ImageAssetTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** 이 job의 현재 실행 상태입니다. */
    val status = enumerationByName("status", 20, ImageJobStatus::class)
        .default(ImageJobStatus.RUNNING)

    /** 이 job에서 요청한 변형 이름의 JSON 배열입니다(예: `["thumbnail", "webp-2x"]`). */
    val requestedVariants = jacksonb<List<String>>("requested_variants")

    /** job이 시작된 벽시계 시간입니다. 기본값은 DB `CURRENT_TIMESTAMP`입니다. */
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)

    /** job이 끝난 벽시계 시간입니다. 실행 중이면 null입니다. */
    val finishedAt = timestamp("finished_at").nullable()

    /** 전체 처리 시간을 밀리초로 저장합니다. 실행 중이면 null입니다. */
    val durationMs = long("duration_ms").nullable()

    /** 짧은 기계 판독용 오류 코드입니다(≤ 100자). 성공 시 null입니다. */
    val errorCode = varchar("error_code", 100).nullable()

    /** 사람이 읽을 수 있는 오류 설명입니다. 성공 시 null입니다. */
    val errorMessage = text("error_message").nullable()
}
