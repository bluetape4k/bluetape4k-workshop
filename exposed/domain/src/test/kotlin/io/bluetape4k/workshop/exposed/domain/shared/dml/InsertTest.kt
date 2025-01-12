package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.assertFailAndRollback
import io.bluetape4k.workshop.exposed.domain.currentTestDB
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData.Cities
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.fail
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.substring
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.trim
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.wrapAsExpression
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*

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
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get id 01`(testDB: TestDB) {
        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(testDB, idTable) {
            idTable.insertAndGetId { it[name] = "1" }
            idTable.selectAll().count() shouldBeEqualTo 1L

            idTable.insertAndGetId { it[name] = "2" }
            idTable.selectAll().count() shouldBeEqualTo 2L

            assertFailAndRollback("Unique constraint failed") {
                idTable.insertAndGetId { it[name] = "2" }
            }
        }
    }

    /**
     * insertIgnoreAndGetId
     *
     * ```sql
     * INSERT INTO tmp (foo) VALUES ('1') ON CONFLICT DO NOTHING
     * INSERT INTO tmp (foo) VALUES ('2') ON CONFLICT DO NOTHING
     * INSERT INTO tmp (id, foo) VALUES (100, '2') ON CONFLICT DO NOTHING
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert ignore and get id 01`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in (TestDB.ALL_MYSQL_LIKE - TestDB.MYSQL_V5) + TestDB.ALL_POSTGRES_LIKE }

        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(testDB, idTable) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get id when column has different name and get value by id column`(testDB: TestDB) {
        val testTableWithId = object: IdTable<Int>("testTableWithId") {
            val code = integer("code")
            override val id: Column<EntityID<Int>> = code.entityId()
        }

        withTables(testDB, testTableWithId) {
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
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `id and column have different names and get value by original column`(testDB: TestDB) {
        val exampleTable = object: IdTable<String>("test_id_and_column_table") {
            val exampleColumn = varchar("example_column", 200)
            override val id: Column<EntityID<String>> = exampleColumn.entityId()
        }

        withTables(testDB, exampleTable) {
            val value = "value"
            exampleTable.insert {
                it[exampleColumn] = value
            }

            val resultValues = exampleTable.selectAll().map { it[exampleTable.exampleColumn] }
            resultValues.first() shouldBeEqualTo value
        }
    }

    /**
     * MYSQL V8
     * ```sql
     * INSERT IGNORE INTO tmp (id, foo) VALUES (1, '1')
     * INSERT IGNORE INTO tmp (id, foo) VALUES (1, '2')
     * ```
     *
     * POSTGRES
     * ```sql
     * INSERT INTO tmp (id, foo) VALUES (1, '1') ON CONFLICT DO NOTHING
     * INSERT INTO tmp (id, foo) VALUES (1, '2') ON CONFLICT DO NOTHING
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insertIgnoreAndGetId with predefined id`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in (TestDB.ALL_MYSQL_LIKE - TestDB.MYSQL_V5) + TestDB.ALL_POSTGRES_LIKE }
        
        val idTable = object: IntIdTable("tmp") {
            val name = varchar("foo", 10).uniqueIndex()
        }

        withTables(testDB, idTable) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert 01`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, users, _ ->
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
            users.selectAll().where { users.name inList userNamesWithCityIds.map { it.first } }
                .count() shouldBeEqualTo userNamesWithCityIds.size.toLong()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert with sequence`(testDB: TestDB) {
        val cities = Cities
        withTables(testDB, cities) {
            val batchSize = 25
            val names = generateSequence { TimebasedUuid.Epoch.nextIdAsString() }.take(batchSize)

            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

            cities.selectAll().count().toInt() shouldBeEqualTo batchSize
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert using empty sequence should work`(testDB: TestDB) {
        val cities = Cities
        withTables(testDB, cities) {
            val names = emptySequence<String>()

            cities.batchInsert(names) { name ->
                this[cities.name] = name
            }

            cities.selectAll().count().toInt() shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get generted key 01`(testDB: TestDB) {
        withTables(testDB, Cities) {
            val id = Cities.insert {
                it[name] = "FooCity"
            } get Cities.id

            Cities.selectAll().last()[Cities.id] shouldBeEqualTo id
        }
    }

    object TestLongIdTable: Table() {
        val id = long("id").autoIncrement()
        val name = text("name")

        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get generated key 02`(testDB: TestDB) {
        withTables(testDB, TestLongIdTable) {
            val id = TestLongIdTable.insert {
                it[name] = "Foo"
            } get TestLongIdTable.id

            TestLongIdTable.selectAll().last()[TestLongIdTable.id] shouldBeEqualTo id
        }
    }

    object IntIdTestTable: IntIdTable() {
        val name = text("name")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get generated key 03`(testDB: TestDB) {
        withTables(testDB, IntIdTestTable) {
            val id = IntIdTestTable.insertAndGetId {
                it[name] = "Foo"
            }
            IntIdTestTable.selectAll().last()[IntIdTestTable.id] shouldBeEqualTo id
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with predefined id`(testDB: TestDB) {
        val stringTable = object: IdTable<String>("stringTable") {
            override val id: Column<EntityID<String>> = varchar("id", 15).entityId()
            val name = text("name")
        }
        withTables(testDB, stringTable) {
            val entityID = EntityID("id1", stringTable)

            // INSERT INTO stringtable (id, "name") VALUES ('id1', 'Foo')
            val id1 = stringTable.insertAndGetId {
                it[stringTable.id] = entityID
                it[stringTable.name] = "Foo"
            }

            // INSERT INTO stringtable (id, "name") VALUES ('testId', 'Bar')
            stringTable.insertAndGetId {
                it[stringTable.id] = EntityID("testId", stringTable)
                it[stringTable.name] = "Bar"
            }

            id1 shouldBeEqualTo entityID
            val row1 = stringTable.selectAll().where { stringTable.id eq entityID }.singleOrNull()
            row1?.get(stringTable.id) shouldBeEqualTo entityID

            val row2 = stringTable.selectAll().where { stringTable.id like "id%" }.singleOrNull()
            row2?.get(stringTable.id) shouldBeEqualTo entityID
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with foreign id`(testDB: TestDB) {
        val idTable = object: IntIdTable("idTable") {}
        val standardTable = object: Table("standardTable") {
            val externalId = reference("externalId", idTable.id)
        }
        withTables(testDB, idTable, standardTable) {
            val id1 = idTable.insertAndGetId {}
            standardTable.insert {
                it[externalId] = id1
            }

            val rows = standardTable.selectAll().map { it[standardTable.externalId] }
            rows shouldBeEqualTo listOf(id1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with expression`(testDB: TestDB) {
        val tbl = object: IntIdTable("testInsert") {
            val nullableInt = integer("nullableInt").nullable()
            val string = varchar("stringCol", 255)
        }

        fun expression(value: String) = stringLiteral(value).trim().substring(2, 4)

        fun verify(value: String) {
            val row = tbl.selectAll().where { tbl.string eq value }.single()
            row[tbl.string] shouldBeEqualTo value
        }

        withTables(testDB, tbl) {
            // INSERT INTO testinsert ("stringCol") VALUES (SUBSTRING(TRIM(' _exp1_ '), 2, 4))
            tbl.insert {
                it[string] = expression(" _exp1_ ")
            }
            verify("exp1")

            // INSERT INTO testinsert ("stringCol", "nullableInt") VALUES (SUBSTRING(TRIM(' _exp2_ '), 2, 4), 5)
            tbl.insert {
                it[string] = expression(" _exp2_ ")
                it[nullableInt] = 5
            }
            verify("exp2")

            // INSERT INTO testinsert ("stringCol", "nullableInt") VALUES (SUBSTRING(TRIM(' _exp3_ '), 2, 4), NULL)
            tbl.insert {
                it[string] = expression(" _exp3_ ")
                it[nullableInt] = null
            }
            verify("exp3")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with column expression`(testDB: TestDB) {
        val tbl1 = object: IntIdTable("testInsert1") {
            val string1 = varchar("stringCol", 20)
        }
        val tbl2 = object: IntIdTable("testInsert2") {
            val string2 = varchar("stringCol", 20).nullable()
        }

        fun verify(value: String) {
            val row = tbl2.selectAll().where { tbl2.string2 eq value }.single()
            row[tbl2.string2] shouldBeEqualTo value
        }

        withTables(testDB, tbl1, tbl2) {
            // INSERT INTO testinsert1 ("stringCol") VALUES ('_exp1_')
            val id = tbl1.insertAndGetId {
                it[string1] = "_exp1_"
            }

            /**
             * ```sql
             * INSERT INTO TESTINSERT2 ("stringCol")
             * VALUES (
             *         (SELECT SUBSTRING(TRIM(TESTINSERT1."stringCol"), 2, 4)
             *           FROM TESTINSERT1
             *           WHERE TESTINSERT1.ID = 1
             *         )
             * )
             * ```
             */
            val expr1 = tbl1.string1.trim().substring(2, 4)
            tbl2.insert {
                it[string2] = wrapAsExpression(tbl1.select(expr1).where { tbl1.id eq id })
            }
            verify("exp1")
        }
    }

    private object OrderedDataTable: IntIdTable() {
        val name = text("name")
        val order = integer("order")
    }

    class OrderedData(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<OrderedData>(OrderedDataTable)

        var name by OrderedDataTable.name
        var order by OrderedDataTable.order

        override fun toString(): String = "OrderedData(id=$id, name=$name, order=$order)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with column named with keyword`(testDB: TestDB) {
        withTables(testDB, OrderedDataTable) {
            val foo = OrderedData.new {
                name = "foo"
                order = 20
            }
            val bar = OrderedData.new {
                name = "bar"
                order = 10
            }

            val orders = OrderedData.all()
                .orderBy(OrderedDataTable.order to SortOrder.ASC)
                .toList()

            orders shouldBeEqualTo listOf(bar, foo)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `check column length on insert`(testDB: TestDB) {
        val stringTable = object: Table("stringTable") {
            val name = varchar("name", 10)
        }

        withTables(testDB, stringTable) {
            val veryLongString = "1".repeat(255)

            expectException<IllegalArgumentException> {
                stringTable.insert {
                    it[name] = veryLongString
                }
            }
        }
    }

    /**
     * Use subquery in an insert statement
     *
     * ```sql
     * INSERT INTO TAB1 (ID) VALUES ((SELECT TAB2.ID FROM TAB2 WHERE TAB2.ID = 'foo'))
     * ```
     *
     * Use subquery in an update statement
     *
     * ```sql
     * UPDATE TAB1 SET ID=(SELECT TAB2.ID FROM TAB2 WHERE TAB2.ID = 'bar') WHERE TAB1.ID = 'foo'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `subquery in an insert or update statement`(testDB: TestDB) {
        val tbl1 = object: Table("tab1") {
            val id = varchar("id", 10)
        }
        val tbl2 = object: Table("tab2") {
            val id = varchar("id", 10)
        }

        withTables(testDB, tbl1, tbl2) {
            // Initial data
            tbl2.insert { it[id] = "foo" }
            tbl2.insert { it[id] = "bar" }

            /**
             * Use subquery in an insert statement
             *
             * ```sql
             * INSERT INTO TAB1 (ID)
             * VALUES ((SELECT TAB2.ID FROM TAB2 WHERE TAB2.ID = 'foo'))
             * ```
             */
            tbl1.insert {
                it[id] = tbl2.select(tbl2.id).where { tbl2.id eq "foo" }
            }

            // Check inserted data
            val insertedId = tbl1.select(tbl1.id).single()[tbl1.id]
            insertedId shouldBeEqualTo "foo"

            /**
             * Use subquery in an update statement
             *
             * ```sql
             * UPDATE TAB1
             *    SET ID=(SELECT TAB2.ID FROM TAB2 WHERE TAB2.ID = 'bar')
             *  WHERE TAB1.ID = 'foo'
             * ```
             */
            tbl1.update({ tbl1.id eq "foo" }) {
                it[id] = tbl2.select(tbl2.id).where { tbl2.id eq "bar" }
            }

            val updatedId = tbl1.select(tbl1.id).single()[tbl1.id]
            updatedId shouldBeEqualTo "bar"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `generated key 04`(testDB: TestDB) {
        val charIdTable = object: IdTable<String>("charIdTable") {
            override val id = varchar("id", 50)
                .clientDefault { Base58.randomString(6) }
                .entityId()
            val foo = integer("foo")

            override val primaryKey = PrimaryKey(id)
        }
        withTables(testDB, charIdTable) {
            // INSERT INTO charIdTable (id, foo) VALUES ('vLH7J6', 42)
            val id = charIdTable.insertAndGetId {
                it[foo] = 42
            }
            id.value.shouldNotBeNull()
        }
    }

    private fun rollbackSupportDbs() = TestDB.ALL_H2 + TestDB.MYSQL_V8 + TestDB.POSTGRESQL

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `rollback on constraint exception with normal transactions`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 + TestDB.MYSQL_V8 + TestDB.POSTGRESQL }

        val testTable = object: IntIdTable("TestRollback") {
            val foo = integer("foo").check { it greater 0 }
        }
        try {
            try {
                withDb(testDB) {
                    SchemaUtils.create(testTable)
                    testTable.insert { it[foo] = 1 }
                    testTable.insert { it[foo] = 0 }
                }
                fail("예외가 발생해서 Rollback 이 수행되어야 합니다.")
            } catch (e: Throwable) {
                // 예외 발생 시 Rollback 이 수행되어야 합니다.
            }
            withDb(testDB) {
                testTable.selectAll().empty().shouldBeTrue()
            }
        } finally {
            withDb(testDB) {
                SchemaUtils.drop(testTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `rollback on constraint exception with suspend transactions`(testDB: TestDB) = runTest {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 + TestDB.MYSQL_V8 + TestDB.POSTGRESQL }

        val testTable = object: IntIdTable("TestRollback") {
            val foo = integer("foo").check { it greater 0 }
        }
        try {
            try {
                withDb(testDB) {
                    SchemaUtils.create(testTable)
                }
                runBlocking {
                    newSuspendedTransaction(db = testDB.db) {
                        testTable.insert { it[foo] = 1 }
                        testTable.insert { it[foo] = 0 }
                    }
                }
                fail("예외가 발생해서 Rollback 이 수행되어야 합니다.")
            } catch (e: Throwable) {
                // 예외 발생 시 Rollback 이 수행되어야 합니다.
            }
            withDb(testDB) {
                testTable.selectAll().empty().shouldBeTrue()
            }
        } finally {
            withDb(testDB) {
                SchemaUtils.drop(testTable)
            }
        }
    }

    class BatchInsertOnConflictDoNothing(table: Table): BatchInsertStatement(table) {
        override fun prepareSQL(transaction: Transaction, prepared: Boolean): String = buildString {
            val insertStatement = super.prepareSQL(transaction, prepared)
            when (val db = currentTestDB) {
                in TestDB.ALL_MYSQL_LIKE -> {
                    append("INSERT IGNORE ")
                    append(insertStatement.substringAfter("INSERT "))
                }

                else                     -> {
                    append(insertStatement)
                    val identifier = if (db == TestDB.H2_PSQL) "" else "(id) "
                    append(" ON CONFLICT ${identifier}DO NOTHING")
                }
            }
        }
    }

    /**
     * Batch Insert with ON CONFLICT DO NOTHING
     *
     * H2_MYSQL
     *
     * ```sql
     * INSERT IGNORE INTO TAB (ID) VALUES ('foo')
     * INSERT IGNORE INTO TAB (ID) VALUES ('bar')
     * ```
     *
     * H2_PSQL
     * ```
     * INSERT INTO tab (id) VALUES ('foo') ON CONFLICT DO NOTHING
     * INSERT INTO tab (id) VALUES ('bar') ON CONFLICT DO NOTHING
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert number of inserted rows`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in (TestDB.ALL_MYSQL_LIKE - TestDB.MYSQL_V5) + TestDB.ALL_POSTGRES_LIKE }
        
        val tab = object: Table("tab") {
            val id = varchar("id", 10).uniqueIndex()
        }
        withTables(testDB, tab) {
            tab.insert { it[id] = "foo" }

            val numInserted = BatchInsertOnConflictDoNothing(tab).run {
                addBatch()
                this[tab.id] = "foo"        // 중복되므로 insert 되지 않음

                addBatch()
                this[tab.id] = "bar"

                execute(this@withTables)
            }
            numInserted shouldBeEqualTo 1
        }
    }

    /**
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS GENERATEDTABLE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      AMOUNT INT NULL,
     *      COMPUTED_AMOUNT INT GENERATED ALWAYS AS (AMOUNT + 1 ) NULL
     * )
     * ```
     *
     * ```sql
     * INSERT INTO GENERATEDTABLE (AMOUNT) VALUES (99)
     * INSERT INTO GENERATEDTABLE (AMOUNT) VALUES (NULL)
     * ```
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert into nullable generated column`(testDb: TestDB) {
        withDb(testDb) {
            val generatedTable = object: IntIdTable("generatedTable") {
                val amount = integer("amount").nullable()
                val computedAmount = integer("computed_amount").nullable().databaseGenerated().apply {
                    if (testDb in TestDB.ALL_H2) {
                        withDefinition("GENERATED ALWAYS AS (AMOUNT + 1 )")
                    } else {
                        withDefinition("GENERATED ALWAYS AS (AMOUNT + 1 ) STORED")
                    }
                }
            }
            try {
                val computedName = generatedTable.computedAmount.name.inProperCase()
                val computedType = generatedTable.computedAmount.columnType.sqlType()
                val computation = "${generatedTable.amount.name.inProperCase()} + 1"

                val createStatement =
                    """
                    CREATE TABLE ${addIfNotExistsIfSupported()}${generatedTable.tableName.inProperCase()} (
                        ${generatedTable.id.descriptionDdl()},
                        ${generatedTable.amount.descriptionDdl()},
                    """.trimIndent()

                SchemaUtils.create(generatedTable)

                assertFailAndRollback("Generated columns are auto-drived and read-only") {
                    generatedTable.insert {
                        it[amount] = 99
                        it[computedAmount] = 100
                    }
                }

                generatedTable.insert {
                    it[amount] = 99
                }

                val result1 = generatedTable.selectAll().single()
                result1[generatedTable.computedAmount] shouldBeEqualTo result1[generatedTable.amount]?.plus(1)

                generatedTable.insert {
                    it[amount] = null
                }

                val result2 = generatedTable.selectAll().where { generatedTable.amount.isNull() }.single()
                result2[generatedTable.amount].shouldBeNull()
                result2[generatedTable.computedAmount].shouldBeNull()
            } finally {
                SchemaUtils.drop(generatedTable)
            }
        }
    }

    /**
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_NO_AUTO_INCREMENT_TABLE (CUSTOM_ID VARCHAR(128) PRIMARY KEY)
     *
     * INSERT INTO TEST_NO_AUTO_INCREMENT_TABLE (CUSTOM_ID) VALUES ('ywHqxb4j')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `no auto increment applied to custom string primary key`(testDB: TestDB) {
        val tester = object: IdTable<String>("test_no_auto_increment_table") {
            val customId = varchar("custom_id", 128)

            override val primaryKey = PrimaryKey(customId)
            override val id: Column<EntityID<String>> = customId.entityId()
        }

        val customId = Base58.randomString(8)
        withTables(testDB, tester) {
            val result1 = tester.batchInsert(listOf(customId)) {
                this[tester.customId] = it
            }.single()

            result1[tester.id].value shouldBeEqualTo customId
            result1[tester.customId] shouldBeEqualTo customId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert returns values from default expression`(testDB: TestDB) {
        // Postgres 만 지원됩니다.
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        val tester = object: Table() {
            val defaultDate = timestamp("default_date").defaultExpression(CurrentTimestamp)
        }

        withTables(testDB, tester) {
            val entry = tester.insert { }

            log.debug { "default_date=${entry[tester.defaultDate]}" }
            entry[tester.defaultDate].shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `database generated uuid as primary key`(testDB: TestDB) {
        // Postgres 만 지원됩니다.
        Assumptions.assumeTrue(testDB in TestDB.ALL_POSTGRES)

        val randomPGUUID = object: CustomFunction<UUID>("gen_random_uuid", UUIDColumnType()) {}

        val tester = object: IdTable<UUID>("test_uuid_table") {
            override val id: Column<EntityID<UUID>> = uuid("id").defaultExpression(randomPGUUID).entityId()
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, tester) {
            val entry = tester.insertAndGetId { }
            log.debug { "generated uuid id=${entry.value}" }
            entry.value.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default values and nullable columns not in batch insert arguments`(testDB: TestDB) {
        val tester = object: IntIdTable("test_batch_insert_defaults") {
            val number = integer("number")
            val default = varchar("default", 128).default("default")
            val defaultExpr = varchar("default_expr", 128).defaultExpression(stringLiteral("defaultExpr"))
            val nullable = varchar("nullable", 128).nullable()
            val nullableDefaultNull = varchar("nullable_default_null", 128).nullable().default(null)
            val nullableDefaultNotNull =
                varchar("nullable_default_not_null", 128).nullable().default("nullableDefaultNotNull")
            val databaseGenerated = integer("database_generated").withDefinition("DEFAULT 1").databaseGenerated()
        }

        val testerWithFakeDefaults = object: IntIdTable("test_batch_insert_defaults") {
            val number = integer("number")
            val default = varchar("default", 128).default("default-fake")
            val defaultExpr = varchar("default_expr", 128).defaultExpression(stringLiteral("defaultExpr-fake"))
            val nullable = varchar("nullable", 128).nullable().default("null-fake")
            val nullableDefaultNull = varchar("nullable_default_null", 128).nullable().default("null-fake")
            val nullableDefaultNotNull =
                varchar("nullable_default_not_null", 128).nullable().default("nullableDefaultNotNull-fake")
            val databaseGenerated = integer("database_generated").default(-1)
        }

        withTables(testDB, tester) {
            testerWithFakeDefaults.batchInsert(listOf(1, 2, 3)) {
                this[tester.number] = 10
            }

            testerWithFakeDefaults.selectAll().forEach {
                it[testerWithFakeDefaults.default] shouldBeEqualTo "default"
                it[testerWithFakeDefaults.defaultExpr] shouldBeEqualTo "defaultExpr"
                it[testerWithFakeDefaults.nullable].shouldBeNull()
                it[testerWithFakeDefaults.nullableDefaultNull].shouldBeNull()
                it[testerWithFakeDefaults.nullableDefaultNotNull] shouldBeEqualTo "nullableDefaultNotNull"
                it[testerWithFakeDefaults.databaseGenerated] shouldBeEqualTo 1
            }
        }
    }
}
