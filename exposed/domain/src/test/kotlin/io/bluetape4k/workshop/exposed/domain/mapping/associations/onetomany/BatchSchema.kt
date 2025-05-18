package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * One-To-Many bidirectional Relationship
 */
object BatchSchema {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS batch (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * );
     * ```
     */
    object BatchTable: IntIdTable("batch") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS batch_item (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      batch_id INT NOT NULL,
     *
     *      CONSTRAINT fk_batch_item_batch_id__id FOREIGN KEY (batch_id)
     *      REFERENCES batch(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String {
            return toStringBuilder()
                .add("name", name)
                .toString()
        }
    }

    class BatchItem(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<BatchItem>(BatchItemTable)

        var name by BatchItemTable.name
        var batch by Batch referencedOn BatchItemTable.batch

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String {
            return toStringBuilder()
                .add("name", name)
                .add("batch id", batch.id._value)
                .toString()
        }
    }
}
