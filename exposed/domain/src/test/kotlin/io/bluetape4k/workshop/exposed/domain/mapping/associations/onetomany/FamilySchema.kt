package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.javatime.date

/**
 * One-To-Many Unidirectional Relationship
 */
object FamilySchema {

    object FatherTable: IntIdTable("father") {
        val name = varchar("name", 255)
    }

    object ChildTable: IntIdTable("child") {
        val name = varchar("name", 255)
        val birthday = date("birthday")

        // reference to Father
        val father = reference("father_id", FatherTable).index()
    }

    class Father(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Father>(FatherTable)

        var name by FatherTable.name

        // Ordered by birthday
        // one-to-many relationship
        val children by Child.referrersOn(ChildTable.father).orderBy(ChildTable.birthday to SortOrder.ASC)

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .toString()
    }

    class Child(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Child>(ChildTable)

        var name by ChildTable.name
        var birthday by ChildTable.birthday

        // many-to-one relationship
        // var father by Father referencedOn ChildTable.father

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .add("birthday", birthday)
            .toString()
    }
}
