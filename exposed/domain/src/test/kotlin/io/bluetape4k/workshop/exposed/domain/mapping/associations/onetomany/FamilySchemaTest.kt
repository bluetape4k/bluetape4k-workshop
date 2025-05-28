package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.FamilySchema.Child
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.FamilySchema.ChildTable
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.FamilySchema.Father
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.FamilySchema.FatherTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class FamilySchemaTest: AbstractExposedTest() {

    companion object: KLogging()

    private val allFamilyTables = arrayOf(FatherTable, ChildTable)


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with ordering`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 }  // collate 문제 (한글)

        withTables(testDB, *allFamilyTables) {

            val father1 = Father.new {
                name = ("이성계")
            }

            val childId2 = ChildTable.insertAndGetId {
                it[name] = "이방원"
                it[birthday] = LocalDate.of(1390, 2, 10)
                it[father] = father1.id
            }
            val childId3 = ChildTable.insertAndGetId {
                it[name] = "이방석"
                it[birthday] = LocalDate.of(1400, 1, 21)
                it[father] = father1.id
            }
            val childId1 = ChildTable.insertAndGetId {
                it[name] = "이방번"
                it[birthday] = LocalDate.of(1380, 10, 5)
                it[father] = father1.id
            }

            entityCache.clear()

            val loaded = Father.findById(father1.id)!!
            loaded shouldBeEqualTo father1

            loaded.children.count() shouldBeEqualTo 3

            /**
             * one-to-many with ordering
             *
             * ```sql
             * -- Postgres
             * SELECT child.id,
             *        child."name",
             *        child.birthday,
             *        child.father_id
             *   FROM child
             *  ORDER BY child.birthday ASC;
             *  ```
             */
            val expectedChildren = Child.all().orderBy(ChildTable.birthday to SortOrder.ASC).toList()

            /**
             * ```sql
             * -- Postgres
             * SELECT child.id,
             *        child."name",
             *        child.birthday,
             *        child.father_id
             *   FROM child
             *  WHERE child.father_id = 1
             *  ORDER BY child.birthday ASC,
             * ```
             */
            loaded.children.toList() shouldBeEqualTo expectedChildren
        }
    }
}
