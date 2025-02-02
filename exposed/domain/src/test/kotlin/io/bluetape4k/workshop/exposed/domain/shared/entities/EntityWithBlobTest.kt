package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62Entity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62EntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62EntityID
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62Table
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.sql.statements.api.toUtf8String
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EntityWithBlobTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS blobtable (
     *      id VARCHAR(22) PRIMARY KEY,
     *      "content" bytea NULL
     * )
     * ```
     */
    object BlobTable: TimebasedUUIDBase62Table("BlobTable") {
        val blob = blob("content").nullable()
    }

    class BlobEntity(id: TimebasedUUIDBase62EntityID): TimebasedUUIDBase62Entity(id) {
        companion object: TimebasedUUIDBase62EntityClass<BlobEntity>(BlobTable) {
            /**
             * Custom 생성자
             */
            operator fun invoke(bytes: ByteArray): BlobEntity {
                return new { content = ExposedBlob(bytes) }
            }
        }

        var content: ExposedBlob? by BlobTable.blob

        override fun equals(other: Any?): Boolean = other is BlobEntity && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "BlobEntity($idValue)"
    }

    /**
     * Blob 필드를 다루는 테스트
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle blob field`(testDB: TestDB) {
        withTables(testDB, BlobTable) {
            /**
             * ```sql
             * INSERT INTO blobtable (id, "content")
             * VALUES ('wTsyEsRRJfbF1xtDl6Dmt', E'\\x')
             * ```
             */
            val blobEntity = BlobEntity("foo".toUtf8Bytes())
            entityCache.clear()

            /**
             * ```sql
             * SELECT blobtable.id, blobtable."content"
             *   FROM blobtable
             *  WHERE blobtable.id = 'wTsyEsRRJfbF1xtDl6Dmt'
             * ```
             */
            var y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo"

            /**
             * ```sql
             * UPDATE blobtable
             *    SET "content"=NULL
             *  WHERE id = 'wTsyEsRRJfbF1xtDl6Dmt'
             * ```
             */
            y2.content = null
            entityCache.clear()
            /**
             * ```sql
             * SELECT blobtable.id,
             *        blobtable."content"
             *   FROM blobtable
             *  WHERE blobtable.id = 'wTsyEsRRJfbF1xtDl6Dmt'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content.shouldBeNull()

            /**
             * ```sql
             * UPDATE blobtable
             *    SET "content"=E'\\x'
             *  WHERE id = 'wTsyEsRRJfbF1xtDl6Dmt'
             * ```
             */
            y2.content = ExposedBlob("foo2".toUtf8Bytes())
            entityCache.clear()
            /**
             * ```sql
             * SELECT blobtable.id,
             *        blobtable."content"
             *   FROM blobtable
             *  WHERE blobtable.id = 'wTsyEsRRJfbF1xtDl6Dmt'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.toUtf8String() shouldBeEqualTo "foo2"
        }
    }
}
