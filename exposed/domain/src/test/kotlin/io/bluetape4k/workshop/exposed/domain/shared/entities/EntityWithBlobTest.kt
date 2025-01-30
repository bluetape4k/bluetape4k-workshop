package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62Entity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62EntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62EntityID
import io.bluetape4k.exposed.dao.id.TimebasedUUIDBase62Table
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.sql.statements.api.toUtf8String
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EntityWithBlobTest: AbstractExposedTest() {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS BLOBTABLE (
     *      ID VARCHAR(22) PRIMARY KEY,
     *      CONTENT BLOB NULL
     * )
     * ```
     */
    object BlobTable: TimebasedUUIDBase62Table("BlobTable") {
        val blob = blob("content").nullable()
    }

    class BlobEntity(id: TimebasedUUIDBase62EntityID): TimebasedUUIDBase62Entity(id) {
        companion object: TimebasedUUIDBase62EntityClass<BlobEntity>(BlobTable) {
            operator fun invoke(bytes: ByteArray): BlobEntity {
                return new { content = ExposedBlob(bytes) }
            }
        }

        var content: ExposedBlob? by BlobTable.blob
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle blob field`(testDB: TestDB) {
        withTables(testDB, BlobTable) {
            /**
             * ```sql
             * INSERT INTO BLOBTABLE (ID, CONTENT) VALUES ('wTkxePceefMUJFdP7d4X2', X'')
             * ```
             */
//            val blobEntity = BlobEntity.new {
//                content = ExposedBlob("foo".toUtf8Bytes())
//            }
            val blobEntity = BlobEntity("foo".toUtf8Bytes())
            entityCache.clear()

            /**
             * ```sql
             * SELECT BLOBTABLE.ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.ID = 'wTkxePceefMUJFdP7d4X2'
             * ```
             */
            var y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo"

            /**
             * ```sql
             * UPDATE BLOBTABLE SET CONTENT=NULL WHERE ID = 'wTkxePceefMUJFdP7d4X2'
             * ```
             */
            y2.content = null
            entityCache.clear()
            /**
             * ```sql
             * SELECT BLOBTABLE.ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.ID = 'wTkxePceefMUJFdP7d4X2'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content.shouldBeNull()

            /**
             * ```sql
             * UPDATE BLOBTABLE SET CONTENT=X'' WHERE ID = 'wTkxePceefMUJFdP7d4X2'
             * ```
             */
            y2.content = ExposedBlob("foo2".toUtf8Bytes())
            entityCache.clear()
            /**
             * ```sql
             * SELECT BLOBTABLE.ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.ID = 'wTkxePceefMUJFdP7d4X2'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.toUtf8String() shouldBeEqualTo "foo2"
        }
    }
}
