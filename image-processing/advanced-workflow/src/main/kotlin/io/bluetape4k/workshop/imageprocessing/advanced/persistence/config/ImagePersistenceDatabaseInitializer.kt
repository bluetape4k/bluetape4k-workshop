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
 * Creates the image persistence schema on application startup.
 *
 * ## Behavior / Contract
 * - [SchemaUtils.create] is idempotent: it only creates tables that do not yet exist.
 * - After table creation, a raw SQL statement adds a NULLS NOT DISTINCT partial unique
 *   constraint on `image_objects(image_asset_id, kind, variant_name)`. This constraint
 *   cannot be expressed via Exposed's uniqueIndex() API.
 * - DDL failures propagate without catch — startup must fail fast if schema creation fails.
 * - [@Transactional] on [run] is acceptable here (DDL initializer, not a saga service).
 */
@Component
class ImagePersistenceDatabaseInitializer : ApplicationRunner {

    companion object : KLogging()

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
