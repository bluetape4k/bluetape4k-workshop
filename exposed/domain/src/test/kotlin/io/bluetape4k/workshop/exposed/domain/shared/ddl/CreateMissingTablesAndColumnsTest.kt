package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.assertFailAndRollback
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.doubleLiteral
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.floatLiteral
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.PrimaryKeyMetadata
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import kotlin.properties.Delegates

class CreateMissingTablesAndColumnsTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * H2
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL,
     *      "time" BIGINT NOT NULL
     * );
     * ALTER TABLE TEST_TABLE ADD CONSTRAINT TEST_TABLE_TIME_UNIQUE UNIQUE ("time");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateMissingTablesAndColumns01(testDB: TestDB) {
        val testTable = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", length = 42)
            val time = long("time").uniqueIndex()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, testTable) {
            SchemaUtils.createMissingTablesAndColumns(testTable)
            // execCreateMissingTablesAndColumns(testTable)

            testTable.exists().shouldBeTrue()
            SchemaUtils.drop(testTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateMissingTablesAndColumns02(testDB: TestDB) {
        val testTable = object: IdTable<String>("Users2") {
            override val id: Column<EntityID<String>> = varchar("id", 22)
                .clientDefault { TimebasedUuid.Epoch.nextIdAsString() }
                .entityId()

            val name = varchar("name", 255)
            val email = varchar("email", 255).uniqueIndex()
            val camelCased = varchar("camelCased", 255).index()

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(testTable)
            // execCreateMissingTablesAndColumns(testTable)
            
            testTable.exists().shouldBeTrue()
            try {
                SchemaUtils.createMissingTablesAndColumns(testTable)
                // execCreateMissingTablesAndColumns(testTable)
            } finally {
                SchemaUtils.drop(testTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateMissingTablesAndColumnsChangeNullability(testDB: TestDB) {
        val t1 = object: IntIdTable("foo") {
            val foo = varchar("foo", 50)
        }

        val t2 = object: IntIdTable("foo") {
            val foo = varchar("foo", 50).nullable()
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(t1)
            // execCreateMissingTablesAndColumns(t1)

            t1.insert { it[foo] = "ABC" }
            assertFailAndRollback("Can't insert to not-null column") {
                t2.insert { it[foo] = null }
            }

            SchemaUtils.createMissingTablesAndColumns(t2)
            // execCreateMissingTablesAndColumns(t2)
            t2.insert { it[foo] = null }
            assertFailAndRollback("Can't make column non-null while has null value") {
                SchemaUtils.createMissingTablesAndColumns(t1)
                // execCreateMissingTablesAndColumns(t1)
            }

            t2.deleteWhere { t2.foo.isNull() }

            SchemaUtils.createMissingTablesAndColumns(t1)
            // execCreateMissingTablesAndColumns(t1)
            assertFailAndRollback("Can't insert to nullable column") {
                t2.insert { it[foo] = null }
            }
            SchemaUtils.drop(t1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateMissingTablesAndColumnsChangeAutoincrement(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_POSTGRES }

        val t1 = object: Table("foo") {
            val id = integer("idcol").autoIncrement()
            val foo = varchar("foo", 50)

            override val primaryKey = PrimaryKey(id)
        }

        val t2 = object: Table("foo") {
            val id = integer("idcol")
            val foo = varchar("foo", 50)

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(t1)
            // execCreateMissingTablesAndColumns(t1)

            t1.insert { it[foo] = "ABC" }

            SchemaUtils.createMissingTablesAndColumns(t2)
            // execCreateMissingTablesAndColumns(t2)

            assertFailAndRollback("Can't insert without primaryKey value") {
                t2.insert { it[foo] = "ABC" }
            }

            t2.insert {
                it[id] = 3
                it[foo] = "ABC"
            }

            SchemaUtils.drop(t1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddMissingColumnsStatementsChangeCasing(testDB: TestDB) {
        val t1 = object: Table("foo") {
            val id = integer("idCol")

            override val primaryKey = PrimaryKey(id)
        }

        val t2 = object: Table("foo") {
            val id = integer("idcol")

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            if (db.supportsAlterTableWithAddColumn) {
                SchemaUtils.createMissingTablesAndColumns(t1)
                // execCreateMissingTablesAndColumns(t1)

                val missingStatements = SchemaUtils.addMissingColumnsStatements(t2)

                val alterColumnWord = when (currentDialectTest) {
                    is MysqlDialect -> "MODIFY COLUMN"
                    is OracleDialect -> "MODIFY"
                    else -> "ALTER COLUMN"
                }

                val expected = if (t1.id.nameInDatabaseCase() != t2.id.nameInDatabaseCase()) {
                    "ALTER TABLE ${t2.nameInDatabaseCase()} $alterColumnWord ${t2.id.nameInDatabaseCase()} INT"
                } else {
                    null
                }

                missingStatements.firstOrNull() shouldBeEqualTo expected
                SchemaUtils.drop(t1)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddMissingColumnsStatementsIdentical(testDB: TestDB) {
        val t1 = object: Table("foo") {
            val id = integer("idcol")

            override val primaryKey = PrimaryKey(id)
        }

        val t2 = object: Table("foo") {
            val id = integer("idcol")

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(t1)
            // execCreateMissingTablesAndColumns(t1)

            val missingStatements = SchemaUtils.addMissingColumnsStatements(t2)
            missingStatements.shouldBeEmpty()
            SchemaUtils.drop(t1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddMissingColumnsStatementsIdentical2(testDB: TestDB) {
        val t1 = object: Table("foo") {
            val id = integer("idCol")

            override val primaryKey = PrimaryKey(id)
        }

        val t2 = object: Table("foo") {
            val id = integer("idCol")

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(t1)
            // execCreateMissingTablesAndColumns(t1)

            val missingStatements = SchemaUtils.addMissingColumnsStatements(t2)
            missingStatements.shouldBeEmpty()

            SchemaUtils.drop(t1)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateMissingTablesAndColumnsChangeCascadeType(testDB: TestDB) {
        val fooTable = object: IntIdTable("foo") {
            val foo = varchar("foo", 50)
        }

        val barTable1 = object: IntIdTable("bar") {
            val foo = optReference("foo", fooTable, onDelete = ReferenceOption.NO_ACTION)
        }

        val barTable2 = object: IntIdTable("bar") {
            val foo = optReference("foo", fooTable, onDelete = ReferenceOption.CASCADE)
        }

        withTables(testDB, fooTable, barTable1) {
            SchemaUtils.createMissingTablesAndColumns(barTable2)
            // execCreateMissingTablesAndColumns(barTable2)

            barTable2.exists().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun addAutoPrimaryKey(testDB: TestDB) {
        Assumptions.assumeTrue(testDB !in TestDB.ALL_H2)
        val tableName = "Foo"
        val initialTable = object: Table(tableName) {
            val bar = text("bar")
        }
        val t = IntIdTable(tableName)

        withTables(testDB, initialTable) {
            t.id.ddl.single() shouldBeEqualTo "ALTER TABLE ${tableName.inProperCase()} ADD ${"id".inProperCase()} ${t.id.columnType.sqlType()} PRIMARY KEY"
            currentDialectTest.tableColumns(t)[t]!!.size shouldBeEqualTo 1

            SchemaUtils.createMissingTablesAndColumns(t)
            // execCreateMissingTablesAndColumns(t)
            currentDialectTest.tableColumns(t)[t]!!.size shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddNewPrimaryKeyOnExistingColumn(testDB: TestDB) {
        val tableName = "tester"
        val noPKTable = object: Table(tableName) {
            val bar = integer("bar")
        }

        val singlePKTable = object: Table(tableName) {
            val bar = integer("bar")

            override val primaryKey = PrimaryKey(bar)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(noPKTable)
            // execCreateMissingTablesAndColumns(noPKTable)
            var primaryKey: PrimaryKeyMetadata? = currentDialectTest.existingPrimaryKeys(singlePKTable)[singlePKTable]
            primaryKey.shouldBeNull()

            val expected =
                "ALTER TABLE ${tableName.inProperCase()} ADD PRIMARY KEY (${noPKTable.bar.nameInDatabaseCase()})"
            val statements = SchemaUtils.statementsRequiredToActualizeScheme(singlePKTable)
            statements.single() shouldBeEqualTo expected

            SchemaUtils.createMissingTablesAndColumns(singlePKTable)
            // execCreateMissingTablesAndColumns(singlePKTable)

            primaryKey = currentDialectTest.existingPrimaryKeys(singlePKTable)[singlePKTable]
            primaryKey.shouldNotBeNull()
            primaryKey.columnNames.single() shouldBeEqualTo "bar".inProperCase()

            SchemaUtils.drop(noPKTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun columnsWithDefaultValuesThatHaveNotChangedShouldNotTriggerChange(testDB: TestDB) {
        var table by Delegates.notNull<Table>()

        withDb(testDB) {
            try {
                // MySQL doesn't support default values on text columns, hence excluded
                table = if (testDB !in TestDB.ALL_MYSQL) {
                    object: Table("varchar_test") {
                        val varchar = varchar("varchar_column", 255).default(" ")
                        val text = text("text_column").default(" ")
                    }
                } else {
                    object: Table("varchar_test") {
                        val varchar = varchar("varchar_column", 255).default(" ")
                    }
                }

                SchemaUtils.create(table)
                val actual = SchemaUtils.statementsRequiredToActualizeScheme(table)
                actual.shouldBeEmpty()
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }

    private class StringFieldTable(
        name: String,
        isTextColumn: Boolean,
        default: String,
    ): IntIdTable(name) {
        // nullable column is here as Oracle treat '' as NULL
        val column: Column<String?> = if (isTextColumn) {
            text("test_column").default(default).nullable()
        } else {
            varchar("test_column", 255).default(default).nullable()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun columnsWithDefaultValuesThatAreWhitespacesShouldNotBeTreatedAsEmptyStrings(testDB: TestDB) {
        val tableWhitespaceDefaultVarchar = StringFieldTable("varchar_whitespace_test", false, " ")
        val tableWhitespaceDefaultText = StringFieldTable("text_whitespace_test", true, " ")
        val tableEmptyStringDefaultVarchar = StringFieldTable("varchar_whitespace_test", false, "")
        val tableEmptyStringDefaultText = StringFieldTable("text_whitespace_test", true, "")

        // SQLite doesn't support alter table with add column, so it doesn't generate the statements, hence excluded
        withDb(testDB) {
            // MySQL doesn't support default values on text columns, hence excluded
            val supportsTextDefault = testDB !in TestDB.ALL_MYSQL
            val tablesToTest = listOfNotNull(
                tableWhitespaceDefaultVarchar to tableEmptyStringDefaultVarchar,
                (tableWhitespaceDefaultText to tableEmptyStringDefaultText).takeIf { supportsTextDefault },
            )
            tablesToTest.forEach { (whiteSpaceTable, emptyTable) ->
                try {
                    SchemaUtils.create(whiteSpaceTable)

                    val whiteSpaceId = whiteSpaceTable.insertAndGetId { }

                    whiteSpaceTable.selectAll().where {
                        whiteSpaceTable.id eq whiteSpaceId
                    }.single()[whiteSpaceTable.column] shouldBeEqualTo " "

                    val actual = SchemaUtils.statementsRequiredToActualizeScheme(emptyTable)
                    val expected = 1 // if (testDB == TestDB.SQLSERVER) 2 else 1
                    actual.size shouldBeEqualTo expected

                    SchemaUtils.drop(whiteSpaceTable)
                    SchemaUtils.create(emptyTable)

                    val emptyId = emptyTable.insertAndGetId { }

                    // null is here as Oracle treat '' as NULL
                    val expectedEmptyValue = ""
                    emptyTable.selectAll().where { emptyTable.id eq emptyId }
                        .single()[emptyTable.column] shouldBeEqualTo expectedEmptyValue
                } finally {
                    SchemaUtils.drop(whiteSpaceTable, emptyTable)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    @Suppress("MaximumLineLength")
    fun testAddMissingColumnsStatementsChangeDefault(testDB: TestDB) {
        val t1 = object: Table("foo") {
            val id = integer("idcol")
            val col = integer("col").nullable()
            val strcol = varchar("strcol", 255).nullable()

            override val primaryKey = PrimaryKey(id)
        }

        val t2 = object: Table("foo") {
            val id = integer("idcol")
            val col = integer("col").default(1)
            val strcol = varchar("strcol", 255).default("def")

            override val primaryKey = PrimaryKey(id)
        }

        val complexAlterTable = listOf(TestDB.POSTGRESQL, TestDB.POSTGRESQLNG, TestDB.H2_PSQL)

        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(t1)
                // execCreateMissingTablesAndColumns(t1)

                val missingStatements = SchemaUtils.addMissingColumnsStatements(t2)

                if (testDB !in complexAlterTable) {
                    val alterColumnWord = when (currentDialectTest) {
                        is MysqlDialect -> "MODIFY COLUMN"
                        is OracleDialect -> "MODIFY"
                        else -> "ALTER COLUMN"
                    }
                    val expected = setOf(
                        "ALTER TABLE ${t2.nameInDatabaseCase()} $alterColumnWord ${t2.col.nameInDatabaseCase()} ${t2.col.columnType.sqlType()} DEFAULT 1 NOT NULL",
                        "ALTER TABLE ${t2.nameInDatabaseCase()} $alterColumnWord ${t2.strcol.nameInDatabaseCase()} ${t2.strcol.columnType.sqlType()} DEFAULT 'def' NOT NULL",
                    )
                    missingStatements.toSet() shouldBeEqualTo expected
                } else {
                    missingStatements.shouldNotBeEmpty()
                }

                missingStatements.forEach {
                    exec(it)
                }
            } finally {
                SchemaUtils.drop(t1)
            }
        }

        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(t2)
                // execCreateMissingTablesAndColumns(t2)

                val missingStatements = SchemaUtils.addMissingColumnsStatements(t1)

                if (testDB !in complexAlterTable) {
                    val alterColumnWord = when (currentDialectTest) {
                        is MysqlDialect -> "MODIFY COLUMN"
                        is OracleDialect -> "MODIFY"
                        else -> "ALTER COLUMN"
                    }
                    val expected = setOf(
                        "ALTER TABLE ${t2.nameInDatabaseCase()} $alterColumnWord ${t1.col.nameInDatabaseCase()} ${t1.col.columnType.sqlType()} NULL",
                        "ALTER TABLE ${t2.nameInDatabaseCase()} $alterColumnWord ${t1.strcol.nameInDatabaseCase()} ${t1.strcol.columnType.sqlType()} NULL",
                    )
                    missingStatements.toSet() shouldBeEqualTo expected
                } else {
                    missingStatements.shouldNotBeEmpty()
                }

                missingStatements.forEach {
                    exec(it)
                }
            } finally {
                SchemaUtils.drop(t2)
            }
        }
    }

    private enum class TestEnum { A, B, C }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `check that running addMissingTablesAndColumns multiple time doesnt affect schema`(testDB: TestDB) {
        val table = object: Table("defaults2") {
            val bool1 = bool("boolCol1").default(false)
            val bool2 = bool("boolCol2").default(true)
            val int = integer("intCol").default(12345)
            val float = float("floatCol").default(123.45f)
            val decimal = decimal("decimalCol", 10, 1).default(BigDecimal.TEN)
            val string = varchar("varcharCol", 50).default("12345")
            val enum1 = enumeration("enumCol1", TestEnum::class).default(TestEnum.B)
            val enum2 = enumerationByName("enumCol2", 25, TestEnum::class).default(TestEnum.B)
        }

        withDb(testDB) {
            try {
                SchemaUtils.create(table)
                SchemaUtils.statementsRequiredToActualizeScheme(table).shouldBeEmpty()
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun createTableWithMultipleIndexes(testDB: TestDB) {
        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(MultipleIndexesTable)
                // execCreateMissingTablesAndColumns(MultipleIndexesTable)
            } finally {
                SchemaUtils.drop(MultipleIndexesTable)
            }
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testForeignKeyCreation(testDB: TestDB) {
        val usersTable = object: IntIdTable("tmpusers") {}
        val spacesTable = object: IntIdTable("spaces") {
            val userId = reference("userId", usersTable)
        }

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(usersTable, spacesTable)
            // execCreateMissingTablesAndColumns(usersTable, spacesTable)
            usersTable.exists().shouldBeTrue()
            spacesTable.exists().shouldBeTrue()
            SchemaUtils.drop(usersTable, spacesTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCamelCaseForeignKeyCreation(testDB: TestDB) {
        val ordersTable = object: IntIdTable("tmporders") {
            val traceNumber = char("traceNumber", 10).uniqueIndex()
        }
        val receiptsTable = object: IntIdTable("receipts") {
            val traceNumber = reference("traceNumber", ordersTable.traceNumber)
        }

        // Oracle metadata only returns foreign keys that reference primary keys
        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(ordersTable, receiptsTable)
            // execCreateMissingTablesAndColumns(ordersTable, receiptsTable)
            ordersTable.exists().shouldBeTrue()
            receiptsTable.exists().shouldBeTrue()
            SchemaUtils.drop(ordersTable, receiptsTable)
        }
    }

    object MultipleIndexesTable: Table("H2_MULTIPLE_INDEXES") {
        val value1 = varchar("value1", 255)
        val value2 = varchar("value2", 255)

        init {
            uniqueIndex("index1", value1, value2)
            uniqueIndex("index2", value2, value1)
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateTableWithReferenceMultipleTimes(testDB: TestDB) {
        withTables(testDB, PlayerTable, SessionsTable) {
            SchemaUtils.createMissingTablesAndColumns(PlayerTable, SessionsTable)
            SchemaUtils.createMissingTablesAndColumns(PlayerTable, SessionsTable)
            // execCreateMissingTablesAndColumns(PlayerTable, SessionsTable)
            // execCreateMissingTablesAndColumns(PlayerTable, SessionsTable)

            PlayerTable.exists().shouldBeTrue()
            SessionsTable.exists().shouldBeTrue()
        }
    }

    object PlayerTable: IntIdTable() {
        val username = varchar("username", 10).uniqueIndex().nullable()
    }

    object SessionsTable: IntIdTable() {
        val playerId = integer("player_id").references(PlayerTable.id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun createTableWithReservedIdentifierInColumnName(testDB: TestDB) {
        Assumptions.assumeTrue(testDB == TestDB.MYSQL_V5)

        withDb(testDB) {
            SchemaUtils.createMissingTablesAndColumns(T1, T2)
            SchemaUtils.createMissingTablesAndColumns(T1, T2)
//            execCreateMissingTablesAndColumns(T1, T2)
//            execCreateMissingTablesAndColumns(T1, T2)

            T1.exists().shouldBeTrue()
            T2.exists().shouldBeTrue()

            SchemaUtils.drop(T1, T2)
        }
    }

    object ExplicitTable: IntIdTable() {
        val playerId = integer("player_id")
            .references(PlayerTable.id, fkName = "Explicit_FK_NAME")
    }

    object NonExplicitTable: IntIdTable() {
        val playerId = integer("player_id")
            .references(PlayerTable.id)
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun explicitFkNameIsExplicit(testDB: TestDB) {
        withTables(testDB, PlayerTable, ExplicitTable, NonExplicitTable) {
            ExplicitTable.playerId.foreignKey!!.customFkName shouldBeEqualTo "Explicit_FK_NAME"
            NonExplicitTable.playerId.foreignKey!!.customFkName.shouldBeNull()
        }
    }

    object T1: Table("ARRAY") {
        val name = integer("name").uniqueIndex()
        val tmp = varchar("temp", 255)
    }

    object T2: Table("CHAIN") {
        val ref = integer("ref").references(T1.name)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `test create table with name from system scheme`(testDB: TestDB) {
        val usersTable = object: IdTable<String>("users") {
            override var id: Column<EntityID<String>> = varchar("id", 190).entityId()

            override val primaryKey = PrimaryKey(id)
        }
        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(usersTable)
                // execCreateMissingTablesAndColumns(usersTable)
                usersTable.exists().shouldBeTrue()
            } finally {
                SchemaUtils.drop(usersTable)
            }
        }
    }

    object CompositePrimaryKeyTable: Table("H2_COMPOSITE_PRIMARY_KEY") {
        val idA = varchar("id_a", 255)
        val idB = varchar("id_b", 255)
        override val primaryKey = PrimaryKey(idA, idB)
    }

    object CompositeForeignKeyTable: Table("H2_COMPOSITE_FOREIGN_KEY") {
        val idA = varchar("id_a", 255)
        val idB = varchar("id_b", 255)

        init {
            foreignKey(idA, idB, target = CompositePrimaryKeyTable.primaryKey)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateCompositePrimaryKeyTableAndCompositeForeignKeyTableMultipleTimes(testDB: TestDB) {
        withTables(testDB, CompositePrimaryKeyTable, CompositeForeignKeyTable) {
            SchemaUtils.createMissingTablesAndColumns(CompositePrimaryKeyTable, CompositeForeignKeyTable)
            SchemaUtils.createMissingTablesAndColumns(CompositePrimaryKeyTable, CompositeForeignKeyTable)
            val statements =
                SchemaUtils.statementsRequiredToActualizeScheme(CompositePrimaryKeyTable, CompositeForeignKeyTable)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateTableWithQuotedIdentifiers(testDB: TestDB) {
        val identifiers = listOf("\"IdentifierTable\"", "\"IDentiFierCoLUmn\"")
        val quotedTable = object: Table(identifiers[0]) {
            val column1 = varchar(identifiers[1], 32)
        }

        withDb(testDB) {
            try {
                SchemaUtils.createMissingTablesAndColumns(quotedTable)
                quotedTable.exists().shouldBeTrue()

                val statements = SchemaUtils.statementsRequiredToActualizeScheme(quotedTable)
                statements.shouldBeEmpty()
            } finally {
                SchemaUtils.drop(quotedTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateCompositePrimaryKeyTableAndCompositeForeignKeyInVariousOrder(testDB: TestDB) {
        withTables(testDB, CompositeForeignKeyTable, CompositePrimaryKeyTable) {
            SchemaUtils.createMissingTablesAndColumns(CompositePrimaryKeyTable, CompositeForeignKeyTable)
        }
        withTables(testDB, CompositeForeignKeyTable, CompositePrimaryKeyTable) {
            SchemaUtils.createMissingTablesAndColumns(CompositeForeignKeyTable, CompositePrimaryKeyTable)
        }
        withTables(testDB, CompositePrimaryKeyTable, CompositeForeignKeyTable) {
            SchemaUtils.createMissingTablesAndColumns(CompositePrimaryKeyTable, CompositeForeignKeyTable)
        }
        withTables(testDB, CompositePrimaryKeyTable, CompositeForeignKeyTable) {
            SchemaUtils.createMissingTablesAndColumns(CompositeForeignKeyTable, CompositePrimaryKeyTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateTableWithSchemaPrefix(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL }

        val schemaName = "my_schema"
        // index and foreign key both use table name to auto-generate their own names & to compare metadata
        // default columns in SQL Server requires a named constraint that uses table name
        val parentTable = object: IntIdTable("$schemaName.parent_table") {
            val secondId = integer("second_id").uniqueIndex()
            val column1 = varchar("column_1", 32).default("TEST")
        }
        val childTable = object: LongIdTable("$schemaName.child_table") {
            val parent = reference("my_parent", parentTable)
        }

        // SQLite does not recognize creation of schema other than the attached database
        withDb(testDB) {
            val schema = Schema(schemaName)

            // Should not require to be in the same schema
            SchemaUtils.createSchema(schema)
            SchemaUtils.create(parentTable, childTable)

            try {
                // Try in different schema
                SchemaUtils.createMissingTablesAndColumns(parentTable, childTable)
                // execCreateMissingTablesAndColumns(parentTable, childTable)
                parentTable.exists().shouldBeTrue()
                childTable.exists().shouldBeTrue()

                // Try in the same schema

                SchemaUtils.setSchema(schema)
                SchemaUtils.createMissingTablesAndColumns(parentTable, childTable)
                // execCreateMissingTablesAndColumns(parentTable, childTable)
                parentTable.exists().shouldBeTrue()
                childTable.exists().shouldBeTrue()

            } finally {
                SchemaUtils.dropSchema(schema, cascade = true)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testNoChangesOnCreateMissingNullableColumns(testDB: TestDB) {
        val testerWithDefaults = object: Table("tester") {
            val defaultNullNumber = integer("default_null_number").nullable().default(null)
            val defaultNullWord = varchar("default_null_word", 8).nullable().default(null)
            val nullNumber = integer("null_number").nullable()
            val nullWord = varchar("null_word", 8).nullable()
            val defaultNumber = integer("default_number").default(999).nullable()
            val defaultWord = varchar("default_word", 8).default("Hello").nullable()
        }

        val testerWithoutDefaults = object: Table("tester") {
            val defaultNullNumber = integer("default_null_number").nullable()
            val defaultNullWord = varchar("default_null_word", 8).nullable()
            val nullNumber = integer("null_number").nullable()
            val nullWord = varchar("null_word", 8).nullable()
            val defaultNumber = integer("default_number").default(999).nullable()
            val defaultWord = varchar("default_word", 8).default("Hello").nullable()
        }

        listOf(
            testerWithDefaults to testerWithDefaults,
            testerWithDefaults to testerWithoutDefaults,
            testerWithoutDefaults to testerWithDefaults,
            testerWithoutDefaults to testerWithoutDefaults
        ).forEach { (existingTable, definedTable) ->
            withTables(testDB, existingTable) {
                SchemaUtils.statementsRequiredToActualizeScheme(definedTable).also {
                    it.shouldBeEmpty()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testChangesOnCreateMissingNullableColumns(testDB: TestDB) {
        val testerWithDefaults = object: Table("tester") {
            val defaultNullString = varchar("default_null_string", 8).nullable().default("NULL")
            val defaultNumber = integer("default_number").default(999).nullable()
            val defaultWord = varchar("default_word", 8).default("Hello").nullable()
        }

        val testerWithoutDefaults = object: Table("tester") {
            val defaultNullString = varchar("default_null_string", 8).nullable()
            val defaultNumber = integer("default_number").nullable()
            val defaultWord = varchar("default_word", 8).nullable()
        }

        listOf(
            testerWithDefaults to testerWithoutDefaults,
            testerWithoutDefaults to testerWithDefaults,
        ).forEach { (existingTable, definedTable) ->
            withTables(testDB, existingTable) {
                SchemaUtils.statementsRequiredToActualizeScheme(definedTable).also {
                    it.shouldNotBeEmpty()
                }
            }
        }

        listOf(
            testerWithDefaults to testerWithDefaults,
            testerWithoutDefaults to testerWithoutDefaults
        ).forEach { (existingTable, definedTable) ->
            withTables(testDB, existingTable) {
                SchemaUtils.statementsRequiredToActualizeScheme(definedTable).also {
                    it.shouldBeEmpty()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testFloatDefaultColumnValue(testDB: TestDB) {
        val tester = object: Table("testFloatDefaultColumnValue") {
            val float = float("float_value").default(30.0f)
            val double = double("double_value").default(30.0)
            val floatExpression = float("float_expression_value").defaultExpression(floatLiteral(30.0f))
            val doubleExpression = double("double_expression_value").defaultExpression(doubleLiteral(30.0))
        }
        withTables(testDB, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testColumnTypesWithDefinedSizeAndScale(testDB: TestDB) {
        val originalTable = object: Table("tester") {
            val tax = decimal("tax", 3, 1)
            val address = varchar("address", 8)
            val zip = binary("zip", 1)
            val province = char("province", 1)
        }
        val newTable = object: Table("tester") {
            val tax = decimal("tax", 6, 3)
            val address = varchar("address", 16)
            val zip = binary("zip", 2)
            val province = char("province", 2)
        }

        val taxValue = 123.456.toBigDecimal()
        val addressValue = "A".repeat(16)
        val zipValue = "BB".toByteArray()
        val provinceValue = "CC"

        // SQLite doesn't support alter table with add column, so it doesn't generate alter statements
        withTables(testDB, originalTable) {
            expectException<IllegalArgumentException> {
                originalTable.insert {
                    it[tax] = taxValue
                    it[address] = addressValue
                    it[zip] = zipValue
                    it[province] = provinceValue
                }
            }

            val alterStatements = SchemaUtils.statementsRequiredToActualizeScheme(newTable)
            alterStatements.size shouldBeEqualTo 4
            alterStatements.forEach { exec(it) }

            newTable.insert {
                it[tax] = taxValue
                it[address] = addressValue
                it[zip] = zipValue
                it[province] = provinceValue
            }
        }
    }
} 
