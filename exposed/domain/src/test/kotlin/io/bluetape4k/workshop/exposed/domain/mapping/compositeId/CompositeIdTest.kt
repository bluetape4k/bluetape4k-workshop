package io.bluetape4k.workshop.exposed.domain.mapping.compositeId

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withDb
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CompositeIdTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop composite id tables`(testDB: TestDB) {
        withDb(testDB) {
            try {
                SchemaUtils.create(tables = BookSchema.allTables)
            } finally {
                SchemaUtils.drop(tables = BookSchema.allTables)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `composite id 의 컬럼이 없이 정의된 테이블을 사용하는 것은 실패합니다`(testDB: TestDB) {
        /**
         * Error: Table definition must include id columns. Please use Column.entityId() or IdTable.addIdColumn().
         * ```sql
         * CREATE TABLE IF NOT EXISTS missing_ids_table (
         *      age INT,
         *      "name" VARCHAR(50),
         *
         *      CONSTRAINT pk_missing_ids_table PRIMARY KEY (age, "name")
         * )
         * ```
         */
        val missingIdsTable = object: CompositeIdTable("missing_ids_table") {
            val age = integer("age")                    // .entityId()
            val name = varchar("name", 50)     // .entityId()
            override val primaryKey = PrimaryKey(age, name)
        }

        withDb(testDB) {
            // Table can be created with no issue
            SchemaUtils.create(missingIdsTable)

            expectException<IllegalStateException> {
                // but trying to use id property requires idColumns not being empty
                missingIdsTable.select(missingIdsTable.id).toList()
            }

            SchemaUtils.drop(missingIdsTable)
        }
    }
}
