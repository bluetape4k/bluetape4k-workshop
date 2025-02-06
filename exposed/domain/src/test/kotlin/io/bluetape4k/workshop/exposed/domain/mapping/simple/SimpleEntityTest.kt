package io.bluetape4k.workshop.exposed.domain.mapping.simple

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class SimpleEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `equals for entities`(testDB: TestDB) {
        withTables(testDB, SimpleTable) {
            val name1 = faker.name().name()
            val name2 = faker.name().name()

            val entity1 = SimpleEntity.new { name = name1 }
            val entity2 = SimpleEntity.new { name = name2 }

            entityCache.clear()

            val persisted1 = SimpleEntity.findById(entity1.id)!!
            val persisted2 = SimpleEntity.findById(entity2.id)!!

            persisted1 shouldBeEqualTo entity1
            persisted2 shouldBeEqualTo entity2
            persisted1 shouldNotBeEqualTo persisted2
        }
    }

    /**
     * Unique index violation
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `violance unique index`(testDB: TestDB) {
        withTables(testDB, SimpleTable) {
            val name = faker.name().name()

            SimpleEntity.new { this.name = name }
            commit()

            assertFailsWith<ExposedSQLException> {
                SimpleEntity.new { this.name = name }
                commit()
            }
        }
    }

    /**
     * Batch Insert 작업을 수행합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert records`(testDB: TestDB) {
        withTables(testDB, SimpleTable) {
            val entityCount = 100

            val names = List(entityCount) { faker.name().name() }.distinct()
            SimpleTable.batchInsert(names) { name ->
                this[SimpleTable.name] = name
                this[SimpleTable.description] = faker.lorem().paragraph()
            }

            /**
             * ```sql
             * SELECT COUNT(*) FROM simple_entity;
             * SELECT COUNT(*) FROM simple_entity;
             * ```
             */
            // SQL DSL 로 조회
            SimpleTable.selectAll().count() shouldBeEqualTo names.size.toLong()

            // DAO 로 조회
            SimpleEntity.all().count() shouldBeEqualTo names.size.toLong()
        }
    }
}
