package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.collections.tryForEach
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.H2_V1
import io.bluetape4k.workshop.exposed.TestDB.MARIADB
import io.bluetape4k.workshop.exposed.currentDialectMetadataTest
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeEqualToIgnoringCase
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldStartWithIgnoringCase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.byteLiteral
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.floatLiteral
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.shortLiteral
import org.jetbrains.exposed.v1.core.ubyteLiteral
import org.jetbrains.exposed.v1.core.uintLiteral
import org.jetbrains.exposed.v1.core.ulongLiteral
import org.jetbrains.exposed.v1.core.ushortLiteral
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PrimaryKeyMetadata
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.migration.MigrationUtils
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.properties.Delegates

@OptIn(ExperimentalDatabaseMigrationApi::class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DatabaseMigrationTest: AbstractExposedTest() {

    private fun TestDB.dropAllSequence() {
        withDb(this) {
            if (currentDialectTest.supportsCreateSequence) {
                val allSequences = currentDialectMetadataTest.sequences()
                    .map { name -> org.jetbrains.exposed.v1.core.Sequence(name) }
                    .toSet()

                allSequences.forEach { sequence ->
                    sequence.dropStatement().tryForEach { statement -> exec(statement) }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testMigrationScriptDirectoryAndContent(testDB: TestDB) {
        testDB.dropAllSequence()

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

        withTables(testDB, noPKTable) {
            val script = MigrationUtils.generateMigrationScript(
                singlePKTable,
                scriptDirectory = scriptDirectory,
                scriptName = scriptName,
                withLogs = false
            )
            script.exists().shouldBeTrue()
            script.path shouldBeEqualTo "src/test/resources/$scriptName.sql"

            val expectedStatements: List<String> = MigrationUtils.statementsRequiredForDatabaseMigration(
                singlePKTable,
                withLogs = true
            )
            expectedStatements shouldHaveSize 1

            val fileStatements: List<String> = script.bufferedReader().readLines().map { it.trimEnd(';') }
            expectedStatements.zip(fileStatements).forEach { (expected, actual) ->
                actual shouldBeEqualTo expected
            }

            File("$scriptDirectory/$scriptName.sql").delete().shouldBeTrue()
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testMigrationScriptOverwrittenIfAlreadyExists(testDB: TestDB) {
        testDB.dropAllSequence()

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

        withTables(testDB, noPKTable) {
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
    fun testNoTablesPassedWhenGeneratingMigrationScript(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
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
    fun testCreateStatementsGeneratedForTablesThatDoNotExist(testDB: TestDB) {
        testDB.dropAllSequence()

        val tester = object: Table("tester") {
            val bar = char("bar")
        }

        withDb(testDB) {
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS FOO (
     *      COL1 INT NOT NULL,
     *      "CoL2" INT NOT NULL,
     *      "CoL3" INT NOT NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedColumnsStatementsIdentical(testDB: TestDB) {
        testDB.dropAllSequence()

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

        withTables(testDB, t1) {
            val statements = MigrationUtils.dropUnmappedColumnsStatements(t2, withLogs = false)
            statements.shouldBeEmpty()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS FOO (
     *      ID INT NOT NULL,
     *      "name" TEXT NOT NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedColumns(testDB: TestDB) {
        testDB.dropAllSequence()

        val t1 = object: Table("foo") {
            val id = integer("id")
            val name = text("name")
        }

        val t2 = object: Table("foo") {
            val id = integer("id")
        }

        withTables(testDB, t1) {
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
    fun testAddNewPrimaryKeyOnExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        val tableName = "tester"
        val noPKTable = object: Table(tableName) {
            val bar = integer("bar")
        }

        val singlePKTable = object: Table(tableName) {
            val bar = integer("bar")

            override val primaryKey = PrimaryKey(bar)
        }

        withTables(testDB, noPKTable) {
            val primaryKey: PrimaryKeyMetadata? =
                currentDialectMetadataTest.existingPrimaryKeys(singlePKTable)[singlePKTable]
            primaryKey.shouldBeNull()

            val expected =
                "ALTER TABLE ${tableName.inProperCase()} ADD PRIMARY KEY (${noPKTable.bar.nameInDatabaseCase()})"
            val statements = MigrationUtils
                .statementsRequiredForDatabaseMigration(
                    singlePKTable,
                    withLogs = false
                )
            statements.single() shouldBeEqualTo expected
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun columnsWithDefaultValuesThatHaveNotChangedShouldNotTriggerChange(testDB: TestDB) {
        testDB.dropAllSequence()

        var table by Delegates.notNull<Table>()
        withDb(testDB) {
            // MySQL doesn't support default values on text columns, hence excluded
            table = if (testDB !in TestDB.ALL_MYSQL) {
                object: Table("varchar_test") {
                    val varchar = varchar("varchar_col", 255).default(" ")
                    val text = text("text_col").default(" ")
                }
            } else {
                object: Table("varchar_test") {
                    val varchar = varchar("varchar_col", 255).default(" ")
                }
            }
            try {
                SchemaUtils.create(table)
                MigrationUtils
                    .statementsRequiredForDatabaseMigration(table, withLogs = false)
                    .shouldBeEmpty()
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testCreateTableWithQuotedIdentifiers(testDB: TestDB) {
        testDB.dropAllSequence()

        val identifiers = listOf("\"IdentifierTable\"", "\"IDentiFierCoLUmn\"")
        val quotedTable = object: Table(identifiers[0]) {
            val column1 = varchar(identifiers[1], 32)
        }

        withTables(testDB, quotedTable) {
            quotedTable.exists() shouldBeEqualTo true

            val statements = MigrationUtils
                .statementsRequiredForDatabaseMigration(quotedTable, withLogs = false)
            statements.isEmpty().shouldBeTrue()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL
     * );
     * CREATE INDEX test_table_by_name ON TEST_TABLE ("name");
     * CREATE INDEX test_table_by_name_2 ON TEST_TABLE ("name");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropExtraIndexOnSameColumn(testDB: TestDB) {
        testDB.dropAllSequence()

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
        withTables(testDB, testTableWithTwoIndices) {
            testTableWithTwoIndices.exists().shouldBeTrue()

            val statements = MigrationUtils
                .statementsRequiredForDatabaseMigration(testTableWithOneIndex, withLogs = false)
            statements shouldHaveSize 1
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL
     * );
     * CREATE INDEX test_table_by_name ON TEST_TABLE ("name");
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropUnmappedIndex(testDB: TestDB) {
        testDB.dropAllSequence()

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

        withTables(testDB, testTableWithIndex) {
            testTableWithIndex.exists().shouldBeTrue()

            val statements =
                MigrationUtils.statementsRequiredForDatabaseMigration(testTableWithoutIndex, withLogs = false)
            statements.size shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddAutoIncrementToExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withTables(testDB, tableWithoutAutoIncrement) {
            MigrationUtils
                .statementsRequiredForDatabaseMigration(
                    tableWithoutAutoIncrement,
                    withLogs = false
                )
                .shouldBeEmpty()

            val statements = MigrationUtils
                .statementsRequiredForDatabaseMigration(
                    tableWithAutoIncrement,
                    withLogs = false
                )

            when (testDB) {
                in TestDB.ALL_POSTGRES -> {
                    statements.size shouldBeEqualTo 3
                    statements[0] shouldBeEqualTo expectedCreateSequenceStatement("test_table_id_seq")
                    statements[1] shouldBeEqualTo "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"
                    statements[2] shouldBeEqualTo "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
                }

                else -> {
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
    fun testAddAutoIncrementWithSequenceNameToExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withTables(testDB, tableWithoutAutoIncrement) {
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
    fun testAddAutoIncrementWithCustomSequenceToExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withTables(testDB, tableWithoutAutoIncrement) {
            if (currentDialectTest.supportsCreateSequence) {
                MigrationUtils.statementsRequiredForDatabaseMigration(
                    tableWithoutAutoIncrement,
                    withLogs = false
                ).shouldBeEmpty()

                val statements = MigrationUtils
                    .statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence,
                        withLogs = false
                    )
                statements.size shouldBeEqualTo 1
                statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
            }
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropAutoIncrementOnExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        val tableWithAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()

            override val primaryKey = PrimaryKey(id)
        }
        val tableWithoutAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, tableWithAutoIncrement) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithAutoIncrement,
                withLogs = false
            ).shouldBeEmpty()

            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                tableWithoutAutoIncrement,
                withLogs = false
            )
            when (testDB) {
                in TestDB.ALL_POSTGRES -> {
                    statements.size shouldBeEqualTo 2
                    statements[0] shouldBeEqualTo "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT, ALTER COLUMN id DROP DEFAULT"
                    statements[1] shouldBeEqualTo expectedDropSequenceStatement("test_table_id_seq")
                }

                else -> {
                    statements.size shouldBeEqualTo 1
                    val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                    statements[0] shouldBeEqualToIgnoringCase "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT"
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddSequenceNameToExistingAutoIncrementColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        val sequenceName = "custom_sequence"
        val tableWithAutoIncrement = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()

            override val primaryKey = PrimaryKey(id)
        }
        val tableWithAutoIncrementSequenceName = object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, tableWithAutoIncrement) {
            if (currentDialectTest.supportsCreateSequence) {
                MigrationUtils
                    .statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    )
                    .shouldBeEmpty()

                val statements = MigrationUtils
                    .statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName,
                        withLogs = false
                    )
                statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)

                when (testDB) {
                    in TestDB.ALL_POSTGRES -> {
                        statements.size shouldBeEqualTo 3
                        statements[1] shouldBeEqualTo
                                "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT, ALTER COLUMN id DROP DEFAULT"
                        statements[2] shouldBeEqualTo
                                expectedDropSequenceStatement("test_table_id_seq")
                    }

                    else -> {
                        val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                        statements[1] shouldStartWithIgnoringCase
                                "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT"
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddCustomSequenceToExistingAutoIncrementColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    // MariaDB does not allow to create auto column without defining it as a key
                    val tableWithAutoIncrement = if (testDB == TestDB.MARIADB) {
                        object: IdTable<Long>("test_table") {
                            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
                            override val primaryKey = PrimaryKey(id)
                        }
                    } else {
                        tableWithAutoIncrement
                    }
                    SchemaUtils.create(tableWithAutoIncrement)

                    val stmts = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    )
                    stmts.shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementCustomSequence,
                        withLogs = false
                    )
                    statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)

                    when (testDB) {
                        in TestDB.ALL_POSTGRES -> {
                            statements shouldHaveSize 3
                            statements[1] shouldBeEqualTo
                                    "ALTER TABLE test_table ALTER COLUMN id TYPE BIGINT, ALTER COLUMN id DROP DEFAULT"
                            statements[2] shouldBeEqualTo
                                    expectedDropSequenceStatement("test_table_id_seq")
                        }
                        else -> {
                            statements.size shouldBeEqualTo 2
                            val alterColumnWord = if (currentDialectTest is MysqlDialect) "MODIFY" else "ALTER"
                            statements[1] shouldStartWithIgnoringCase
                                    "ALTER TABLE test_table $alterColumnWord COLUMN id BIGINT"
                        }
                    }
                } finally {
                    SchemaUtils.drop(tableWithAutoIncrement)
                }
            }
        }
    }

    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS custom_sequence START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (ID BIGINT NOT NULL);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDropAutoIncrementWithSequenceNameOnExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrementSequenceName,
                        withLogs = false
                    ).shouldBeEmpty()

                    val statements = MigrationUtils
                        .statementsRequiredForDatabaseMigration(
                            tableWithoutAutoIncrement,
                            withLogs = false
                        )
                    when (testDB) {
                        in TestDB.ALL_POSTGRES -> {
                            statements.shouldBeEmpty()
                        }
                        TestDB.H2 -> {
                            statements.size shouldBeEqualTo 1
                        }

                        else -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualToIgnoringCase
                                    expectedDropSequenceStatement(sequenceName)
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
    fun testDropSequenceNameOnExistingAutoIncrementColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils
                        .statementsRequiredForDatabaseMigration(tableWithAutoIncrementSequenceName)
                        .shouldBeEmpty()

                    val statements = MigrationUtils.statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    )

                    when (testDB) {
                        in TestDB.ALL_POSTGRES -> {
                            statements.forEachIndexed { i, stmt ->
                                log.debug { "stmt[$i]=$stmt" }
                            }
                            statements shouldHaveSize 3
                            statements[0] shouldBeEqualTo
                                    expectedCreateSequenceStatement("test_table_id_seq")
                            statements[1] shouldBeEqualTo
                                    "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"
                            statements[2] shouldBeEqualTo
                                    "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
                        }

                        H2_V1 -> {
                            statements shouldHaveSize 1
                            statements[0] shouldBeEqualTo
                                    "ALTER TABLE TEST_TABLE ALTER COLUMN ID BIGINT AUTO_INCREMENT NOT NULL"
                        }
                        TestDB.MARIADB -> {
                            statements shouldHaveSize 2
                            statements[0] shouldBeEqualTo "ALTER TABLE test_table MODIFY COLUMN id BIGINT AUTO_INCREMENT NOT NULL"
                            statements[1] shouldBeEqualTo expectedDropSequenceStatement(sequenceName)
                        }
                        else -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldStartWithIgnoringCase
                                    "ALTER TABLE TEST_TABLE ALTER COLUMN ID"
                            statements[1] shouldBeEqualToIgnoringCase
                                    expectedDropSequenceStatement(sequenceName)
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
    fun testAddCustomSequenceToExistingAutoIncrementColumnWithSequenceName(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementSequenceName)

                    MigrationUtils
                        .statementsRequiredForDatabaseMigration(
                            tableWithAutoIncrementSequenceName,
                            withLogs = false
                        )
                        .shouldBeEmpty()

                    val statements = MigrationUtils
                        .statementsRequiredForDatabaseMigration(
                            tableWithAutoIncrementCustomSequence,
                            withLogs = false
                        )
                    when (testDB) {
                        H2_V1, in TestDB.ALL_POSTGRES -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequence.name)
                        }

                        else -> {
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
    fun testDropAutoIncrementWithCustomSequenceOnExistingColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
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

                    when (testDB) {
                        H2_V1, in TestDB.ALL_POSTGRES -> {
                            statements.size shouldBeEqualTo 0
                        }

                        else -> {
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
    fun testDropCustomSequenceOnExistingAutoIncrementColumn(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (!currentDialectTest.supportsCreateSequence) {
                log.debug { "${currentDialectTest.name} does not support CREATE SEQUENCE" }
                return@withDb
            }
            try {
                SchemaUtils.create(tableWithAutoIncrementCustomSequence)

                SchemaUtils.statementsRequiredToActualizeScheme(tableWithAutoIncrementCustomSequence)
                    .shouldBeEmpty()

                val statements = MigrationUtils
                    .statementsRequiredForDatabaseMigration(
                        tableWithAutoIncrement,
                        withLogs = false
                    )
                when (testDB) {
                    in TestDB.ALL_POSTGRES -> {
                        statements.size shouldBeEqualTo 3
                        statements[0] shouldBeEqualTo
                                expectedCreateSequenceStatement("test_table_id_seq")
                        statements[1] shouldBeEqualTo
                                "ALTER TABLE test_table ALTER COLUMN id SET DEFAULT nextval('test_table_id_seq')"
                        statements[2] shouldBeEqualTo
                                "ALTER SEQUENCE test_table_id_seq OWNED BY test_table.id"
//                        statements[3] shouldBeEqualTo
//                                expectedDropSequenceStatement(sequence.name)
                    }

                    H2_V1 -> {
                        statements.size shouldBeEqualTo 1
                        statements[0] shouldBeEqualTo
                                "ALTER TABLE TEST_TABLE ALTER COLUMN ID BIGINT AUTO_INCREMENT NOT NULL"
                    }
                    MARIADB -> {
                        statements shouldHaveSize 2
                        statements[0] shouldBeEqualTo "ALTER TABLE test_table MODIFY COLUMN id BIGINT AUTO_INCREMENT NOT NULL"
                        statements[1] shouldBeEqualTo expectedDropSequenceStatement(sequence.name)
                    }
                    else -> {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAddSequenceNameToExistingAutoIncrementColumnWithCustomSequence(testDB: TestDB) {
        testDB.dropAllSequence()

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.create(tableWithAutoIncrementCustomSequence)

                    MigrationUtils
                        .statementsRequiredForDatabaseMigration(tableWithAutoIncrementCustomSequence)
                        .shouldBeEmpty()

                    val statements = MigrationUtils
                        .statementsRequiredForDatabaseMigration(
                            tableWithAutoIncrementSequenceName,
                            withLogs = false
                        )

                    when (testDB) {
                        H2_V1, in TestDB.ALL_POSTGRES -> {
                            statements.size shouldBeEqualTo 1
                            statements[0] shouldBeEqualTo expectedCreateSequenceStatement(sequenceName)
                        }

                        else -> {
                            statements.size shouldBeEqualTo 2
                            statements[0] shouldBeEqualTo
                                    expectedCreateSequenceStatement(sequenceName)
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
    fun testNumericTypeLiteralsAsDefaultsDoNotTriggerMigrationStatements(testDB: TestDB) {
        testDB.dropAllSequence()

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

        withTables(testDB, tester) {
            MigrationUtils
                .statementsRequiredForDatabaseMigration(
                    tester,
                    withLogs = false
                )
                .shouldBeEmpty()
        }
    }

    private fun expectedCreateSequenceStatement(sequenceName: String) =
        "CREATE SEQUENCE${" IF NOT EXISTS".takeIf { currentDialectTest.supportsIfNotExists } ?: ""} " +
                "$sequenceName START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807"

    private fun expectedDropSequenceStatement(sequenceName: String) =
        "DROP SEQUENCE${" IF EXISTS".takeIf { currentDialectTest.supportsIfNotExists } ?: ""} $sequenceName"

    private val sequence = org.jetbrains.exposed.v1.core.Sequence(
        name = "my_sequence",
        startWith = 1,
        minValue = 1,
        maxValue = 9223372036854775807
    )

    private val sequenceName = "custom_sequence"

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (ID BIGINT NOT NULL)
     * ```
     */
    private val tableWithoutAutoIncrement by lazy {
        object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").entityId()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (ID BIGINT NOT NULL)
     * ```
     */
    private val tableWithAutoIncrement by lazy {
        object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
        }
    }

    private val tableWithAutoIncrementCustomSequence by lazy {
        object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequence).entityId()
        }
    }

    private val tableWithAutoIncrementSequenceName by lazy {
        object: IdTable<Long>("test_table") {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()
        }
    }
}
