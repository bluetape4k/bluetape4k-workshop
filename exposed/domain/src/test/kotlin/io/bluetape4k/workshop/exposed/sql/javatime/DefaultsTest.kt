package io.bluetape4k.workshop.exposed.sql.javatime

import MigrationUtils
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.constraintNamePart
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.insertAndWait
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.CurrentDate
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.dateLiteral
import org.jetbrains.exposed.sql.javatime.dateTimeLiteral
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.duration
import org.jetbrains.exposed.sql.javatime.durationLiteral
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timeLiteral
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.timestampLiteral
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZoneLiteral
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchDataInconsistentException
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.H2Dialect.H2CompatibilityMode.Oracle
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.jetbrains.exposed.sql.vendors.h2Mode
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private val dbTimestampNow: CustomFunction<OffsetDateTime>
    get() = object: CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType()) {}

class DefaultsTest: AbstractExposedTest() {

    companion object: KLogging()

    object TableWithDBDefault: IntIdTable("t_db_default") {
        val cIndex = AtomicInteger(0)

        val field = varchar("field", 100)
        val t1 = datetime("t1").defaultExpression(CurrentDateTime)
        val clientDefault = integer("clientDefault").clientDefault { cIndex.getAndIncrement() }

        init {
            cIndex.set(0)
        }
    }

    class DBDefault(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<DBDefault>(TableWithDBDefault)

        var field by TableWithDBDefault.field
        var t1 by TableWithDBDefault.t1
        var clientDefault by TableWithDBDefault.clientDefault

        override fun equals(other: Any?): Boolean = other is DBDefault && id._value == other.id._value
        override fun hashCode(): Int = id.value.hashCode()
        override fun toString(): String = "DBDefault(id=$id, field=$field, t1=$t1, clientDefault=$clientDefault)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `can use client default on nullable column`(testDB: TestDB) {
        val defaultValue: Int? = null
        val table = object: IntIdTable("tester") {
            val clientDefault = integer("clientDefault").nullable().clientDefault { defaultValue }
        }

        val returnedDefault = table.clientDefault.defaultValueFun?.invoke()

        table.clientDefault.columnType.nullable.shouldBeTrue()
        table.clientDefault.defaultValueFun.shouldNotBeNull()
        returnedDefault shouldBeEqualTo defaultValue
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `can set nullable column to use client default`(testDB: TestDB) {
        val defaultValue: Int = 123
        val table = object: IntIdTable("tester") {
            val clientDefault = integer("clientDefault").nullable().clientDefault { defaultValue }
        }

        val returnedDefault = table.clientDefault.defaultValueFun?.invoke()

        table.clientDefault.columnType.nullable.shouldBeTrue()
        table.clientDefault.defaultValueFun.shouldNotBeNull()
        returnedDefault shouldBeEqualTo defaultValue
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults with explicit 01`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            val created = listOf(
                DBDefault.new { field = "1" },
                DBDefault.new {
                    field = "2"
                    t1 = LocalDateTime.now().minusDays(5)
                }
            )
            commit()
            created.forEach {
                DBDefault.removeFromCache(it)
            }

            val entities = DBDefault.all().toList()
            entities shouldBeEqualTo created
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults with explicit 02`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            val created = listOf(
                DBDefault.new {
                    field = "2"
                    t1 = LocalDateTime.now().minusDays(5)
                },
                DBDefault.new { field = "1" }
            )
            flushCache()
            created.forEach {
                DBDefault.removeFromCache(it)
            }

            val entities = DBDefault.all().toList()
            entities shouldBeEqualTo created

        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults invoked only once per entity`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            TableWithDBDefault.cIndex.set(0)
            val db1 = DBDefault.new { field = "1" }
            val db2 = DBDefault.new { field = "2" }
            flushCache()

            db1.clientDefault shouldBeEqualTo 0
            db2.clientDefault shouldBeEqualTo 1
            TableWithDBDefault.cIndex.get() shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults can be overriden`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            TableWithDBDefault.cIndex.set(0)

            val db1 = DBDefault.new { field = "1" }
            val db2 = DBDefault.new { field = "2" }
            db1.clientDefault = 12345
            flushCache()
            db1.clientDefault shouldBeEqualTo 12345
            db2.clientDefault shouldBeEqualTo 1
            TableWithDBDefault.cIndex.get() shouldBeEqualTo 2

            flushCache()
            db1.clientDefault shouldBeEqualTo 12345
        }
    }

