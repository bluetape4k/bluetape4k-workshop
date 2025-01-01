package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.assertFailAndRollback
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Test

class InsertTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     *
     * ```sql
     * INSERT INTO TMP (FOO) VALUES ('1')
     * ```
     *
     * ```sql
     * INSERT INTO TMP (FOO) VALUES ('2')
     * ```
     */
    @Test
    fun `insert and get id 01`() {
        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(idTable) {
            idTable.insertAndGetId { it[name] = "1" }
            idTable.selectAll().count() shouldBeEqualTo 1L

            idTable.insertAndGetId { it[name] = "2" }
            idTable.selectAll().count() shouldBeEqualTo 2L

            assertFailAndRollback("Unique constraint failed") {
                idTable.insertAndGetId { it[name] = "2" }
            }
        }
    }

    private val insertIgnoreUnsupportedDB = TestDB.entries -
            listOf(TestDB.MYSQL_V5, TestDB.H2_MYSQL, TestDB.POSTGRESQL, TestDB.H2_PSQL)

    /**
     * insertIgnoreAndGetId
     *
     * ```sql
     * INSERT INTO tmp (foo) VALUES ('1') ON CONFLICT DO NOTHING
     * INSERT INTO tmp (foo) VALUES ('2') ON CONFLICT DO NOTHING
     * INSERT INTO tmp (id, foo) VALUES (100, '2') ON CONFLICT DO NOTHING
     * ```
     */
    @Test
    fun `insert ignore and get id 01`() {
        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(excludeSettings = insertIgnoreUnsupportedDB, idTable) {
            // INSERT INTO tmp (foo) VALUES ('1') ON CONFLICT DO NOTHING
            idTable.insertIgnoreAndGetId { it[name] = "1" }
            idTable.selectAll().count() shouldBeEqualTo 1L

            // INSERT INTO tmp (foo) VALUES ('2') ON CONFLICT DO NOTHING
            idTable.insertIgnoreAndGetId { it[name] = "2" }
            idTable.selectAll().count() shouldBeEqualTo 2L

            // INSERT INTO tmp (foo) VALUES ('2') ON CONFLICT DO NOTHING
            val idNull = idTable.insertIgnoreAndGetId { it[name] = "2" }
            idNull.shouldBeNull()
            idTable.selectAll().count() shouldBeEqualTo 2L

            // INSERT INTO tmp (id, foo) VALUES (100, '2') ON CONFLICT DO NOTHING
            val shouldNotReturnProvidedIdOnConflict = idTable.insertIgnoreAndGetId {
                it[idTable.id] = EntityID(100, idTable)
                it[idTable.name] = "2"
            }
            shouldNotReturnProvidedIdOnConflict.shouldBeNull()
        }
    }

    @Test
    fun `insert and get id when column has different name and get value by id column`() {
        val testTableWithId = object: IdTable<Int>("testTableWithId") {
            val code = integer("code")
            override val id: Column<EntityID<Int>> = code.entityId()
        }

        withTables(testTableWithId) {
            val id1 = testTableWithId.insertAndGetId {
                it[code] = 1
            }
            id1.shouldNotBeNull()
            id1.value shouldBeEqualTo 1

            val id2 = testTableWithId.insert {
                it[code] = 2
            } get testTableWithId.id

            id2.shouldNotBeNull()
            id2.value shouldBeEqualTo 2
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_ID_AND_COLUMN_TABLE (EXAMPLE_COLUMN VARCHAR(200) NOT NULL)
     *
     * INSERT INTO TEST_ID_AND_COLUMN_TABLE (EXAMPLE_COLUMN) VALUES ('value')
     *
     * SELECT TEST_ID_AND_COLUMN_TABLE.EXAMPLE_COLUMN FROM TEST_ID_AND_COLUMN_TABLE
     * ```
     */
    @Test
    fun `id and column have different names and get value by original column`() {
        val exampleTable = object: IdTable<String>("test_id_and_column_table") {
            val exampleColumn = varchar("example_column", 200)
            override val id: Column<EntityID<String>> = exampleColumn.entityId()
        }

        withTables(exampleTable) {
            val value = "value"
            exampleTable.insert {
                it[exampleColumn] = value
            }

            val resultValues = exampleTable.selectAll().map { it[exampleTable.exampleColumn] }
            resultValues.first() shouldBeEqualTo value
        }
    }

    @Test
    fun `insertIgnoreAndGetId with predefined id`() {
        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(excludeSettings = insertIgnoreUnsupportedDB, idTable) {
            val insertedStatement = idTable.insertIgnore {
                it[idTable.id] = EntityID(1, idTable)
                it[idTable.name] = "1"
            }
            insertedStatement[idTable.id].value shouldBeEqualTo 1
            insertedStatement.insertedCount shouldBeEqualTo 1

            // ID가 중복되므로 insert가 되지 않음
            val notInsertedStatement = idTable.insertIgnore {
                it[idTable.id] = EntityID(1, idTable)
                it[idTable.name] = "2"
            }
            notInsertedStatement[idTable.id].value shouldBeEqualTo 1
            notInsertedStatement.insertedCount shouldBeEqualTo 0
        }
    }

    @Test
    fun `batch insert 01`() {
        withCitiesAndUsers { cities, users, _ ->
            val cityNames = listOf("Paris", "Moscow", "Helsinki")

            // INSERT INTO CITIES ("name") VALUES ('Paris')
            // INSERT INTO CITIES ("name") VALUES ('Moscow')
            // INSERT INTO CITIES ("name") VALUES ('Helsinki')
            val allCitiesID = cities.batchInsert(cityNames) { name ->
                this[cities.name] = name
            }
            allCitiesID.size shouldBeEqualTo cityNames.size

            val userNamesWithCityIds = allCitiesID.mapIndexed { index, rows ->
                "UserFrom${cityNames[index]}" to rows[cities.id] as Number
            }

            // INSERT INTO USERS (ID, "name", CITY_ID) VALUES ('vLH7J6', 'UserFromParis', 4)
            // INSERT INTO USERS (ID, "name", CITY_ID) VALUES ('NGE25y', 'UserFromMoscow', 5)
            // INSERT INTO USERS (ID, "name", CITY_ID) VALUES ('9M7E68', 'UserFromHelsinki', 6)
            val generatedIds = users.batchInsert(userNamesWithCityIds) { (userName, cityId) ->
                this[users.id] = Base58.randomString(6)
                this[users.name] = userName
                this[users.cityId] = cityId.toInt()
            }

            generatedIds.size shouldBeEqualTo userNamesWithCityIds.size

            // SELECT COUNT(*) FROM USERS WHERE USERS."name" IN ('UserFromParis', 'UserFromMoscow', 'UserFromHelsinki')
            users.selectAll()
                .where { users.name inList userNamesWithCityIds.map { it.first } }
                .count() shouldBeEqualTo userNamesWithCityIds.size.toLong()
        }
    }

    @Test
    fun `batch insert with sequence`() {
        val cities = DMLTestData.Cities
        withTables(cities) {
            val batchSize = 25
            val names = generateSequence { TimebasedUuid.Epoch.nextIdAsString() }.take(batchSize)

            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

            cities.selectAll().count().toInt() shouldBeEqualTo batchSize
        }
    }
}
