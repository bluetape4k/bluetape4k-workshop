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

        var content by BlobTable.blob
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle blob field`(dialect: TestDB) {
        withTables(dialect, BlobTable) {
            val blobEntity = BlobEntity.new {
                content = ExposedBlob("foo".toUtf8Bytes())
            }
            flushCache()

            var y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo"

            y2.content = null
            flushCache()
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content.shouldBeNull()

            y2.content = ExposedBlob("foo2".toUtf8Bytes())
            flushCache()
            y2 = BlobEntity.reload(blobEntity)!!
            y2.content!!.bytes.toUtf8String() shouldBeEqualTo "foo2"
        }
    }
}