    private val initBatch = listOf<(BatchInsertStatement) -> Unit>(
        {
            it[TableWithDBDefault.field] = "1"
        },
        {
            it[TableWithDBDefault.field] = "2"
            it[TableWithDBDefault.t1] = LocalDateTime.now()
        }
    )

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `raw batch insert fails 01`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            expectException<BatchDataInconsistentException> {
                BatchInsertStatement(TableWithDBDefault).run {
                    initBatch.forEach {
                        addBatch()
                        it(this)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert not fails 01`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            TableWithDBDefault.batchInsert(initBatch) {
                it(this)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batch insert fails 01`(testDB: TestDB) {
        withTables(testDB, TableWithDBDefault) {
            expectException<BatchDataInconsistentException> {
                TableWithDBDefault.batchInsert(initBatch) {
                    // t1 은 Database Default 이므로 값을 넣지 않는다.
                    this[TableWithDBDefault.t1] = LocalDateTime.now()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `defaults 01`(testDB: TestDB) {
        val currentDT = CurrentDateTime
        val nowExpression = object: Expression<LocalDateTime>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                +when (val dialect = currentDialectTest) {
                    is OracleDialect -> "SYSDATE"
                    is SQLServerDialect -> "GETDATE()"
                    is MysqlDialect -> if (dialect.isFractionDateTimeSupported()) "NOW(6)" else "NOW()"
                    is SQLiteDialect -> "CURRENT_TIMESTAMP"
                    else -> "NOW()"
                }
            }
        }
        val dtConstValue = LocalDate.of(2010, 1, 1)
        val dLiteral = dateLiteral(dtConstValue)
        val dtLiteral = dateTimeLiteral(dtConstValue.atStartOfDay())
        val tsConstValue = dtConstValue.atStartOfDay(ZoneOffset.UTC).plusSeconds(42).toInstant()
        val tsLiteral = timestampLiteral(tsConstValue)
        val durConstValue = Duration.between(Instant.EPOCH, tsConstValue)
        val durLiteral = durationLiteral(durConstValue)
        val tmConstValue = LocalTime.of(12, 0)
        val tLiteral = timeLiteral(tmConstValue)

        val testTable = object: IntIdTable("t") {
            val s = varchar("s", 100).default("test")
            val sn = varchar("sn", 100).default("testNullable").nullable()
            val l = long("l").default(42)
            val c = char("c").default('X')
            val t1 = datetime("t1").defaultExpression(currentDT)
            val t2 = datetime("t2").defaultExpression(nowExpression)
            val t3 = datetime("t3").defaultExpression(dtLiteral)
            val t4 = date("t4").default(dtConstValue)
            val t5 = timestamp("t5").default(tsConstValue)
            val t6 = timestamp("t6").defaultExpression(tsLiteral)
            val t7 = duration("t7").default(durConstValue)
            val t8 = duration("t8").defaultExpression(durLiteral)
            val t9 = time("t9").default(tmConstValue)
            val t10 = time("t10").defaultExpression(tLiteral)
        }

        fun Expression<*>.itOrNull() = when {
            currentDialectTest.isAllowedAsColumnDefault(this) ->
                "DEFAULT ${currentDialectTest.dataTypeProvider.processForDefaultValue(this)} NOT NULL"
            else -> "NULL"
        }

        withTables(testDB, testTable) {
            val dtType = currentDialectTest.dataTypeProvider.dateTimeType()
            val dType = currentDialectTest.dataTypeProvider.dateType()
            val longType = currentDialectTest.dataTypeProvider.longType()
            val timeType = currentDialectTest.dataTypeProvider.timeType()
            val varcharType = currentDialectTest.dataTypeProvider.varcharType(100)
            val q = db.identifierManager.quoteString
            val baseExpression = "CREATE TABLE " + addIfNotExistsIfSupported() +
                    "${"t".inProperCase()} (" +
                    "${"id".inProperCase()} ${currentDialectTest.dataTypeProvider.integerAutoincType()}${
                        " PRIMARY KEY"
                    }, " +
                    "${"s".inProperCase()} $varcharType${testTable.s.constraintNamePart()} DEFAULT 'test' NOT NULL, " +
                    "${"sn".inProperCase()} $varcharType${testTable.sn.constraintNamePart()} DEFAULT 'testNullable' NULL, " +
                    "${"l".inProperCase()} ${currentDialectTest.dataTypeProvider.longType()}${testTable.l.constraintNamePart()} DEFAULT 42 NOT NULL, " +
                    "$q${"c".inProperCase()}$q CHAR${testTable.c.constraintNamePart()} DEFAULT 'X' NOT NULL, " +
                    "${"t1".inProperCase()} $dtType${testTable.t1.constraintNamePart()} ${currentDT.itOrNull()}, " +
                    "${"t2".inProperCase()} $dtType${testTable.t2.constraintNamePart()} ${nowExpression.itOrNull()}, " +
                    "${"t3".inProperCase()} $dtType${testTable.t3.constraintNamePart()} ${dtLiteral.itOrNull()}, " +
                    "${"t4".inProperCase()} $dType${testTable.t4.constraintNamePart()} ${dLiteral.itOrNull()}, " +
                    "${"t5".inProperCase()} $dtType${testTable.t5.constraintNamePart()} ${tsLiteral.itOrNull()}, " +
                    "${"t6".inProperCase()} $dtType${testTable.t6.constraintNamePart()} ${tsLiteral.itOrNull()}, " +
                    "${"t7".inProperCase()} $longType${testTable.t7.constraintNamePart()} ${durLiteral.itOrNull()}, " +
                    "${"t8".inProperCase()} $longType${testTable.t8.constraintNamePart()} ${durLiteral.itOrNull()}, " +
                    "${"t9".inProperCase()} $timeType${testTable.t9.constraintNamePart()} ${tLiteral.itOrNull()}, " +
                    "${"t10".inProperCase()} $timeType${testTable.t10.constraintNamePart()} ${tLiteral.itOrNull()}" +
//                    when (testDB) {
//                        TestDB.SQLITE, TestDB.ORACLE ->
//                            ", CONSTRAINT chk_t_signed_integer_id CHECK (${"id".inProperCase()} BETWEEN ${Int.MIN_VALUE} AND ${Int.MAX_VALUE})"
//                        else -> ""
//                    } +
                    ")"

            val expected =
                if (currentDialectTest is OracleDialect || currentDialectTest.h2Mode == Oracle) {
                    arrayListOf(
                        "CREATE SEQUENCE t_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807",
                        baseExpression
                    )
                } else {
                    arrayListOf(baseExpression)
                }

            testTable.ddl shouldBeEqualTo expected

            val id1 = testTable.insertAndGetId { }

            val row1 = testTable.selectAll().where { testTable.id eq id1 }.single()
            row1[testTable.s] shouldBeEqualTo "test"
            row1[testTable.sn] shouldBeEqualTo "testNullable"
            row1[testTable.l] shouldBeEqualTo 42
            row1[testTable.c] shouldBeEqualTo 'X'
            row1[testTable.t3] shouldTemporalEqualTo dtConstValue.atStartOfDay()
            row1[testTable.t4] shouldTemporalEqualTo dtConstValue
            row1[testTable.t5] shouldTemporalEqualTo tsConstValue
            row1[testTable.t6] shouldTemporalEqualTo tsConstValue
            row1[testTable.t7] shouldBeEqualTo durConstValue
            row1[testTable.t8] shouldBeEqualTo durConstValue
            row1[testTable.t9] shouldBeEqualTo tmConstValue
            row1[testTable.t10] shouldBeEqualTo tmConstValue
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default expressions 01`(testDB: TestDB) {
        fun abs(value: Int) = object: ExpressionWithColumnType<Int>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("ABS($value)") }
            override val columnType: IColumnType<Int> = IntegerColumnType()
        }

        val tester = object: IntIdTable("tester") {
            val name = text("name")
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentDateTime)
            val defaultDate = date("defaultDate").defaultExpression(CurrentDate)
            val defaultInt = integer("defaultInt").defaultExpression(abs(-100))
        }

        // MySql 5 is excluded because it does not support `CURRENT_DATE()` as a default value
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 }
        withTables(testDB, tester) {
            val id = tester.insertAndGetId {
                it[tester.name] = "bar"
            }
            val result = tester.selectAll().where { tester.id eq id }.single()

            result[tester.defaultDateTime].toLocalDate() shouldBeEqualTo today
            result[tester.defaultDate] shouldBeEqualTo today
            result[tester.defaultInt] shouldBeEqualTo 100
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `default expressions 02`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val name = text("name")
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentDateTime)
        }

        val nonDefaultDate = LocalDate.of(2000, 1, 1).atStartOfDay()

        withTables(testDB, tester) {
            val id = tester.insertAndGetId {
                it[tester.name] = "bar"
                it[tester.defaultDateTime] = nonDefaultDate
            }

            val result = tester.selectAll().where { tester.id eq id }.single()
            result[tester.name] shouldBeEqualTo "bar"
            result[tester.defaultDateTime] shouldBeEqualTo nonDefaultDate

            tester.update({ tester.id eq id }) {
                it[tester.name] = "baz"
            }

            val result2 = tester.selectAll().where { tester.id eq id }.single()
            result2[tester.name] shouldBeEqualTo "baz"
            result2[tester.defaultDateTime] shouldBeEqualTo nonDefaultDate
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `between function`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val dt = datetime("datetime")
        }

        withTables(testDB, tester) {
            val dt2020 = LocalDateTime.of(2020, 1, 1, 1, 1)
            tester.insert { it[dt] = LocalDateTime.of(2019, 1, 1, 1, 1) }
            tester.insert { it[dt] = dt2020 }
            tester.insert { it[dt] = LocalDateTime.of(2021, 1, 1, 1, 1) }

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM TESTER
             *  WHERE TESTER.DATETIME BETWEEN '2019-12-25T01:01:00' AND '2020-01-08T01:01:00'
             * ```
             */
            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM TESTER
             *  WHERE TESTER.DATETIME BETWEEN '2019-12-25T01:01:00' AND '2020-01-08T01:01:00'
             * ```
             */
            val count = tester.selectAll()
                .where {
                    tester.dt.between(dt2020.minusWeeks(1), dt2020.plusWeeks(1))
                }
                .count()
            count shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Consistent Scheme With Function As Default Expression`(testDB: TestDB) {
        val tester = object: IntIdTable("tester") {
            val name = text("name")
            val defaultDate = date("defaultDate").defaultExpression(CurrentDate)
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentDateTime)
            val defaultTimestamp = timestamp("defaultTimestamp").defaultExpression(CurrentTimestamp)
        }
        withTables(testDB, tester) {
            val actual = MigrationUtils.statementsRequiredForDatabaseMigration(tester)
            if (testDB !in TestDB.ALL_POSTGRES) {
                actual.shouldBeEmpty()
            } else {
                actual shouldHaveSize 5
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Timestamp with TimeZone Default`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 } // TestDB.ALL_MARIADB + TestDB.MYSQL_V5
        // UTC time zone
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.UTC))
        ZoneId.systemDefault().id shouldBeEqualTo "UTC"

        val nowWithTimeZone = OffsetDateTime.parse("2024-07-18T13:19:44.000+00:00")
        val timestampWithTimeZoneLiteral = timestampWithTimeZoneLiteral(nowWithTimeZone)

        val testTable = object: IntIdTable("t") {
            val t1 = timestampWithTimeZone("t1").default(nowWithTimeZone)
            val t2 = timestampWithTimeZone("t2").defaultExpression(timestampWithTimeZoneLiteral)
            val t3 = timestampWithTimeZone("t3").defaultExpression(CurrentTimestampWithTimeZone)
        }

        fun Expression<*>.itOrNull() = when {
            currentDialectTest.isAllowedAsColumnDefault(this) ->
                "DEFAULT ${currentDialectTest.dataTypeProvider.processForDefaultValue(this)} NOT NULL"
            else -> "NULL"
        }

        withTables(testDB, testTable) { testDb ->
            val timestampWithTimeZoneType = currentDialectTest.dataTypeProvider.timestampWithTimeZoneType()

            val baseExpression = "CREATE TABLE " + addIfNotExistsIfSupported() +
                    "${"t".inProperCase()} (" +
                    "${"id".inProperCase()} ${currentDialectTest.dataTypeProvider.integerAutoincType()}${
                        " PRIMARY KEY"
                        // testDb.takeIf { it != TestDB.SQLITE }?.let { " PRIMARY KEY" } ?: ""
                    }, " +
                    "${"t1".inProperCase()} $timestampWithTimeZoneType${testTable.t1.constraintNamePart()} ${timestampWithTimeZoneLiteral.itOrNull()}, " +
                    "${"t2".inProperCase()} $timestampWithTimeZoneType${testTable.t2.constraintNamePart()} ${timestampWithTimeZoneLiteral.itOrNull()}, " +
                    "${"t3".inProperCase()} $timestampWithTimeZoneType${testTable.t3.constraintNamePart()} ${CurrentTimestampWithTimeZone.itOrNull()}" +
//                    when (testDb) {
//                        TestDB.SQLITE, TestDB.ORACLE ->
//                            ", CONSTRAINT chk_t_signed_integer_id CHECK (${"id".inProperCase()} BETWEEN ${Int.MIN_VALUE} AND ${Int.MAX_VALUE})"
//                        else -> ""
//                    } +
                    ")"

            val expected = if (currentDialectTest is OracleDialect ||
                currentDialectTest.h2Mode == Oracle
            ) {
                arrayListOf(
                    "CREATE SEQUENCE t_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807",
                    baseExpression
                )
            } else {
                arrayListOf(baseExpression)
            }

            testTable.ddl shouldBeEqualTo expected

            val id1 = testTable.insertAndGetId { }

            val row1 = testTable.selectAll().where { testTable.id eq id1 }.single()
            row1[testTable.t1] shouldBeEqualTo nowWithTimeZone
            row1[testTable.t2] shouldBeEqualTo nowWithTimeZone
            val dbDefault = row1[testTable.t3]
            dbDefault.offset shouldBeEqualTo nowWithTimeZone.offset
            dbDefault.toLocalDateTime() shouldBeGreaterOrEqualTo nowWithTimeZone.toLocalDateTime()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Default CurrentDateTime`(testDB: TestDB) {
        val testDate = object: IntIdTable("TestDate") {
            val time = datetime("time").defaultExpression(CurrentDateTime)
        }

        fun LocalDateTime.millis(): Long = this.toEpochSecond(ZoneOffset.UTC) * 1000

        withTables(testDB, testDate) {
            val duration = 2000L

            repeat(2) {
                testDate.insertAndWait(duration)
            }

            Thread.sleep(duration)

            repeat(2) {
                testDate.insertAndWait(duration)
            }

            val sortedEntries: List<LocalDateTime> = testDate.selectAll().map { it[testDate.time] }.sorted()

            (sortedEntries[1].millis() - sortedEntries[0].millis()) shouldBeGreaterOrEqualTo 2000
            (sortedEntries[2].millis() - sortedEntries[0].millis()) shouldBeGreaterOrEqualTo 6000
            (sortedEntries[3].millis() - sortedEntries[0].millis()) shouldBeGreaterOrEqualTo 8000
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDateDefaultDoesNotTriggerAlterStatement(testDB: TestDB) {
        val date = LocalDate.of(2024, 2, 1)

        val tester = object: Table("tester") {
            val dateWithDefault = date("dateWithDefault").default(date)
        }

        // SQLite does not support ALTER TABLE on a column that has a default value
        withTables(testDB, tester) {
            val statements = SchemaUtils.addMissingColumnsStatements(tester)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimestampDefaultDoesNotTriggerAlterStatement(testDB: TestDB) {
        val instant = Instant.parse("2023-05-04T05:04:00.700Z") // In UTC

        val tester = object: Table("tester") {
            val timestampWithDefault = timestamp("timestampWithDefault").default(instant)
            val timestampWithDefaultExpression =
                timestamp("timestampWithDefaultExpression").defaultExpression(CurrentTimestamp)
        }

        // SQLite does not support ALTER TABLE on a column that has a default value
        withTables(testDB, tester) {
            val statements = SchemaUtils.addMissingColumnsStatements(tester)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDatetimeDefaultDoesNotTriggerAlterStatement(testDB: TestDB) {
        val datetime = LocalDateTime.parse("2023-05-04T05:04:07.000")

        val tester = object: Table("tester") {
            val datetimeWithDefault = datetime("datetimeWithDefault").default(datetime)
            val datetimeWithDefaultExpression =
                datetime("datetimeWithDefaultExpression").defaultExpression(CurrentDateTime)
        }

        // SQLite does not support ALTER TABLE on a column that has a default value
        withTables(testDB, tester) {
            val statements = SchemaUtils.addMissingColumnsStatements(tester)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimeDefaultDoesNotTriggerAlterStatement(testDB: TestDB) {
        val time = LocalDateTime.now(ZoneId.of("Japan")).toLocalTime()

        val tester = object: Table("tester") {
            val timeWithDefault = time("timeWithDefault").default(time)
        }

        // SQLite does not support ALTER TABLE on a column that has a default value
        withTables(testDB, tester) {
            val statements = SchemaUtils.addMissingColumnsStatements(tester)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testTimestampWithTimeZoneDefaultDoesNotTriggerAlterStatement(testDB: TestDB) {
        val offsetDateTime = OffsetDateTime.parse("2024-02-08T20:48:04.700+09:00")

        val tester = object: Table("tester") {
            val timestampWithTimeZoneWithDefault =
                timestampWithTimeZone("timestampWithTimeZoneWithDefault").default(offsetDateTime)
        }

        // SQLite does not support ALTER TABLE on a column that has a default value
        // MariaDB does not support TIMESTAMP WITH TIME ZONE column type
        val unsupportedDatabases = setOf(TestDB.MYSQL_V5) // TestDB.ALL_MARIADB + listOf(TestDB.SQLITE, TestDB.MYSQL_V5)
        Assumptions.assumeTrue { testDB !in unsupportedDatabases }
        withTables(testDB, tester) {
            val statements = SchemaUtils.addMissingColumnsStatements(tester)
            statements.shouldBeEmpty()
        }
    }

    object DefaultTimestampTable: IntIdTable("test_table") {
        val timestamp: Column<OffsetDateTime> =
            timestampWithTimeZone("timestamp").defaultExpression(dbTimestampNow)
    }

    class DefaultTimestampEntity(id: EntityID<Int>): Entity<Int>(id) {
        companion object: EntityClass<Int, DefaultTimestampEntity>(DefaultTimestampTable)

        var timestamp: OffsetDateTime by DefaultTimestampTable.timestamp
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCustomDefaultTimestampFunctionWithEntity(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in (TestDB.ALL_POSTGRES + TestDB.MYSQL_V8 + TestDB.ALL_H2) }

        withTables(testDB, DefaultTimestampTable) {
            val entity = DefaultTimestampEntity.new {}
            val timestamp = DefaultTimestampTable.selectAll().first()[DefaultTimestampTable.timestamp]
            timestamp shouldBeEqualTo entity.timestamp
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCustomDefaultTimestampFunctionWithInsertStatement(testDB: TestDB) {
        // Only Postgres allows to get timestamp values directly from the insert statement due to implicit 'returning *'
        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }

        withTables(testDB, DefaultTimestampTable) {
            val entity = DefaultTimestampTable.insert { }
            val timestamp = DefaultTimestampTable.selectAll().first()[DefaultTimestampTable.timestamp]
            timestamp shouldBeEqualTo entity[DefaultTimestampTable.timestamp]
        }
    }
}
