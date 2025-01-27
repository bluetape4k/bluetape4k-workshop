package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column

object EntityTestData {

    object YTable: IdTable<String>("YTable") {
        override val id: Column<EntityID<String>> = varchar("uuid", 24).entityId()
            .clientDefault { EntityID(TimebasedUuid.nextBase62String(), YTable) }

        val x = bool("x").default(true)

        override val primaryKey = PrimaryKey(id)
    }

    object XTable: IntIdTable("XTable") {
        val b1 = bool("b1").default(true)
        val b2 = bool("b2").default(false)
        val y1 = optReference("y1", YTable)
    }

    class XEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<XEntity>(XTable)

        var b1 by XTable.b1
        var b2 by XTable.b2

        override fun toString(): String = "XEntity(id=$id, b1=$b1, b2=$b2)"
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

        override fun toString(): String = "AEntity(id=$id, b1=$b1)"
    }

    open class BEntity(id: EntityID<Int>): AEntity(id) {
        var b2 by XTable.b2
        var y by YEntity optionalReferencedOn XTable.y1

        companion object: IntEntityClass<BEntity>(XTable) {
            fun create(init: AEntity.() -> Unit): BEntity {
                val answer = new { init() }
                return answer
            }
        }

        override fun toString(): String = "BEntity(id=$id, b1=$b1, b2=$b2)"
    }

    class YEntity(id: EntityID<String>): Entity<String>(id) {
        companion object: EntityClass<String, YEntity>(YTable)

        var x by YTable.x
        val b: BEntity? by BEntity.backReferencedOn(XTable.y1)

        override fun toString(): String = "YEntity(id=$id, x=$x)"
    }
}
