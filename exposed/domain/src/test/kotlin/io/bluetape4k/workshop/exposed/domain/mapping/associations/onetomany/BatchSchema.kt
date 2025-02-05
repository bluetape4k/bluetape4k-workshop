package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.workshop.exposed.dao.idValue
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * One-To-Many bidirectional Relationship
 */
object BatchSchema {

    object BatchTable: IntIdTable("batch") {
        val name = varchar("name", 255)
    }

    object BatchItemTable: IntIdTable("batch_item") {
        val name = varchar("name", 255)

        // reference to Batch
        val batch = reference("batch_id", BatchTable).index()
    }

    class Batch(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Batch>(BatchTable)

        var name by BatchTable.name

        // one-to-many relationship
        val items by BatchItem.referrersOn(BatchItemTable.batch)

        override fun equals(other: Any?): Boolean = other is Batch && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "Batch(id=$idValue, name=$name)"
    }

    class BatchItem(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<BatchItem>(BatchItemTable)

        var name by BatchItemTable.name
        var batch by Batch referencedOn BatchItemTable.batch

        override fun equals(other: Any?): Boolean = other is BatchItem && idValue == other.idValue
        override fun hashCode(): Int = idValue.hashCode()
        override fun toString(): String = "BatchItem(id=$idValue, name=$name, batch=${batch.id._value})"
    }
}
