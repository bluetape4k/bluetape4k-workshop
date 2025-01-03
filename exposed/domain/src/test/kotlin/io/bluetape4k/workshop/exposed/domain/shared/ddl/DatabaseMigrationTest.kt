package io.bluetape4k.workshop.exposed.domain.shared.ddl

import MigrationUtils
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeEqualToIgnoringCase
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldStartWithIgnoringCase
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.byteLiteral
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.doubleLiteral
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.floatLiteral
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.longLiteral
import org.jetbrains.exposed.sql.shortLiteral
import org.jetbrains.exposed.sql.ubyteLiteral
import org.jetbrains.exposed.sql.uintLiteral
import org.jetbrains.exposed.sql.ulongLiteral
import org.jetbrains.exposed.sql.ushortLiteral
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.PrimaryKeyMetadata
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.properties.Delegates

@OptIn(ExperimentalDatabaseMigrationApi::class)
class DatabaseMigrationTest: AbstractExposedTest() {

    private fun TestDB.dropAllSequence() {
        withDb(this) {
            if (currentDialectTest.supportsCreateSequence) {
                val allSequences =
                    currentDialectTest.sequences().map { name -> org.jetbrains.exposed.sql.Sequence(name) }.toSet()
                allSequences.forEach { sequence ->
                    val dropStatements = sequence.dropStatement()
                    dropStatements.forEach { statement ->
                        exec(statement)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testMigrationScriptDirectoryAndContent(dialect: TestDB) {
        dialect.dropAllSequence()

        val tableName = "tester"
        val noPKTable = object: Table(tableName) {
            val bar = integer("bar")
        }
        val singlePKTable = object: Table(tableName) {
            val bar = integer("bar")

            override val primaryKey = PrimaryKey(bar)
        }

        val scriptName = "V2__Add_primary_key"
        val scriptDirectory = "src/test/resources"

        withTables(dialect, noPKTable) {
            val script = MigrationUtils.generateMigrationScript(
                singlePKTable,
                scriptDirectory = scriptDirectory,
                scriptName = scriptName,
                withLogs = true
            )
            script.exists().shouldBeTrue()
            script.path shouldBeEqualTo "src/test/resources/$scriptName.sql"

            val expectedStatements: List<String> = MigrationUtils.statementsRequiredForDatabaseMigration(
                singlePKTable,
                withLogs = true
            )
            expectedStatements.size shouldBeEqualTo 1

            val fileStatements: List<String> = script.bufferedReader().readLines().map { it.trimEnd(';') }
            expectedStatements.zip(fileStatements).forEach { (expected, actual) ->
                actual shouldBeEqualTo expected
            }

            File("$scriptDirectory/$scriptName.sql").delete().shouldBeTrue()
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testMigrationScriptOverwrittenIfAlreadyExists(dialect: TestDB) {
        dialect.dropAllSequence()

        val tableName = "tester"
        val noPKTable = object: Table(tableName) {
            val bar = integer("bar")
        }
        val singlePKTable = object: Table(tableName) {
            val bar = integer("bar")

            override val primaryKey = PrimaryKey(bar)
        }

        val directory = "src/test/resources"
        val name = "V2__Test"

        withTables(dialect, noPKTable) {
            // Create initial script
            val initialScript = File("$directory/$name.sql")
            initialScript.createNewFile()
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                noPKTable,
                withLogs = false
            )
            statements.forEach {
                initialScript.appendText(it)
            }

            // Generate script with the same name of initial script
            val newScript = MigrationUtils.generateMigrationScript(
                singlePKTable,
                scriptDirectory = directory,
                scriptName = name,
                withLogs = true
            )

            val expectedStatements: List<String> = MigrationUtils.statementsRequiredForDatabaseMigration(
                singlePKTable,
                withLogs = true
            )
            expectedStatements.size shouldBeEqualTo 1

            val fileStatements: List<String> = newScript.bufferedReader().readLines().map { it.trimEnd(';') }
            expectedStatements.zip(fileStatements).forEach { (expected, actual) ->
                actual shouldBeEqualTo expected
            }

            File("$directory/$name.sql").delete().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testNoTablesPassedWhenGeneratingMigrationScript(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            expectException<IllegalArgumentException> {
                MigrationUtils.generateMigrationScript(
                    scriptDirectory = "src/test/resources",
                    scriptName = "V2__Test",
                    withLogs = false
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateStatementsGeneratedForTablesThatDoNotExist(dialect: TestDB) {
        dialect.dropAllSequence()

        val tester = object: Table("tester") {
            val bar = char("bar")
        }

        withDb(dialect) {
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                tester,
                withLogs = false
            )
            statements.size shouldBeEqualTo 1
            statements.single() shouldBeEqualTo
                    "CREATE TABLE ${addIfNotExistsIfSupported()}${tester.nameInDatabaseCase()} " +
                    "(${"bar".inProperCase()} CHAR NOT NULL)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedColumnsStatementsIdentical(dialect: TestDB) {
        dialect.dropAllSequence()

        val t1 = object: Table("foo") {
            val col1 = integer("col1")
            val col2 = integer("CoL2")
            val col3 = integer("\"CoL3\"")
        }

        val t2 = object: Table("foo") {
            val col1 = integer("col1")
            val col2 = integer("CoL2")
            val col3 = integer("\"CoL3\"")
        }

        withTables(dialect, t1) {
            val statements = MigrationUtils.dropUnmappedColumnsStatements(t2, withLogs = false)
            statements.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedColumns(dialect: TestDB) {
        dialect.dropAllSequence()

        val t1 = object: Table("foo") {
            val id = integer("id")
            val name = text("name")
        }

        val t2 = object: Table("foo") {
            val id = integer("id")
        }

        withTables(dialect, t1) {

            MigrationUtils.statementsRequiredForDatabaseMigration(
                t1,
                withLogs = false
            ).shouldBeEmpty()

            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                t2,
                withLogs = false
            )
            statements.size shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddNewPrimaryKeyOnExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        val tableName = "tester"
        val noPKTable = object: Table(tableName) {
            val bar = integer("bar")
        }

        val singlePKTable = object: Table(tableName) {
            val bar = integer("bar")

            override val primaryKey = PrimaryKey(bar)
        }

        withTables(dialect, noPKTable) {
            val primaryKey: PrimaryKeyMetadata? = currentDialectTest.existingPrimaryKeys(singlePKTable)[singlePKTable]
            primaryKey.shouldBeNull()

            val expected =
                "ALTER TABLE ${tableName.inProperCase()} ADD PRIMARY KEY (${noPKTable.bar.nameInDatabaseCase()})"
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                singlePKTable,
                withLogs = false
            )
            statements.single() shouldBeEqualTo expected
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun columnsWithDefaultValuesThatHaveNotChangedShouldNotTriggerChange(dialect: TestDB) {
        dialect.dropAllSequence()

        var table by Delegates.notNull<Table>()
        withDb(dialect) { testDb ->
            try {
                // MySQL doesn't support default values on text columns, hence excluded
                table = if (testDb !in TestDB.ALL_MYSQL) {
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
                val actual = MigrationUtils.statementsRequiredForDatabaseMigration(table, withLogs = false)
                actual.shouldBeEmpty()
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateTableWithQuotedIdentifiers(dialect: TestDB) {
        dialect.dropAllSequence()

        val identifiers = listOf("\"IdentifierTable\"", "\"IDentiFierCoLUmn\"")
        val quotedTable = object: Table(identifiers[0]) {
            val column1 = varchar(identifiers[1], 32)
        }

        withTables(dialect, quotedTable) {
            quotedTable.exists() shouldBeEqualTo true

            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(quotedTable, withLogs = false)
            statements.isEmpty().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropExtraIndexOnSameColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        val testTableWithTwoIndices = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
            val byName = index("test_table_by_name", false, name)
            val byName2 = index("test_table_by_name_2", false, name)
        }

        val testTableWithOneIndex = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
            val byName = index("test_table_by_name", false, name)
        }

        // Oracle does not allow more than one index on a column
        withTables(dialect, testTableWithTwoIndices) {
            testTableWithTwoIndices.exists().shouldBeTrue()

            val statements =
                MigrationUtils.statementsRequiredForDatabaseMigration(testTableWithOneIndex, withLogs = false)
            statements.size shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedIndex(dialect: TestDB) {
        dialect.dropAllSequence()

        val testTableWithIndex = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
            val byName = index("test_table_by_name", false, name)
        }

        val testTableWithoutIndex = object: Table("test_table") {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
        }

        withTables(dialect, testTableWithIndex) {
            testTableWithIndex.exists().shouldBeTrue()

            val statements =
                MigrationUtils.statementsRequiredForDatabaseMigration(testTableWithoutIndex, withLogs = false)
            statements.size shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddAutoIncrementToExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withTables(dialect, tableWithoutAutoIncrement) { testDb ->
            MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithoutAutoIncrement,
                withLogs = false
            ).shouldBeEmpty()

            val statements =
                MigrationUtils.statementsRequiredForDatabaseMigration(tableWithAutoIncrement, withLogs = false)
            when (testDb) {
                TestDB.POSTGRESQL -> {
                    statements.size shouldBeEqualTo 3
                    statements[0] shouldBeEqualTo expectedCreateSequenceStatement("test_table_id_seq")
                    statements[1] shouldBeEqualTo "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"
                    statements[2] shouldBeEqualTo "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
                }

                else              -> {
                    statements.size shouldBeEqualTo 1
                    val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                    statements[0].startsWith(
                        "ALTER TABLE test_table $alterColumnWord COLUMN id ",
                        ignoreCase = true
                    ).shouldBeTrue()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddAutoIncrementWithSequenceNameToExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withTables(dialect, tableWithoutAutoIncrement) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithoutAutoIncrement,
                withLogs = false
            ).shouldBeEmpty()

            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithAutoIncrementSequenceName,
                withLogs = false
            )
            statements.size shouldBeEqualTo 1

            if (currentDialectTest.supportsCreateSequence) {
                statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)
            } else {
                val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                statements[0].equals(
                    "ALTER TABLE TEST_TABLE $alterColumnWord COLUMN ID BIGINT AUTO_INCREMENT NOT NULL",
                    ignoreCase = true
                ).shouldBeTrue()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddAutoIncrementWithCustomSequenceToExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withTables(dialect, tableWithoutAutoIncrement) {
            if (currentDialectTest.supportsCreateSequence) {
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    tableWithoutAutoIncrement,
                    withLogs = false
                ).shouldBeEmpty()

                val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                    tableWithAutoIncrementCustomSequence,
                    withLogs = false
                )
                statements.size shouldBeEqualTo 1
                statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropAutoIncrementOnExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        val tableWithAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()

            override val primaryKey = PrimaryKey(id)
        }
        val tableWithoutAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(dialect, tableWithAutoIncrement) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithAutoIncrement,
                withLogs = false
            ).shouldBeEmpty()

            val statements =
                MigrationUtils.statementsRequiredForDatabaseMigration(tableWithoutAutoIncrement, withLogs = false)
            when (dialect) {
                TestDB.POSTGRESQL -> {
                    statements.size shouldBeEqualTo 2
                    statements[0] shouldBeEqualTo "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT"
                    statements[1] shouldBeEqualTo expectedDropSequenceStatement("test_table_id_seq")
                }

                else              -> {
                    statements.size shouldBeEqualTo 1
                    val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                    statements[0] shouldBeEqualToIgnoringCase "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT"
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddSequenceNameToExistingAutoIncrementColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        val sequenceName = "custom_sequence"
        val tableWithAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()

            override val primaryKey = PrimaryKey(id)
        }
        val tableWithAutoIncrementSequenceName = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(dialect, tableWithAutoIncrement) {
            if (currentDialectTest.supportsCreateSequence) {
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    tableWithAutoIncrement,
                    withLogs = false
                ).shouldBeEmpty()

                val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                    tableWithAutoIncrementSequenceName,
                    withLogs = false
                )
                statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)
                when (dialect) {
                    TestDB.POSTGRESQL -> {
                        statements.size shouldBeEqualTo 3
                        statements[1] shouldBeEqualTo "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT, ALTER COLUMN id DROP DEFAULT"
                        statements[2] shouldBeEqualTo expectedDropSequenceStatement("test_table_id_seq")
                    }

                    else              -> {
                        val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                        statements[1].startsWith(
                            "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT",
                            ignoreCase = true
                        ).shouldBeTrue()

                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddCustomSequenceToExistingAutoIncrementColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrement)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    ).shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence,
                        withLogs = false
                    )
                    statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
                    when (dialect) {
                        TestDB.POSTGRESQL -> {
                            statements.size shouldBeEqualTo 3
                            statements[1] shouldBeEqualTo
                                    "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT, ALTER COLUMN id DROP DEFAULT"
                            statements[2] shouldBeEqualTo expectedDropSequenceStatement("test_table_id_seq")
                        }

                        else              -> {
                            statements.size shouldBeEqualTo 2
                            val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                            statements[1].startsWith(
                                "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT",
                                ignoreCase = true
                            ).shouldBeTrue()

                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrement)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropAutoIncrementWithSequenceNameOnExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName,
                        withLogs = false
                    ).shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithoutAutoIncrement,
                        withLogs = false
                    )
                    when (dialect) {
                        TestDB.H2 -> {
                            statements.size shouldBeEqualTo 1
                        }

                        else      -> {
                            statements.size shouldBeEqualTo 1
                            statements[0].equals(
                                expectedDropSequenceStatement(sequenceName),
                                ignoreCase = true
                            ).shouldBeTrue()
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementSequenceName)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropSequenceNameOnExistingAutoIncrementColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName
                    ).shouldBeEmpty()

                    val statements =
                        MigrationUtils.statementsRequiredForDatabaseMigration(tableWithAutoIncrement, withLogs = false)

                    when (dialect) {
                        TestDB.POSTGRESQL -> {
                            statements.size shouldBeEqualTo 4
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement("test_table_id_seq")
                            statements[1] shouldBeEqualTo
                                    "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"

                            statements[2] shouldBeEqualTo "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
                            statements[3] shouldBeEqualTo expectedDropSequenceStatement(sequenceName)
                        }

                        TestDB.H2_V1      -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo "ALTER TABLE TEST_TABLE ALTER COLUMN ID BIGINT AUTO_INCREMENT NOT NULL"
                        }

                        else              -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldStartWithIgnoringCase "ALTER TABLE TEST_TABLE ALTER COLUMN ID"
                            statements[1] shouldBeEqualToIgnoringCase expectedDropSequenceStatement(sequenceName)
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementSequenceName)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddCustomSequenceToExistingAutoIncrementColumnWithSequenceName(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName,
                        withLogs = false
                    ).shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence,
                        withLogs = false
                    )
                    when (dialect) {
                        TestDB.H2_V1 -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
                        }

                        else         -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
                            statements[1] shouldBeEqualToIgnoringCase expectedDropSequenceStatement(sequenceName)
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementSequenceName)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropAutoIncrementWithCustomSequenceOnExistingColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementCustomSequence)
                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence,
                        withLogs = false
                    ).shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithoutAutoIncrement,
                        withLogs = false
                    )
                    when (dialect) {
                        TestDB.H2_V1 -> {
                            statements.size shouldBeEqualTo 0
                        }

                        else         -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualToIgnoringCase
                                    expectedDropSequenceStatement(sequence.name)
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementCustomSequence)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropCustomSequenceOnExistingAutoIncrementColumn(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementCustomSequence)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence
                    ).shouldBeEmpty()


                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    )
                    when (dialect) {
                        TestDB.POSTGRESQL -> {
                            statements.size shouldBeEqualTo 4
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement("test_table_id_seq")
                            statements[1] shouldBeEqualTo
                                    "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"
                            statements[2] shouldBeEqualTo "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
                            statements[3] shouldBeEqualTo expectedDropSequenceStatement(sequence.name)
                        }

                        TestDB.H2_V1      -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo
                                    "ALTER TABLE TEST_TABLE ALTER COLUMN ID BIGINT AUTO_INCREMENT NOT NULL"
                        }

                        else              -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldStartWithIgnoringCase
                                    "ALTER TABLE TEST_TABLE ALTER COLUMN ID"
                            statements[1] shouldBeEqualToIgnoringCase
                                    expectedDropSequenceStatement(sequence.name)

                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementCustomSequence)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddSequenceNameToExistingAutoIncrementColumnWithCustomSequence(dialect: TestDB) {
        dialect.dropAllSequence()

        withDb(dialect) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementCustomSequence)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence
                    ).shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName,
                        withLogs = false
                    )
                    when (dialect) {
                        TestDB.H2_V1 -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)
                        }

                        else         -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)
                            statements[1] shouldBeEqualToIgnoringCase expectedDropSequenceStatement(sequence.name)
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrementCustomSequence)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testNumericTypeLiteralsAsDefaultsDoNotTriggerMigrationStatements(dialect: TestDB) {
        dialect.dropAllSequence()

        val tester = object: Table("tester") {
            val byte = byte("byte_column").defaultExpression(byteLiteral(Byte.MIN_VALUE))
            val ubyte = ubyte("ubyte_column").defaultExpression(ubyteLiteral(UByte.MAX_VALUE))
            val short = short("short_column").defaultExpression(shortLiteral(Short.MIN_VALUE))
            val ushort = ushort("ushort_column").defaultExpression(ushortLiteral(UShort.MAX_VALUE))
            val integer = integer("integer_column").defaultExpression(intLiteral(Int.MIN_VALUE))
            val uinteger = uinteger("uinteger_column").defaultExpression(uintLiteral(UInt.MAX_VALUE))
            val long = long("long_column").defaultExpression(longLiteral(Long.MIN_VALUE))
            val ulong = ulong("ulong_column").defaultExpression(ulongLiteral(Long.MAX_VALUE.toULong()))
            val float = float("float_column").defaultExpression(floatLiteral(3.14159F))
            val double = double("double_column").defaultExpression(doubleLiteral(3.1415926535))
            val decimal = decimal("decimal_column", 6, 3).defaultExpression(decimalLiteral(123.456.toBigDecimal()))
        }

        withTables(dialect, tester) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                tester,
                withLogs = false
            ).shouldBeEmpty()
        }
    }

    private fun expectedCreateSequenceStatement(sequenceName: String) =
        "CREATE SEQUENCE${" IF NOT EXISTS".takeIf { currentDialectTest.supportsIfNotExists } ?: ""} " +
                "$sequenceName START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807"

    private fun expectedDropSequenceStatement(sequenceName: String) =
        "DROP SEQUENCE${" IF EXISTS".takeIf { currentDialectTest.supportsIfNotExists } ?: ""} $sequenceName"

    private val sequence = org.jetbrains.exposed.sql.Sequence(
        name = "my_sequence",
        startWith = 1,
        minValue = 1,
        maxValue = 9223372036854775807
    )

    private val sequenceName = "custom_sequence"

    private val tableWithoutAutoIncrement = object: IdTable<Long>("test_table") {
        override val id: Column<EntityID<Long>> = long("id").entityId()
    }

    private val tableWithAutoIncrement = object: IdTable<Long>("test_table") {
        override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
    }

    private val tableWithAutoIncrementCustomSequence = object: IdTable<Long>("test_table") {
        override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequence).entityId()
    }

    private val tableWithAutoIncrementSequenceName = object: IdTable<Long>("test_table") {
        override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()
    }
}
