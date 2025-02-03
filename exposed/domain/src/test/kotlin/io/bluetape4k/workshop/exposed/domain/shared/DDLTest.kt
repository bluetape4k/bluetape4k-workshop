package io.bluetape4k.workshop.exposed.domain.shared

import MigrationUtils
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.H2
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V8
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainIgnoringCase
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.H2Dialect.H2CompatibilityMode.Oracle
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DDLTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `table exists`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS "testTable" (
         *      id INT PRIMARY KEY,
         *      "name" VARCHAR(42) NOT NULL
         * )
         * ```
         */
        val testTable = object: Table("testTable") {
            val id = integer("id")
            val name = varchar("name", length = 42)

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB) {
            testTable.exists().shouldBeFalse()
        }

        withTables(testDB, testTable) {
            testTable.exists().shouldBeTrue()
        }
    }

    @Test
    fun `keywoard identifiers with opt out`() {
        val keywords = listOf("Integer", "name")
        val tester = object: Table(keywords[0]) {
            val name = varchar(keywords[1], length = 32)
        }

        transaction(keywordFlagDB) {
            log.debug { "DB Config preserveKeywordCasing=false" }
            db.config.preserveKeywordCasing.shouldBeFalse()

            tester.exists().shouldBeFalse()

            SchemaUtils.create(tester)
            tester.exists().shouldBeTrue()

            val (tableName, columnName) = keywords.map { "\"${it.uppercase()}\"" }

            val expectedCreate = "CREATE TABLE ${addIfNotExistsIfSupported()}$tableName (" +
                    "$columnName ${tester.name.columnType.sqlType()} NOT NULL)"
            tester.ddl.single() shouldBeEqualTo expectedCreate

            // check that insert and select statement identifiers also match in DB without throwing SQLException
            tester.insert { it[name] = "A" }

            val expectedSelect = "SELECT $tableName.$columnName FROM $tableName"
            tester.selectAll().also {
                it.prepareSQL(this, prepared = false) shouldBeEqualTo expectedSelect
            }

            // check that identifiers match with returned jdbc metadata
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(tester, withLogs = false)
            statements.isEmpty().shouldBeTrue()

            SchemaUtils.drop(tester)
        }

        TransactionManager.closeAndUnregister(keywordFlagDB)
    }

    private val keywordFlagDB by lazy {
        Database.connect(
            url = "jdbc:h2:mem:keywordFlagDB;DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
            user = "root",
            password = "",
            databaseConfig = DatabaseConfig {
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = false
            }
        )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `keyword identifiers without opt out`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS "data" (
         *      "public" BOOLEAN NOT NULL,
         *      "key" INT NOT NULL,
         *      "constraint" VARCHAR(32) NOT NULL
         * )
         * ```
         */
        val keywords = listOf("data", "public", "key", "constraint")
        val keywordTable = object: Table(keywords[0]) {
            val public = bool(keywords[1])
            val data = integer(keywords[2])
            val constraint = varchar(keywords[3], length = 32)
        }

        withDb(testDB) {
            db.config.preserveKeywordCasing.shouldBeTrue()

            SchemaUtils.create(keywordTable)
            keywordTable.exists().shouldBeTrue()

            val (tableName, publicName, dataName, constraintName) = keywords.map {
                when (currentDialectTest) {
                    is MysqlDialect -> "`$it`"
                    else -> "\"$it\""
                }
            }

            val expectedCreate = "CREATE TABLE ${addIfNotExistsIfSupported()}$tableName (" +
                    "$publicName ${keywordTable.public.columnType.sqlType()} NOT NULL, " +
                    "$dataName ${keywordTable.data.columnType.sqlType()} NOT NULL, " +
                    "$constraintName ${keywordTable.constraint.columnType.sqlType()} NOT NULL)"

            keywordTable.ddl.single() shouldBeEqualTo expectedCreate

            // check that insert and select statement identifiers also match in DB without throwing SQLException
            keywordTable.insert {
                it[public] = true
                it[data] = 999
                it[constraint] = "unique"
            }

            val expectedSelect =
                "SELECT $tableName.$publicName, $tableName.$dataName, $tableName.$constraintName FROM $tableName"
            keywordTable.selectAll().also {
                it.prepareSQL(this, prepared = false) shouldBeEqualTo expectedSelect
            }

            // check that identifiers match with returned jdbc metadata
            val statements = MigrationUtils.statementsRequiredForDatabaseMigration(keywordTable, withLogs = false)
            statements.shouldBeEmpty()

            SchemaUtils.drop(keywordTable)
        }
    }

    /**
     * Placed outside test function to shorten generated name
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS "unnamedtable$1" (
     *      id INT PRIMARY KEY,
     *      "name" VARCHAR(42) NOT NULL
     * );
     * ```
     */
    val unnamedTable = object: Table() {
        val id = integer("id")
        val name = varchar("name", 42)

        override val primaryKey = PrimaryKey(id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unnamed table with quoted SQL`(testDB: TestDB) {
        withTables(testDB, unnamedTable) {
            val q = db.identifierManager.quoteString

            // MySQL V8 테이블 명에는 back-quote(`) 를 사용하지 않네요.
            val tableName = if (currentDialectTest.needsQuotesWhenSymbolsInNames && testDB != MYSQL_V8) {
                "$q${"unnamedTable$1".inProperCase()}$q"
            } else {
                "unnamedTable$1".inProperCase()
            }
            log.debug { "Table Name: $tableName" }

            val integerType = currentDialectTest.dataTypeProvider.integerType()
            val varCharType = currentDialectTest.dataTypeProvider.varcharType(42)

            val expectedDDL = "CREATE TABLE " + addIfNotExistsIfSupported() + "$tableName " +
                    "(${"id".inProperCase()} $integerType PRIMARY KEY," +
                    " $q${"name".inProperCase()}$q $varCharType NOT NULL)"

            val unnamedTableDDL = unnamedTable.ddl.single()
            log.debug { "DDL: $unnamedTableDDL" }

            unnamedTableDDL shouldBeEqualTo expectedDDL
        }
    }


    @Test
    fun `namedEmptyTable without quotes SQL`() {
        val testTable = object: Table("test_named_table") {}
        withDb(H2) {
            testTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE IF NOT EXISTS ${"test_named_table".inProperCase()}"
        }
    }

    fun `table with different column types SQL 01`() {
        /**
         * H2:
         * ```sql
         * CREATE TABLE IF NOT EXISTS DIFFERENT_COLUMN_TYPES (
         *      ID INT AUTO_INCREMENT NOT NULL,
         *      "name" VARCHAR(42) PRIMARY KEY,
         *      AGE INT NULL
         * )
         * ```
         */
        val testTable = object: Table("different_column_types") {
            val id = integer("id").autoIncrement()
            val name = varchar("name", 42)
            val age = integer("age").nullable()

            override val primaryKey = PrimaryKey(name)
        }

        withTables(TestDB.H2, testTable) {
            val varCharType = currentDialectTest.dataTypeProvider.varcharType(42)

            log.debug { "DDL: ${testTable.ddl.single()}" }

            testTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + "${"different_column_types".inProperCase()} " +
                    "(${"id".inProperCase()} ${currentDialectTest.dataTypeProvider.integerAutoincType()} NOT NULL, " +
                    "\"${"name".inProperCase()}\" $varCharType PRIMARY KEY, " +
                    "${"age".inProperCase()} ${currentDialectTest.dataTypeProvider.integerType()} NULL)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `table with different column types SQL 02`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 }
        /**
         * Postgres:
         * ```sql
         * CREATE TABLE IF NOT EXISTS with_different_column_types (
         *      id INT,
         *      "name" VARCHAR(42),
         *      age INT NULL,
         *
         *      CONSTRAINT pk_with_different_column_types PRIMARY KEY (id, "name")
         * )
         * ```
         */
        val testTable = object: Table("with_different_column_types") {
            val id = integer("id")
            val name = varchar("name", 42)
            val age = integer("age").nullable()

            override val primaryKey = PrimaryKey(id, name)
        }

        withTables(testDB, testTable) {
            val q = db.identifierManager.quoteString
            val varCharType = currentDialectTest.dataTypeProvider.varcharType(42)
            val tableDescription =
                "CREATE TABLE " + addIfNotExistsIfSupported() + "with_different_column_types".inProperCase()
            val idDescription = "${"id".inProperCase()} ${currentDialectTest.dataTypeProvider.integerType()}"
            val nameDescription = "$q${"name".inProperCase()}$q $varCharType"
            val ageDescription = "${"age".inProperCase()} ${db.dialect.dataTypeProvider.integerType()} NULL"
            val primaryKeyConstraint =
                "CONSTRAINT pk_with_different_column_types PRIMARY KEY (${"id".inProperCase()}, $q${"name".inProperCase()}$q)"

            log.debug { "DDL: ${testTable.ddl.single()}" }

            testTable.ddl.single() shouldBeEqualTo
                    "$tableDescription ($idDescription, $nameDescription, $ageDescription, $primaryKeyConstraint)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `auto increment on unsigned columns`(testDB: TestDB) {
        // separate tables are necessary as some db only allow a single column to be auto-incrementing
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS u_int_tester (
         *      id BIGSERIAL PRIMARY KEY,
         *
         *      CONSTRAINT chk_u_int_tester_unsigned_id
         *          CHECK (id BETWEEN 0 AND 4294967295)
         * )
         * ```
         */
        val uIntTester = object: Table("u_int_tester") {
            val id = uinteger("id").autoIncrement()
            override val primaryKey = PrimaryKey(id)
        }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS u_long_tester (
         *      id BIGSERIAL PRIMARY KEY
         * )
         * ```
         */
        val uLongTester = object: Table("u_long_tester") {
            val id = ulong("id").autoIncrement()
            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, uIntTester, uLongTester) {
            uIntTester.insert { }
            uIntTester.selectAll().single()[uIntTester.id] shouldBeEqualTo 1u

            uLongTester.insert { }
            uLongTester.selectAll().single()[uLongTester.id] shouldBeEqualTo 1uL
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `table with multi PK and auto increment`(testDB: TestDB) {
        /**
         * Postgres:
         * ```sql
         * CREATE TABLE IF NOT EXISTS footable (
         *      bar INT,
         *      id BIGSERIAL,
         *      CONSTRAINT pk_FooTable PRIMARY KEY (id, bar)
         * );
         * ```
         */
        val foo = object: IdTable<Long>("FooTable") {
            val bar = integer("bar")
            override val id: Column<EntityID<Long>> = long("id").entityId().autoIncrement()

            override val primaryKey = PrimaryKey(bar, id)
        }

        withTables(testDB, foo) {
            foo.insert {
                it[bar] = 1
            }
            foo.insert {
                it[bar] = 2
            }

            val result = foo.selectAll().map { it[foo.id] to it[foo.bar] }
            result shouldHaveSize 2
            result[0].second shouldBeEqualTo 1
            result[1].second shouldBeEqualTo 2
        }
    }

    @Test
    fun `primary key on text column in H2`() {
        /**
         * H2:
         * ```sql
         * CREATE TABLE IF NOT EXISTS TEXT_PK_TABLE (
         *      COLUMN_1 TEXT PRIMARY KEY
         * );
         * ```
         */
        val testTable = object: Table("text_pk_table") {
            val column1 = text("column_1")

            override val primaryKey = PrimaryKey(column1)
        }

        withDb(TestDB.H2) {
            val h2Dialect = currentDialectTest as H2Dialect
            val isOracleMode = h2Dialect.h2Mode == Oracle
            val singleColumnDescription = testTable.columns.single().descriptionDdl(false)

            singleColumnDescription shouldContainIgnoringCase "PRIMARY KEY"

            if (h2Dialect.isSecondVersion && !isOracleMode) {
                SchemaUtils.create(testTable)
                SchemaUtils.drop(testTable)
            } else {
                expectException<ExposedSQLException> {
                    SchemaUtils.create(testTable)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `indices 01`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t1 (
         *      id INT PRIMARY KEY,
         *      "name" VARCHAR(255) NOT NULL
         * );
         * CREATE INDEX t1_name ON t1 ("name");
         */
        val t = object: Table("t1") {
            val id = integer("id")
            val name = varchar("name", 255).index()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, t) {
            val alter = SchemaUtils.createIndex(t.indices.first()).single()
            val q = db.identifierManager.quoteString

            log.debug { "Alter: $alter" }

            alter shouldBeEqualTo
                    "CREATE INDEX ${"t1_name".inProperCase()} ON ${"t1".inProperCase()} ($q${"name".inProperCase()}$q)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `indices 02`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t2 (
         *      id INT PRIMARY KEY,
         *      lvalue INT NOT NULL,
         *      rvalue INT NOT NULL,
         *      "name" VARCHAR(255) NOT NULL
         * );
         *
         * CREATE INDEX t2_name ON t2 ("name");
         * CREATE INDEX t2_lvalue_rvalue ON t2 (lvalue, rvalue);
         */
        val t = object: Table("t2") {
            val id = integer("id")
            val lvalue = integer("lvalue")
            val rvalue = integer("rvalue")
            val name = varchar("name", 255).index()

            override val primaryKey = PrimaryKey(id)

            init {
                index(false, lvalue, rvalue)
            }
        }

        withTables(testDB, t) {
            val q = db.identifierManager.quoteString

            val a1 = SchemaUtils.createIndex(t.indices[0]).single()
            log.debug { "Alter 1: $a1" }
            a1 shouldBeEqualTo
                    "CREATE INDEX ${"t2_name".inProperCase()} ON ${"t2".inProperCase()} ($q${"name".inProperCase()}$q)"

            val a2 = SchemaUtils.createIndex(t.indices[1]).single()
            log.debug { "Alter 2: $a2" }
            a2 shouldBeEqualTo
                    "CREATE INDEX ${"t2_lvalue_rvalue".inProperCase()} ON ${"t2".inProperCase()} " +
                    "(${"lvalue".inProperCase()}, ${"rvalue".inProperCase()})"
        }
    }

    @Test
    fun `index on text column in H2`() {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS TEST_INDEX_TABLE (COLUMN_1 TEXT NOT NULL);
         * CREATE INDEX TEST_INDEX_TABLE_COLUMN_1 ON TEST_INDEX_TABLE (COLUMN_1);
         * ```
         */
        val testTable = object: Table("test_index_table") {
            val column1 = text("column_1")

            init {
                index(false, column1)
            }
        }

        withDb(TestDB.H2) {
            val h2Dialect = currentDialectTest as H2Dialect
            val isOracleMode = h2Dialect.h2Mode == Oracle
            val tableProperName = testTable.tableName.inProperCase()
            val columnProperName = testTable.columns.first().name.inProperCase()
            val indexProperName = "${tableProperName}_${columnProperName}"

            val indexStatement = SchemaUtils.createIndex(testTable.indices.single()).single()

            log.debug { "DDL: ${testTable.ddl.single()}" }

            testTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + tableProperName +
                    " (" + testTable.columns.single().descriptionDdl(false) + ")"

            if (h2Dialect.isSecondVersion && !isOracleMode) {
                log.debug { "Index: $indexStatement" }
                indexStatement shouldBeEqualTo
                        "CREATE INDEX $indexProperName ON $tableProperName ($columnProperName)"
            } else {
                indexStatement.shouldBeEmpty()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unique indices 01`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t1 (
         *      id INT PRIMARY KEY,
         *      "name" VARCHAR(255) NOT NULL
         * );
         *
         * ALTER TABLE t1 ADD CONSTRAINT t1_name_unique UNIQUE ("name");
         * ```
         */
        val t = object: Table("t1") {
            val id = integer("id")
            val name = varchar("name", 255).uniqueIndex()

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, t) {
            val q = db.identifierManager.quoteString
            val alter = SchemaUtils.createIndex(t.indices[0]).single()

            log.debug { "Alter: $alter" }

            alter shouldBeEqualTo
                    "ALTER TABLE ${"t1".inProperCase()} ADD CONSTRAINT ${"t1_name_unique".inProperCase()} " +
                    "UNIQUE ($q${"name".inProperCase()}$q)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unique indices custom name`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t1 (
         *      id INT PRIMARY KEY,
         *      "name" VARCHAR(255) NOT NULL
         * );
         *
         * ALTER TABLE t1 ADD CONSTRAINT U_T1_NAME UNIQUE ("name");
         * ```
         */
        val t = object: Table("t1") {
            val id = integer("id")
            val name = varchar("name", 255).uniqueIndex("U_T1_NAME")

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, t) {
            val q = db.identifierManager.quoteString
            val alter = SchemaUtils.createIndex(t.indices[0]).single()

            log.debug { "Alter: $alter" }

            alter shouldBeEqualTo
                    "ALTER TABLE ${"t1".inProperCase()} ADD CONSTRAINT ${"U_T1_NAME"} " +
                    "UNIQUE ($q${"name".inProperCase()}$q)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multi column index`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t1 ("type" VARCHAR(255) NOT NULL, "name" VARCHAR(255) NOT NULL);
         *
         * CREATE INDEX t1_name_type ON t1 ("name", "type");
         * ALTER TABLE t1
         *      ADD CONSTRAINT t1_type_name_unique UNIQUE ("type", "name");
         */
        val t = object: Table("t1") {
            val type = varchar("type", 255)
            val name = varchar("name", 255)

            init {
                index(false, name, type)
                uniqueIndex(type, name)
            }
        }

        withTables(testDB, t) {
            val q = db.identifierManager.quoteString
            val indexAlter = SchemaUtils.createIndex(t.indices[0]).single()
            val uniqueAlter = SchemaUtils.createIndex(t.indices[1]).single()

            log.debug { "Index Alter: $indexAlter" }
            log.debug { "Unique Alter: $uniqueAlter" }

            indexAlter shouldBeEqualTo
                    "CREATE INDEX ${"t1_name_type".inProperCase()} ON ${"t1".inProperCase()} " +
                    "($q${"name".inProperCase()}$q, $q${"type".inProperCase()}$q)"

            uniqueAlter shouldBeEqualTo
                    "ALTER TABLE ${"t1".inProperCase()} ADD CONSTRAINT ${"t1_type_name_unique".inProperCase()} " +
                    "UNIQUE ($q${"type".inProperCase()}$q, $q${"name".inProperCase()}$q)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `multi column index custom name`(testDB: TestDB) {
        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS t1 (
         *      "type" VARCHAR(255) NOT NULL,
         *      "name" VARCHAR(255) NOT NULL
         * );
         *
         * CREATE INDEX I_T1_NAME_TYPE ON t1 ("name", "type");
         *
         *  ALTER TABLE t1
         *      ADD CONSTRAINT U_T1_TYPE_NAME UNIQUE ("type", "name")
         * ```
         */
        val t = object: Table("t1") {
            val type = varchar("type", 255)
            val name = varchar("name", 255)

            init {
                index("I_T1_NAME_TYPE", false, name, type)
                uniqueIndex("U_T1_TYPE_NAME", type, name)
            }
        }

        withTables(testDB, t) {
            val q = db.identifierManager.quoteString
            val indexAlter = SchemaUtils.createIndex(t.indices[0]).single()
            val uniqueAlter = SchemaUtils.createIndex(t.indices[1]).single()

            log.debug { "Index Alter: $indexAlter" }
            log.debug { "Unique Alter: $uniqueAlter" }

            indexAlter shouldBeEqualTo
                    "CREATE INDEX ${"I_T1_NAME_TYPE"} ON ${"t1".inProperCase()} " +
                    "($q${"name".inProperCase()}$q, $q${"type".inProperCase()}$q)"

            uniqueAlter shouldBeEqualTo
                    "ALTER TABLE ${"t1".inProperCase()} ADD CONSTRAINT ${"U_T1_TYPE_NAME"} " +
                    "UNIQUE ($q${"type".inProperCase()}$q, $q${"name".inProperCase()}$q)"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `binary without length`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in (TestDB.ALL_H2 - TestDB.H2_MYSQL + TestDB.ALL_POSTGRES) }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS tablewithbinary (
         *      "binaryColumn" bytea NOT NULL
         * );
         * ```
         */
        val tableWithBinary = object: Table("TableWithBinary") {
            val binaryColumn = binary("binaryColumn")
        }

        fun SizedIterable<ResultRow>.readAsString() = map { String(it[tableWithBinary.binaryColumn]) }

        val exposedBytes = "Exposed".toByteArray()
        val kotlinBytes = "Kotlin".toByteArray()

        withTables(testDB, tableWithBinary) {
            tableWithBinary.insert {
                it[tableWithBinary.binaryColumn] = exposedBytes
            }
            val insertedExposed = tableWithBinary.selectAll().readAsString().single()
            insertedExposed shouldBeEqualTo "Exposed"

            tableWithBinary.insert {
                it[tableWithBinary.binaryColumn] = kotlinBytes
            }

            tableWithBinary.selectAll().readAsString() shouldBeEqualTo listOf("Exposed", "Kotlin")

            val insertedKotlin = tableWithBinary.selectAll()
                .where { tableWithBinary.binaryColumn eq kotlinBytes }
                .readAsString()

            insertedKotlin shouldBeEqualTo listOf("Kotlin")
        }
    }

    // TODO: Add more tests
}
