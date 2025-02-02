package io.bluetape4k.workshop.exposed.domain.shared.ddl

import MigrationUtils
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withSchemas
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldStartWith
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Coalesce
import org.jetbrains.exposed.sql.Index
import org.jetbrains.exposed.sql.Op.TRUE
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.expect

class CreateIndexTest: AbstractExposedTest() {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create standard index`(testDB: TestDB) {
        val testTable = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", 42)

            override val primaryKey = PrimaryKey(id)
            val byName = index("test_table_by_name", false, name)
        }

        withTables(testDB, testTable) {
            // SchemaUtils.createMissingTablesAndColumns(testTable)
            val statemnts = MigrationUtils.statementsRequiredForDatabaseMigration(testTable)
            exec(statemnts.joinToString(";"))

            testTable.exists().shouldBeTrue()
            SchemaUtils.drop(testTable)
        }
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS test_table (
     *      id INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL
     * );
     * CREATE INDEX test_table_by_name ON test_table ("name");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create hash index`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL_LIKE }

        val testTable = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", 42)

            override val primaryKey = PrimaryKey(id)
            val byName = index("test_table_by_name", false, name)
        }

        withTables(testDB, testTable) {
            // SchemaUtils.createMissingTablesAndColumns(testTable)
            val statemnts = MigrationUtils.statementsRequiredForDatabaseMigration(testTable)
            exec(statemnts.joinToString(";"))

            testTable.exists().shouldBeTrue()
        }
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS test_table (
     *      id INT NOT NULL,
     *      "name" VARCHAR(42) NOT NULL
     * );
     * CREATE INDEX text_index ON test_table ("name");
     * CREATE INDEX test_table_id_name ON test_table (id, "name");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create index with table in different schema`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 || testDB == TestDB.H2_PSQL }

        val testTable = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", 42).index("text_index")

            init {
                index(false, id, name)
            }
        }

        val schema1 = Schema("Schema1")
        val schema2 = Schema("Schema2")

        withSchemas(testDB, schema1, schema2) {
            SchemaUtils.setSchema(schema1)
            SchemaUtils.createMissingTablesAndColumns(testTable)
//            val statemnts = MigrationUtils.statementsRequiredForDatabaseMigration(testTable)
//            exec(statemnts.joinToString(";"))
            testTable.exists().shouldBeTrue()

            SchemaUtils.setSchema(schema2)
            testTable.exists().shouldBeFalse()

            SchemaUtils.createMissingTablesAndColumns(testTable)
//            val statemnts2 = MigrationUtils.statementsRequiredForDatabaseMigration(testTable)
//            exec(statemnts2.joinToString(";"))
            testTable.exists().shouldBeTrue()
        }
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS partialindextabletest (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(50) NOT NULL,
     *      "value" INT NOT NULL,
     *      "anotherValue" INT NOT NULL,
     *      flag BOOLEAN NOT NULL
     * );
     *
     * CREATE INDEX flag_index ON partialindextabletest (flag, "name") WHERE partialindextabletest.flag = TRUE;
     *
     * CREATE INDEX partialindextabletest_value_name ON partialindextabletest ("value", "name") WHERE (partialindextabletest."name" = 'aaa') AND (partialindextabletest."value" >= 6);
     *
     * ALTER TABLE partialindextabletest ADD CONSTRAINT partialindextabletest_anothervalue_unique UNIQUE ("anotherValue");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop partial index with postgres`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        val partialIndexTable = object: IntIdTable("PartialIndexTableTest") {
            val name = varchar("name", 50)
            val value = integer("value")
            val anotherValue = integer("anotherValue")
            val flag = bool("flag")

            init {
                index("flag_index", columns = arrayOf(flag, name)) {
                    flag eq true
                }
                index(columns = arrayOf(value, name)) {
                    (name eq "aaa") and (value greaterEq 6)
                }
                uniqueIndex(columns = arrayOf(anotherValue))
            }
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(partialIndexTable)
            partialIndexTable.exists().shouldBeTrue()

            // check that indexes are created and contain the proper filtering conditions
            exec(
                """SELECT indexname AS INDEX_NAME,
                   substring(indexdef, strpos(indexdef, ' WHERE ') + 7) AS FILTER_CONDITION
                   FROM pg_indexes
                   WHERE tablename='partialindextabletest' AND indexname != 'partialindextabletest_pkey'
                """.trimIndent()
            ) {
                var totalIndexCount = 0
                while (it.next()) {
                    totalIndexCount += 1
                    val filter = it.getString("FILTER_CONDITION")

                    when (it.getString("INDEX_NAME")) {
                        "partialindextabletest_value_name" -> filter shouldBeEqualTo "(((name)::text = 'aaa'::text) AND (value >= 6))"
                        "flag_index" -> filter shouldBeEqualTo "(flag = true)"
                        "partialindextabletest_anothervalue_unique" -> filter.shouldStartWith(" UNIQUE INDEX ")
                    }
                }
                totalIndexCount shouldBeEqualTo 3
            }

            val dropIndex = Index(
                columns = listOf(partialIndexTable.value, partialIndexTable.name),
                unique = false
            ).dropStatement().first()
            dropIndex.shouldStartWith("DROP INDEX ")

            val dropUniqueConstraint = Index(
                columns = listOf(partialIndexTable.anotherValue),
                unique = true
            ).dropStatement().first()
            dropUniqueConstraint.shouldStartWith("ALTER TABLE ")

            execInBatch(listOf(dropUniqueConstraint, dropIndex))

            getIndices(partialIndexTable).size shouldBeEqualTo 1
            SchemaUtils.drop(partialIndexTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop partial index`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.POSTGRESQL }

        val tester = object: Table("tester") {
            val name = varchar("name", 32).uniqueIndex()
            val age = integer("age")
            val team = varchar("team", 32)

            init {
                uniqueIndex("team_only_index", team) { team eq "A" }
                index("name_age_index", isUnique = false, name, age) { age greaterEq 20 }
            }
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(tester)
            tester.exists().shouldBeTrue()

            val createdStatements = tester.indices.map { SchemaUtils.createIndex(it).first() }
            createdStatements.size shouldBeEqualTo 3

            if (currentDialectTest is SQLiteDialect) {
                createdStatements.all { it.startsWith("CREATE ") }.shouldBeTrue()
            } else {
                createdStatements.count { it.startsWith("CREATE ") } shouldBeEqualTo 2
                createdStatements.count { it.startsWith("ALTER TABLE ") } shouldBeEqualTo 1
            }

            tester.indices.count { it.filterCondition != null } shouldBeEqualTo 2

            var indices = getIndices(tester)
            indices.size shouldBeEqualTo 3

            val uniqueWithPartial = Index(
                listOf(tester.team),
                true,
                "team_only_index",
                null,
                TRUE
            ).dropStatement().first()

            val dropStatements = indices.map { it.dropStatement().first() }
            expect(Unit) { execInBatch(dropStatements + uniqueWithPartial) }

            indices = getIndices(tester)
            indices.shouldBeEmpty()

            // test for non-unique partial index with type
            val type: String? = when (currentDialectTest) {
                is PostgreSQLDialect -> "BTREE"
                is SQLServerDialect -> "NONCLUSTERED"
                else -> null
            }
            val typedPartialIndex = Index(
                listOf(tester.name),
                false,
                "name_only_index",
                type,
                tester.name neq "Default"
            )
            val createdIndex = SchemaUtils.createIndex(typedPartialIndex).single()
            createdIndex.shouldStartWith("CREATE ")
            createdIndex shouldContain " WHERE "
            typedPartialIndex.dropStatement().first().shouldStartWith("DROP INDEX ")

            SchemaUtils.drop(tester)
        }
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS tester (age INT NOT NULL);
     * CREATE INDEX age_index ON tester (age) WHERE tester.age >= 10;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `partial index not created`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val age = integer("age")

            init {
                index("age_index", false, age) { age greaterEq 10 }
            }
        }

        withTables(testDB, tester) {
            SchemaUtils.createMissingTablesAndColumns()
//            val statemnts = MigrationUtils.statementsRequiredForDatabaseMigration()
//            exec(statemnts.joinToString(";"))
            tester.exists().shouldBeTrue()

            val expectedIndexCount = when (currentDialectTest) {
                is PostgreSQLDialect, is SQLServerDialect, is SQLiteDialect -> 1
                else -> 0
            }
            val actualIndexCount = currentDialectTest.existingIndices(tester)[tester].orEmpty().size
            actualIndexCount shouldBeEqualTo expectedIndexCount
        }
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS tester (
     *      id SERIAL PRIMARY KEY,
     *      amount INT NOT NULL,
     *      price INT NOT NULL,
     *      item VARCHAR(32) NULL
     * );
     * CREATE INDEX tester_plus_index ON tester ((tester.amount + tester.price));
     * CREATE INDEX tester_lower ON tester (LOWER(tester.item));
     * CREATE UNIQUE INDEX tester_price_coalesce_unique ON tester (price, COALESCE(tester.item, '*'));
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop functional index`(testDB: TestDB) {
        // H2 does not support functional indexes
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 && testDB != TestDB.MYSQL_V5 }

        val tester = object: IntIdTable("tester") {
            val amount = integer("amount")
            val price = integer("price")
            val item = varchar("item", 32).nullable()

            init {
                index(customIndexName = "tester_plus_index", isUnique = false, functions = listOf(amount.plus(price)))
                index(isUnique = false, functions = listOf(item.lowerCase()))
                uniqueIndex(columns = arrayOf(price), functions = listOf(Coalesce(item, stringLiteral("*"))))
            }
        }

        withTables(testDB, tester) {
            SchemaUtils.createMissingTablesAndColumns()
//            val statemnts = MigrationUtils.statementsRequiredForDatabaseMigration()
//            exec(statemnts.joinToString(";"))
            
            tester.exists().shouldBeTrue()

            var indices = getIndices(tester)
            indices.size shouldBeEqualTo 3

            val dropStatements = indices.map { it.dropStatement().first() }
            expect(Unit) { execInBatch(dropStatements) }

            indices = getIndices(tester)
            indices.size shouldBeEqualTo 0
        }
    }

    private fun Transaction.getIndices(table: Table): List<Index> {
        db.dialect.resetCaches()
        return currentDialect.existingIndices(table)[table].orEmpty()
    }
}
