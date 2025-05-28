package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object EntityTestData {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS ytable (
     *      uuid VARCHAR(24) PRIMARY KEY,
     *      x BOOLEAN DEFAULT TRUE NOT NULL
     * )
     */
    object YTable: IdTable<String>("YTable") {
        override val id: Column<EntityID<String>> = varchar("uuid", 24).entityId()
            .clientDefault { EntityID(TimebasedUuid.nextBase62String(), YTable) }

        val x = bool("x").default(true)

        override val primaryKey = PrimaryKey(id)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS xtable (
     *      id SERIAL PRIMARY KEY,
     *      b1 BOOLEAN DEFAULT TRUE NOT NULL,
     *      b2 BOOLEAN DEFAULT FALSE NOT NULL,
     *      y1 VARCHAR(24) NULL,
     *
     *      CONSTRAINT fk_xtable_y1__uuid FOREIGN KEY (y1) REFERENCES ytable(uuid)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object XTable: IntIdTable("XTable") {
        val b1 = bool("b1").default(true)
        val b2 = bool("b2").default(false)
        val y1 = optReference("y1", YTable)
    }

    class XEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<XEntity>(XTable)

        var b1 by XTable.b1
        var b2 by XTable.b2

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("b1", b1)
                .add("b2", b2)
                .toString()
    }

    enum class XType {
        A, B
    }

    open class AEntity(id: EntityID<Int>): IntEntity(id) {
        var b1 by XTable.b1

        companion object: IntEntityClass<AEntity>(XTable) {
            fun create(b1: Boolean, type: XType): AEntity {
                val init: AEntity.() -> Unit = {
                    this.b1 = b1
                }
                val answer = when (type) {
                    XType.B -> BEntity.create { init() }
                    else -> new { init() }
                }
                return answer
            }
        }

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("b1", b1)
                .toString()
    }

    open class BEntity(id: EntityID<Int>): AEntity(id) {
        companion object: IntEntityClass<BEntity>(XTable) {
            fun create(init: AEntity.() -> Unit): BEntity {
                val answer = new { init() }
                return answer
            }
        }

        var b2 by XTable.b2
        var y by YEntity optionalReferencedOn XTable.y1

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("b1", b1)
                .add("b2", b2)
                .add("y", y)
                .toString()
    }

    class YEntity(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, YEntity>(YTable)

        var x by YTable.x
        val b: BEntity? by BEntity.backReferencedOn(XTable.y1)

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("x", x)
                .add("b", b)
                .toString()
    }
}
