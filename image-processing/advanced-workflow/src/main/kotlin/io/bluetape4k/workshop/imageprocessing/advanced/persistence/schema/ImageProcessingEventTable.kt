package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.jackson3.jacksonb
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 처리 job 안의 개별 파이프라인 단계 이벤트용 Exposed 테이블입니다.
 *
 * 각 행은 단계 결과 하나를 기록합니다(검증, vips 처리, S3 업로드 등).
 * 지정한 [ImageProcessingJobTable] 행에 대해 적용됩니다.
 * 부모 job이 삭제되면 행도 cascade 삭제됩니다.
 */
object ImageProcessingEventTable : LongIdTable("image_processing_events") {

    /** 부모 [ImageProcessingJobTable]에 대한 FK이며 삭제를 cascade합니다. */
    val jobId = reference("job_id", ImageProcessingJobTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** 이 이벤트가 설명하는 파이프라인 단계입니다. */
    val step = enumerationByName("step", 30, ImageProcessingStep::class)

    /** 이 단계의 결과입니다. */
    val status = enumerationByName("status", 20, ImageProcessingEventStatus::class)

    /** 선택적인 사람이 읽을 수 있는 메시지입니다(예: 오류 설명 또는 요약). */
    val message = text("message").nullable()

    /** 단계별 진단 데이터를 담는 선택적 구조화 JSON 페이로드입니다. */
    val payloadJson = jacksonb<Map<String, Any?>>("payload_json").nullable()

    /** 이 이벤트가 기록된 벽시계 시간입니다. 기본값은 DB `CURRENT_TIMESTAMP`입니다. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
