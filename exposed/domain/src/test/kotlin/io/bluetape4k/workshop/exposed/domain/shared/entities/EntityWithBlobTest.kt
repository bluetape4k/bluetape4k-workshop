package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class EntityWithBlobTest: AbstractExposedTest() {

    object BlobTable: IdTable<String>("BlobTable") {
        override val id = varchar("uuid_base62_id", 22)
            .entityId()
            .clientDefault {
                EntityID(TimebasedUuid.Epoch.nextIdAsString(), BlobTable)
            }

        val blob = blob("content").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    class BlobEntity(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, BlobEntity>(BlobTable)

        var content: ExposedBlob? by BlobTable.blob
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle blob field`(testDb: TestDB) {
        withTables(testDb, BlobTable) {
            /**
             * ```sql
             * INSERT INTO BLOBTABLE (UUID_BASE62_ID, CONTENT) VALUES ('2ymBXTjDNVQ14p04fcBwq', X'')
             * ```
             */
            val blobEntity = BlobEntity.new {
                content = ExposedBlob("foo".toUtf8Bytes())
            }
            flushCache()

            /**
             * ```sql
             * SELECT BLOBTABLE.UUID_BASE62_ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.UUID_BASE62_ID = '2ymBXTjDNVQ14p04fcBwq'
             * ```
             */
            var y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo"

            /**
             * ```sql
             * UPDATE BLOBTABLE SET CONTENT=NULL WHERE UUID_BASE62_ID = '2ymBXTjDNVQ14p04fcBwq'
             * ```
             */
            y2.content = null
            flushCache()
            /**
             * ```sql
             * SELECT BLOBTABLE.UUID_BASE62_ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.UUID_BASE62_ID = '2ymBXTjDNVQ14p04fcBwq'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content.shouldBeNull()

            /**
             * ```sql
             * UPDATE BLOBTABLE SET CONTENT=X'' WHERE UUID_BASE62_ID = '2ymBXTjDNVQ14p04fcBwq'
             * ```
             */
            y2.content = ExposedBlob("foo2".toUtf8Bytes())
            flushCache()
            /**
             * ```sql
             * SELECT BLOBTABLE.UUID_BASE62_ID, BLOBTABLE.CONTENT FROM BLOBTABLE WHERE BLOBTABLE.UUID_BASE62_ID = '2ymBXTjDNVQ14p04fcBwq'
             * ```
             */
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo2"
        }
    }
}
