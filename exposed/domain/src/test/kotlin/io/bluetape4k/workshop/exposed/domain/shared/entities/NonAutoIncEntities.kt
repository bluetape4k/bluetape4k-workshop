package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.atomic.AtomicInteger

class NonAutoIncEntities: AbstractExposedTest() {

    companion object: KLogging()

    abstract class BaseNonAutoIncTable(name: String): IdTable<Int>(name) {
        override val id: Column<EntityID<Int>> = integer("id").entityId()
        val b1: Column<Boolean> = bool("b1")
    }

    object NotAutoIntIdTable: BaseNonAutoIncTable("") {
        val defaultedInt: Column<Int> = integer("i1")
    }

    class NotAutoEntity(id: EntityID<Int>): Entity<Int>(id) {
        var b1: Boolean by NotAutoIntIdTable.b1
        var defaultedInNew: Int by NotAutoIntIdTable.defaultedInt

        companion object: EntityClass<Int, NotAutoEntity>(NotAutoIntIdTable) {
            val lastId = AtomicInteger(0)
            internal const val defaultInt = 42

            fun new(b: Boolean) = new(lastId.incrementAndGet()) { b1 = b }

            override fun new(id: Int?, init: NotAutoEntity.() -> Unit): NotAutoEntity {
                return super.new(id ?: lastId.incrementAndGet()) {
                    defaultedInNew = defaultInt
                    init()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults with override new`(testDB: TestDB) {
        withTables(testDB, NotAutoIntIdTable) {
            /**
             * ```sql
             * INSERT INTO NOTAUTOINTID (ID, I1, B1) VALUES (1, 42, TRUE)
             * ```
             */
            val entity1 = NotAutoEntity.new(true)
            entity1.b1.shouldBeTrue()
            entity1.defaultedInNew shouldBeEqualTo NotAutoEntity.defaultInt

            /**
             * ```sql
             * INSERT INTO NOTAUTOINTID (ID, I1, B1) VALUES (2, 1, FALSE)
             * ```
             */
            val entity2 = NotAutoEntity.new {
                b1 = false
                defaultedInNew = 1
            }
            entity2.b1.shouldBeFalse()
            entity2.defaultedInNew shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `not auto inc table`(testDb: TestDB) {
        withTables(testDb, NotAutoIntIdTable) {
            val e1 = NotAutoEntity.new(true)
            val e2 = NotAutoEntity.new(false)

            flushCache()

            val all = NotAutoEntity.all()
            all.map { it.id } shouldBeEqualTo listOf(e1.id, e2.id)
        }
    }

    object RequestsTable: IdTable<String>() {
        val requestId = varchar("request_id", 255)
        val deleted = bool("deleted").default(false)

        override val primaryKey = PrimaryKey(requestId)
        override val id: Column<EntityID<String>> = requestId.entityId()
    }

    class Request(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, Request>(RequestsTable)

        var requestId by RequestsTable.requestId
        var deleted by RequestsTable.deleted

        /**
         * Soft delete the entity
         */
        override fun delete() {
            RequestsTable.update({ RequestsTable.id eq id }) {
                it[deleted] = true
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `access entity id from override entity method`(testDB: TestDB) {
        withTables(testDB, RequestsTable) {
            val request = Request.new {
                requestId = "requestId"
                deleted = false
            }

            /**
             * Soft delete the entity
             *
             * ```sql
             * UPDATE REQUESTS SET DELETED=TRUE WHERE REQUESTS.REQUEST_ID = 'requestId'
             * ```
             */
            request.delete()

            /**
             * ```sql
             * SELECT REQUESTS.DELETED, REQUESTS.REQUEST_ID FROM REQUESTS WHERE REQUESTS.REQUEST_ID = 'requestId'
             * ```
             */
            val updated = Request["requestId"]
            updated.deleted.shouldBeTrue()
        }
    }
}
