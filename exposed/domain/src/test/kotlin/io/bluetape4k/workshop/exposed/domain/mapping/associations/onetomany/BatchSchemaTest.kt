package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.BatchSchema.Batch
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.BatchSchema.BatchItem
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.BatchSchema.BatchItemTable
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.BatchSchema.BatchTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.Transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BatchSchemaTest: AbstractExposedTest() {

    companion object: KLogging()

    private val batchTables = arrayOf(BatchTable, BatchItemTable)

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with bidirectional relationship`(testDB: TestDB) {
        withTables(testDB, BatchTable, BatchItemTable) {
            val batch1 = createSamples()
            val batchItems = batch1.items.toList()

            entityCache.clear()

            val loaded = Batch.findById(batch1.id)!!
            loaded shouldBeEqualTo batch1
            loaded.items.toList() shouldBeEqualTo batchItems

            val loaded2 = Batch.all().with(Batch::items).single()
            loaded2.items.toList() shouldBeEqualTo batchItems

            val loaded3 = Batch.findById(batch1.id)?.load(Batch::items)
            loaded3?.items?.toList() shouldBeEqualTo batchItems
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with bidirectional - delete`(testDB: TestDB) {
        withTables(testDB, *batchTables) {
            val batch1 = createSamples()
            val batchItems = batch1.items.toList()

            entityCache.clear()

            val loaded = Batch.findById(batch1.id)!!
            loaded shouldBeEqualTo batch1
            loaded.items.toList() shouldBeEqualTo batchItems

            batchItems.first().delete()
            entityCache.clear()

            val loaded2 = Batch.findById(batch1.id)!!
            loaded2.items.count() shouldBeEqualTo 2L
            loaded2.items.toList() shouldBeEqualTo batchItems.drop(1)

            batch1.items.forEach { it.delete() }
            batch1.items.count() shouldBeEqualTo 0L

            batch1.delete()
        }
    }

    private fun Transaction.createSamples(): Batch {
        val batch1 = Batch.new { name = "B-123" }
        BatchItem.new { name = "Item 1"; batch = batch1 }
        BatchItem.new { name = "Item 2"; batch = batch1 }
        BatchItem.new { name = "Item 3"; batch = batch1 }

        return batch1
    }
}
