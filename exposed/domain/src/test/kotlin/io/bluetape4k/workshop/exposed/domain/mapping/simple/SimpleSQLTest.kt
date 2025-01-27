package io.bluetape4k.workshop.exposed.domain.mapping.simple

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SimpleSQLTest: AbstractExposedTest() {

    companion object: KLogging() {
        private const val ENTITY_COUNT = 10
    }

    private fun Transaction.persistSimpleEntity() {
        val names = List(ENTITY_COUNT) { faker.name().name() }

        SimpleTable.batchInsert(names) { name ->
            this[SimpleTable.name] = name
        }

        commit()
        entityCache.clear()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find by names`(testDB: TestDB) {
        withTables(testDB, SimpleTable) {
            persistSimpleEntity()

            val names: List<String> = SimpleTable.select(SimpleTable.name).map { it[SimpleTable.name] }

            val query: Query = SimpleTable.selectAll()
                .where { SimpleTable.name inList names }

            val entities: List<SimpleEntity> = SimpleEntity.wrapRows(query).toList()
            entities shouldHaveSize ENTITY_COUNT
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `project to DTO`(testDB: TestDB) {
        withTables(testDB, SimpleTable) {
            persistSimpleEntity()

            val query = SimpleTable.selectAll()
            val dtos = SimpleEntityDto.wrapRows(query)
            dtos shouldHaveSize ENTITY_COUNT
        }
    }
}
