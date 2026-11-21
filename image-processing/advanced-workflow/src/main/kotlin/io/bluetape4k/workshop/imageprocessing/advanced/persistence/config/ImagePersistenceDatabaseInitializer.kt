package io.bluetape4k.workshop.imageprocessing.advanced.persistence.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageObjectTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingJobTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 애플리케이션 시작 시 이미지 영속화 스키마를 생성합니다.
 *
 * ## 동작 / 계약
 * - [SchemaUtils.create]는 멱등입니다. 아직 없는 테이블만 생성합니다.
 * - 테이블 생성 뒤 raw SQL 문으로 NULLS NOT DISTINCT 부분 unique
 *   제약을 `image_objects(image_asset_id, kind, variant_name)`에 추가합니다. 이 제약은
 *   Exposed의 uniqueIndex() API로 표현할 수 없습니다.
 * - DDL 실패는 잡지 않고 전파합니다. 스키마 생성에 실패하면 시작 단계에서 조기 실패해야 합니다.
 * - 여기서는 [run]의 [@Transactional]을 허용합니다(사가 서비스가 아니라 DDL initializer입니다).
 */
@Component
class ImagePersistenceDatabaseInitializer: ApplicationRunner {

    companion object: KLogging()

    /**
     * run은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param args 업무 처리에 필요한 args 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Transactional
    override fun run(args: ApplicationArguments) {
        log.info { "Creating image persistence schema..." }

        SchemaUtils.create(
            ImageAssetTable,
            ImageObjectTable,
            ImageProcessingJobTable,
            ImageProcessingEventTable,
        )

        log.info { "Creating NULLS NOT DISTINCT unique index on image_objects..." }

        transaction {
            val sql = """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_image_objects_asset_kind_variant
                ON image_objects (image_asset_id, kind, variant_name) NULLS NOT DISTINCT
            """.trimIndent()
            this.exec(sql)
        }

        log.info { "Image persistence schema initialized successfully." }
    }
}
