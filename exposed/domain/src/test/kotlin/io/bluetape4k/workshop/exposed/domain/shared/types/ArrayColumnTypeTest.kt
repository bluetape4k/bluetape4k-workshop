package io.bluetape4k.workshop.exposed.domain.shared.types

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.BinaryColumnType
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.allFrom
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.anyFrom
import org.jetbrains.exposed.sql.arrayParam
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.get
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.slice
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ArrayColumnTypeTest: AbstractExposedTest() {

    companion object: KLogging()

    private val arrayTypeSupportedDB = TestDB.ALL_POSTGRES_LIKE + TestDB.H2

    object ArrayTestTable: IntIdTable("array_test_table") {
        val numbers: Column<List<Int>> = array<Int>("numbers").default(listOf(5))

        // val strings: Column<List<String?>> = array<String?>("strings", TextColumnType()).default(emptyList())
        val strings = array<String?>("strings", TextColumnType()).default(emptyList())
        val doubles: Column<List<Double>?> = array<Double>("doubles").nullable()
        val byteArray: Column<List<ByteArray>?> = array("byte_array", BinaryColumnType(32)).nullable()
    }

    /**
     * H2:
     * ```sql
     * CREATE TABLE IF NOT EXISTS ARRAY_TEST_TABLE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      NUMBERS INT ARRAY DEFAULT ARRAY [5] NOT NULL,
     *      STRINGS TEXT ARRAY DEFAULT ARRAY [] NOT NULL,
     *      DOUBLES DOUBLE PRECISION ARRAY NULL,
     *      BYTE_ARRAY VARBINARY(32) ARRAY NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop array columns`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in arrayTypeSupportedDB }

        withDb(testDB) {
            try {
                SchemaUtils.create(ArrayTestTable)
                ArrayTestTable.exists().shouldBeTrue()
            } finally {
                SchemaUtils.drop(ArrayTestTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create missing columns with array defaults`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(ArrayTestTable)
                SchemaUtils.statementsRequiredToActualizeScheme(ArrayTestTable).shouldBeEmpty()
            } finally {
                SchemaUtils.drop(ArrayTestTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array column insert and select`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            // FIXME: Exposed Log `addLogger(StdOutSqlLogger)` 를 사용하면 null 이 들어가면 예외가 발생한다.
            //        logger 에서도 제외해줘야 합니다. (<logger name="Exposed" level="DEBUG"/> 를 제거))
            val stringInput = listOf<String?>("hi", "hey", "hello")
            val doubleInput = listOf(1.0, 2.0, 3.0)

            val id1 = ArrayTestTable.insertAndGetId {
                it[numbers] = numInput
                it[strings] = stringInput
                it[doubles] = doubleInput
            }

            val result1 = ArrayTestTable.selectAll().where { ArrayTestTable.id eq id1 }.single()
            result1[ArrayTestTable.numbers] shouldBeEqualTo numInput
            result1[ArrayTestTable.strings] shouldBeEqualTo stringInput
            result1[ArrayTestTable.doubles] shouldBeEqualTo doubleInput

            val id2 = ArrayTestTable.insertAndGetId {
                it[numbers] = emptyList()
                it[strings] = emptyList()
                it[doubles] = emptyList()
            }

            val result2 = ArrayTestTable.selectAll().where { ArrayTestTable.id eq id2 }.single()
            result2[ArrayTestTable.numbers].shouldBeEmpty()
            result2[ArrayTestTable.strings].shouldBeEmpty()
            result2[ArrayTestTable.doubles]?.shouldBeEmpty()

            // FIXME: Exposed Log `addLogger(StdOutSqlLogger)` 를 사용하면 null 이 들어가면 예외가 발생한다.
            //        logger 에서도 제외해줘야 합니다. (<logger name="Exposed" level="DEBUG"/> 를 제거))
            val id3 = ArrayTestTable.insertAndGetId {
                it[strings] = listOf(null, null, null, "null")
                it[doubles] = null
            }

            val result3 = ArrayTestTable.selectAll().where { ArrayTestTable.id eq id3 }.single()
            result3[ArrayTestTable.numbers].single() shouldBeEqualTo 5
            result3[ArrayTestTable.strings].take(3).all { it == null }.shouldBeTrue()
            result3[ArrayTestTable.strings].last() shouldBeEqualTo "null"
            result3[ArrayTestTable.doubles].shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array max size`(testDB: TestDB) {
        val maxArraySize = 5
        val sizedTester = object: Table("sized_test_table") {
            val numbers: Column<List<Int>> = array("numbers", IntegerColumnType(), maxArraySize).default(emptyList())
        }

        withArrayTestTable(testDB, sizedTester) {
            val tooLongList = List(maxArraySize + 1) { it + 1 }
            if (currentDialect is PostgreSQLDialect) {
                // PostgreSQL ignores any max cardinality value
                sizedTester.insert {
                    it[numbers] = tooLongList
                }

                val result = sizedTester.selectAll().single()[sizedTester.numbers]
                result shouldBeEqualTo tooLongList
            } else {
                expectException<ExposedSQLException> {
                    sizedTester.insert {
                        it[numbers] = tooLongList
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select using array get`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            ArrayTestTable.insert {
                it[numbers] = numInput
                it[strings] = listOf<String?>("hi", "hello")
                it[doubles] = null
            }

            /**
             * SQL array indexes are one-based
             *
             * ```sql
             * SELECT ARRAY_TEST_TABLE.NUMBERS[2] FROM ARRAY_TEST_TABLE
             * ```
             */
            val secondNumber = ArrayTestTable.numbers[2]
            val result1 = ArrayTestTable.select(secondNumber).single()[secondNumber]
            result1 shouldBeEqualTo numInput[1]

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID,
             *        ARRAY_TEST_TABLE.NUMBERS,
             *        ARRAY_TEST_TABLE.STRINGS,
             *        ARRAY_TEST_TABLE.DOUBLES,
             *        ARRAY_TEST_TABLE.BYTE_ARRAY
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.STRINGS[2] = 'hello'
             * ```
             */
            val result2 = ArrayTestTable.selectAll().where { ArrayTestTable.strings[2] eq "hello" }.single()
            result2[ArrayTestTable.strings] shouldBeEqualTo listOf("hi", "hello")
            result2[ArrayTestTable.doubles].shouldBeNull()

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID,
             *        ARRAY_TEST_TABLE.NUMBERS,
             *        ARRAY_TEST_TABLE.STRINGS,
             *        ARRAY_TEST_TABLE.DOUBLES,
             *        ARRAY_TEST_TABLE.BYTE_ARRAY
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.NUMBERS[1] >= ARRAY_TEST_TABLE.NUMBERS[3]
             * ```
             */
            val result3 = ArrayTestTable.selectAll()
                .where {
                    ArrayTestTable.numbers[1] greaterEq ArrayTestTable.numbers[3]
                }
                .toList()
            result3.shouldBeEmpty()

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.DOUBLES[2] FROM ARRAY_TEST_TABLE
             * ```
             */
            val nullArray = ArrayTestTable.doubles[2]
            val result4 = ArrayTestTable.select(nullArray).single()[nullArray]
            result4.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select using array slice`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            ArrayTestTable.insert {
                it[numbers] = numInput
                it[strings] = listOf<String?>(null, null, null, "hello")
                it[doubles] = null
            }

            val lastTwoNumbers = ArrayTestTable.numbers.slice(2, 3)  // numbers[2:3]
            val result1 = ArrayTestTable.select(lastTwoNumbers).single()[lastTwoNumbers]
            result1 shouldBeEqualTo numInput.takeLast(2)

            val firstThreeStrings = ArrayTestTable.strings.slice(upper = 3) // strings[:3]
            val result2 = ArrayTestTable.select(firstThreeStrings).single()[firstThreeStrings]
            if (currentDialect is H2Dialect) {
                result2.shouldBeNull()
            } else {
                result2.filterNotNull().shouldBeEmpty()
            }

            val allNumbers = ArrayTestTable.numbers.slice()  // numbers[:]
            val result3 = ArrayTestTable.select(allNumbers).single()[allNumbers]
            if (currentDialect is H2Dialect) {
                result3.shouldBeNull()
            } else {
                result3 shouldBeEqualTo numInput
            }

            val nullArray = ArrayTestTable.doubles.slice(1, 3)
            val result4 = ArrayTestTable.select(nullArray).single()[nullArray]
            result4.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array literal and array param`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            val doublesInput = List(5) { (it + 1).toDouble() }
            val id1 = ArrayTestTable.insertAndGetId {
                it[numbers] = numInput
                it[strings] = listOf("", "", "", "hello") // listOf<String?>(null, null, null, "hello")
                it[doubles] = doublesInput
            }

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID,
             *        ARRAY_TEST_TABLE.NUMBERS,
             *        ARRAY_TEST_TABLE.STRINGS,
             *        ARRAY_TEST_TABLE.DOUBLES,
             *        ARRAY_TEST_TABLE.BYTE_ARRAY
             *   FROM ARRAY_TEST_TABLE
             *  WHERE (ARRAY_TEST_TABLE.NUMBERS = ARRAY [1,2,3])
             *    AND (ARRAY_TEST_TABLE.STRINGS <> ARRAY [])
             * ```
             */
            val result1 = ArrayTestTable.selectAll()
                .where {
                    (ArrayTestTable.numbers eq numInput) and (ArrayTestTable.strings neq emptyList())
                }
            result1.single()[ArrayTestTable.id] shouldBeEqualTo id1

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.DOUBLES = ARRAY [1.0,2.0,3.0,4.0,5.0]
             * ```
             */
            val result2 = ArrayTestTable.select(ArrayTestTable.id)
                .where {
                    ArrayTestTable.doubles eq arrayParam(doublesInput)
                }
            result2.single()[ArrayTestTable.id] shouldBeEqualTo id1

            if (currentDialectTest is PostgreSQLDialect) {
                /**
                 * ```sql
                 * SELECT array_test_table.id
                 *   FROM array_test_table
                 *  WHERE array_test_table.strings[4:] = ARRAY['hello']
                 * ```
                 */
                val lastStrings = ArrayTestTable.strings.slice(lower = 4) // strings[4:]
                val result3 = ArrayTestTable.select(ArrayTestTable.id)
                    .where {
                        lastStrings eq arrayParam(listOf("hello"))
                    }
                result3.single()[ArrayTestTable.id] shouldBeEqualTo id1
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array column update`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val id1 = ArrayTestTable.insertAndGetId {
                it[doubles] = null
            }
            ArrayTestTable.selectAll().single()[ArrayTestTable.doubles].shouldBeNull()

            val updatedDoubles = listOf(9.0)
            ArrayTestTable.update({ ArrayTestTable.id eq id1 }) {
                it[doubles] = updatedDoubles
            }
            ArrayTestTable.selectAll().single()[ArrayTestTable.doubles] shouldBeEqualTo updatedDoubles
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array column upsert`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numbers = listOf(1, 2, 3)
            val strings = listOf("A", "B")

            val id1 = ArrayTestTable.insertAndGetId {
                it[ArrayTestTable.numbers] = numbers
                it[ArrayTestTable.strings] = strings
            }

            val result = ArrayTestTable.selectAll().single()
            result[ArrayTestTable.id] shouldBeEqualTo id1
            result[ArrayTestTable.numbers] shouldBeEqualTo numbers
            result[ArrayTestTable.strings] shouldBeEqualTo strings

            /**
             * H2:
             * ```sql
             * MERGE INTO ARRAY_TEST_TABLE T
             * USING (VALUES (1, ARRAY [1,2,3], ARRAY ['A','B'])) S(ID, NUMBERS, STRINGS) ON (T.ID=S.ID)
             *  WHEN MATCHED THEN UPDATE SET T.STRINGS=ARRAY ['C','D','E']
             *  WHEN NOT MATCHED THEN INSERT (NUMBERS, STRINGS) VALUES(S.NUMBERS, S.STRINGS)
             * ```
             */
            val updatedString = listOf("C", "D", "E")
            ArrayTestTable.upsert(
                onUpdate = { it[ArrayTestTable.strings] = updatedString },
            ) {
                it[id] = id1
                it[ArrayTestTable.numbers] = numbers
                it[ArrayTestTable.strings] = strings
            }

            val result2 = ArrayTestTable.selectAll().single()
            result2[ArrayTestTable.id] shouldBeEqualTo id1
            result2[ArrayTestTable.numbers] shouldBeEqualTo numbers
            result2[ArrayTestTable.strings] shouldBeEqualTo updatedString
        }
    }

    class ArrayTestDao(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<ArrayTestDao>(ArrayTestTable)

        var numbers by ArrayTestTable.numbers
        var strings by ArrayTestTable.strings
        var doubles by ArrayTestTable.doubles
        var byteArray by ArrayTestTable.byteArray
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array column with DAO functions`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            val entity1 = ArrayTestDao.new {
                numbers = numInput
                doubles = null
            }

            flushCache()

            entity1.numbers shouldBeEqualTo numInput
            entity1.strings.shouldBeEmpty()

            val doublesInput = listOf(9.0)
            entity1.doubles = doublesInput

            ArrayTestDao.findById(entity1.id)?.doubles shouldBeEqualTo doublesInput
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `array column with all any ops`(testDB: TestDB) {
        withArrayTestTable(testDB) {
            val numInput = listOf(1, 2, 3)
            val id1 = ArrayTestTable.insertAndGetId {
                it[numbers] = numInput
                it[doubles] = null
            }

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.ID = ANY (ARRAY_TEST_TABLE.NUMBERS)
             * ```
             */
            val result1 = ArrayTestTable.select(ArrayTestTable.id)
                .where {
                    ArrayTestTable.id eq anyFrom(ArrayTestTable.numbers)
                }
            result1.single()[ArrayTestTable.id] shouldBeEqualTo id1

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.ID = ANY (ARRAY_SLICE(ARRAY_TEST_TABLE.NUMBERS,2,3))
             * ```
             */
            val result2 = ArrayTestTable.select(ArrayTestTable.id)
                .where {
                    ArrayTestTable.id eq anyFrom(ArrayTestTable.numbers.slice(2, 3))
                }
            result2.toList().shouldBeEmpty()

            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.ID <= ALL (ARRAY_TEST_TABLE.NUMBERS)
             * ```
             */
            val result3 = ArrayTestTable.select(ArrayTestTable.id)
                .where {
                    ArrayTestTable.id lessEq allFrom(ArrayTestTable.numbers)
                }
            result3.single()[ArrayTestTable.id] shouldBeEqualTo id1
            /**
             * ```sql
             * SELECT ARRAY_TEST_TABLE.ID
             *   FROM ARRAY_TEST_TABLE
             *  WHERE ARRAY_TEST_TABLE.ID > ALL (ARRAY_TEST_TABLE.NUMBERS)
             * ```
             */
            val result4 = ArrayTestTable.select(ArrayTestTable.id)
                .where {
                    ArrayTestTable.id greater allFrom(ArrayTestTable.numbers)
                }
            result4.toList().shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert array of byte arrays`(testDB: TestDB) {
        // POSTGRESQLNG is excluded because the problem may be on their side.
        // Related issue: https://github.com/impossibl/pgjdbc-ng/issues/600
        // Recheck on our side when the issue is resolved.
        withArrayTestTable(testDB) {
            val testByteArrayList = listOf(byteArrayOf(0), byteArrayOf(1, 2, 3))

            ArrayTestTable.insert {
                it[byteArray] = testByteArrayList
            }

            val result = ArrayTestTable.selectAll().single()[ArrayTestTable.byteArray]
            result.shouldNotBeNull()
            result[0][0] shouldBeEqualTo testByteArrayList[0][0]
            result[1].toUByteString() shouldBeEqualTo testByteArrayList[1].toUByteString()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `aliased array`(testDB: TestDB) {
        val tester = object: IntIdTable("test_aliased_array") {
            val value = array<Int>("value")
        }

        val inputInts = listOf(1, 2, 3)

        withArrayTestTable(testDB, tester) {
            /**
             * ```sql
             * INSERT INTO TEST_ALIASED_ARRAY ("value") VALUES (ARRAY [1,2,3])
             * ```
             */
            tester.insert {
                it[tester.value] = inputInts
            }

            /**
             * ```sql
             * SELECT TEST_ALIASED_ARRAY."value" aliased_value FROM TEST_ALIASED_ARRAY
             * ```
             */
            val alias = tester.value.alias("aliased_value")
            tester.select(alias).first()[alias] shouldBeEqualTo inputInts
        }
    }

    private fun withArrayTestTable(
        testDB: TestDB,
        vararg tables: Table = arrayOf(ArrayTestTable),
        statement: Transaction.(TestDB) -> Unit,
    ) {
        Assumptions.assumeTrue { testDB in arrayTypeSupportedDB }

        withTables(testDB, *tables) {
            statement(testDB)
        }
    }
}

private fun ByteArray.toUByteString(): String = joinToString { it.toUByte().toString() }
