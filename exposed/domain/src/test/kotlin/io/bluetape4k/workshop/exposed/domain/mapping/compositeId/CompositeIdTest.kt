package io.bluetape4k.workshop.exposed.domain.mapping.compositeId

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Authors
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Books
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Offices
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Publishers
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Reviews
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class CompositeIdTest: AbstractExposedTest() {

    companion object: KLogging()

    private val allTables = arrayOf(Publishers, Authors, Books, Reviews, Offices)

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop composite id tables`(dialect: TestDB) {
        transaction(db = dialect.db) {
            try {
                SchemaUtils.create(tables = allTables)
            } finally {
                SchemaUtils.drop(tables = allTables)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `composite id 의 컬럼이 없이 정의된 테이블을 사용하는 것은 실패합니다`(dialect: TestDB) {
        val missingIdsTable = object: CompositeIdTable("missing_ids_table") {
            val age = integer("age")
            val name = varchar("name", 50)
            override val primaryKey = PrimaryKey(age, name)
        }

        transaction(db = dialect.db) {
            // Table can be created with no issue
            SchemaUtils.create(missingIdsTable)

            assertFailsWith<IllegalStateException> {
                // but trying to use id property requires idColumns not being empty
                missingIdsTable.select(missingIdsTable.id).toList()
            }

            SchemaUtils.drop(missingIdsTable)
        }
    }
}
