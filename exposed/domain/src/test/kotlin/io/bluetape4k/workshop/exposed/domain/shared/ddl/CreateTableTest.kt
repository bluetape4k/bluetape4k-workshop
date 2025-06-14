package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.shared.Category
import io.bluetape4k.workshop.exposed.domain.shared.Item
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.autoIncColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.OracleDialect
import org.jetbrains.exposed.v1.core.vendors.SQLServerDialect
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.name
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import kotlin.test.assertFails

class CreateTableTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create table with duplicated column`(testDB: TestDB) {
        val assertionFailureMessage = "Can't create a table with multiple columns having the same name"

        withDb(testDB) {
            assertFails(assertionFailureMessage) {
                SchemaUtils.create(TableWithDuplicatedColumn)
            }
            assertFails(assertionFailureMessage) {
                SchemaUtils.create(TableDuplicatedColumnReferenceToIntIdTable)
            }
            assertFails(assertionFailureMessage) {
                SchemaUtils.create(TableDuplicatedColumnReferToTable)
            }
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      COLUMN_1 VARCHAR(30) PRIMARY KEY
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create id table with primary key by EntityID`(testDB: TestDB) {
        val tester = object: IdTable<String>("test_table") {
            val column1 = varchar("column_1", 30)
            override val id = column1.entityId()

            override val primaryKey = PrimaryKey(id)
        }

        withDb(testDB) {
            val singleColumnDescription = tester.columns.single().descriptionDdl(false)

            singleColumnDescription shouldContain "PRIMARY KEY"

            // CREATE TABLE IF NOT EXISTS TEST_TABLE (COLUMN_1 VARCHAR(30) PRIMARY KEY)
            log.debug { "DDL: ${tester.ddl.single()}" }
            tester.ddl.single() shouldBeEqualTo
                    "CREATE TABLE ${addIfNotExistsIfSupported()}${tester.tableName.inProperCase()} (${singleColumnDescription})"
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      COLUMN_1 VARCHAR(30) PRIMARY KEY
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create id table with primary key by column`(testDB: TestDB) {
        val tester = object: IdTable<String>("test_table") {
            val column1 = varchar("column_1", 30)
            override val id = column1.entityId()

            override val primaryKey = PrimaryKey(column1)
        }

        withDb(testDB) {
            val singleColumnDescription = tester.columns.single().descriptionDdl(false)

            singleColumnDescription shouldContain "PRIMARY KEY"

            // CREATE TABLE IF NOT EXISTS TEST_TABLE (COLUMN_1 VARCHAR(30) PRIMARY KEY)
            log.debug { "DDL: ${tester.ddl.single()}" }
            tester.ddl.single() shouldBeEqualTo
                    "CREATE TABLE ${addIfNotExistsIfSupported()}${tester.tableName.inProperCase()} (${singleColumnDescription})"
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS TEST_TABLE (
     *      COLUMN_1 VARCHAR(30),
     *
     *      CONSTRAINT PK_Constraint_name PRIMARY KEY (COLUMN_1)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create id table with named primary key by column`(testDB: TestDB) {
        val pkConstraintName = "PK_Constraint_name"
        val testTable = object: IdTable<String>("test_table") {
            val column1 = varchar("column_1", 30)
            override val id = column1.entityId()

            override val primaryKey = PrimaryKey(column1, name = pkConstraintName)
        }

        withDb(testDB) {
            val singleColumn = testTable.columns.single()

            log.debug { "DDL: ${testTable.ddl.single()}" }
            // CREATE TABLE IF NOT EXISTS TEST_TABLE (COLUMN_1 VARCHAR(30), CONSTRAINT PK_Constraint_name PRIMARY KEY (COLUMN_1))
            testTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + testTable.tableName.inProperCase() + " (" +
                    "${singleColumn.descriptionDdl(false)}, " +
                    "CONSTRAINT $pkConstraintName PRIMARY KEY (${singleColumn.name.inProperCase()})" +
                    ")"
        }
    }

    /**
     * String primary key Table
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS STRING_PK_TABLE (COLUMN_1 VARCHAR(30) PRIMARY KEY)
     * ```
     *
     * Int primary key Table
     * ```sql
     * CREATE TABLE IF NOT EXISTS INT_PK_TABLE (COLUMN_1 INT PRIMARY KEY)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create table with single column primary key`(testDB: TestDB) {
        val stringPKTable = object: Table("string_pk_table") {
            val column1 = varchar("column_1", 30)

            override val primaryKey = PrimaryKey(column1)
        }
        val intPKTable = object: Table("int_pk_table") {
            val column1 = integer("column_1")

            override val primaryKey = PrimaryKey(column1)
        }

        withDb(testDB) {
            val stringColumnDescription = stringPKTable.columns.single().descriptionDdl(false)
            val intColumnDescription = intPKTable.columns.single().descriptionDdl(false)

            stringColumnDescription shouldContain "PRIMARY KEY"
            intColumnDescription shouldContain "PRIMARY KEY"

            log.debug { "stringPK DDL: ${stringPKTable.ddl.single()}" }
            stringPKTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + stringPKTable.tableName.inProperCase() +
                    " (" + stringColumnDescription + ")"

            log.debug { "intPK DDL: ${intPKTable.ddl.single()}" }
            intPKTable.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + intPKTable.tableName.inProperCase() +
                    " (" + intColumnDescription + ")"
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS ACCOUNT (
     *      ID1 INT,
     *      ID2 INT,
     *
     *      CONSTRAINT pk_Account PRIMARY KEY (ID1, ID2)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `primary key create table`(testDB: TestDB) {
        val account = object: Table("Account") {
            val id1 = integer("id1")
            val id2 = integer("id2")

            override val primaryKey = PrimaryKey(id1, id2)
        }

        withDb(testDB) {
            val id1ProperName = account.id1.name.inProperCase()
            val id2ProperName = account.id2.name.inProperCase()
            val tableName = account.tableName

            log.debug { "Account DDL: ${account.ddl.single()}" }
            account.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + "${tableName.inProperCase()} (" +
                    "${account.columns.joinToString { it.descriptionDdl(false) }}, " +
                    "CONSTRAINT pk_$tableName PRIMARY KEY ($id1ProperName, $id2ProperName)" +
                    ")"
        }
    }

    /**
     * Person Table
     * ```sql
     * CREATE TABLE IF NOT EXISTS PERSON (
     *     ID1 INT,
     *     ID2 INT,
     *     CONSTRAINT PKConstraintName PRIMARY KEY (ID1, ID2)
     * )
     * ```
     *
     * User Table
     * ```sql
     * CREATE TABLE IF NOT EXISTS "User" (
     *     USER_NAME VARCHAR(25),
     *     CONSTRAINT PKConstraintName PRIMARY KEY (USER_NAME)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `primary key with constraint name create table`(testDB: TestDB) {
        val pkConstraintName = "PKConstraintName"

        // Table with composite primary key
        withDb(testDB) {
            val id1ProperName = Person.id1.name.inProperCase()
            val id2ProperName = Person.id2.name.inProperCase()
            val tableName = Person.tableName

            log.debug { "Person DDL: ${Person.ddl.single()}" }
            Person.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + "${tableName.inProperCase()} (" +
                    "${Person.columns.joinToString { it.descriptionDdl(false) }}, " +
                    "CONSTRAINT $pkConstraintName PRIMARY KEY ($id1ProperName, $id2ProperName)" +
                    ")"
        }

        // Table with single column in primary key.
        val user = object: Table("User") {
            val user_name = varchar("user_name", 25)

            override val primaryKey = PrimaryKey(user_name, name = pkConstraintName)
        }
        withDb(testDB) {
            val userNameProperName = user.user_name.name.inProperCase()
            val tableName = TransactionManager.current().identity(user)

            log.debug { "User DDL: ${user.ddl.single()}" }
            // Must generate primary key constraint, because the constraint name was defined.
            user.ddl.single() shouldBeEqualTo
                    "CREATE TABLE " + addIfNotExistsIfSupported() + "$tableName (" +
                    "${user.columns.joinToString { it.descriptionDdl(false) }}, " +
                    "CONSTRAINT $pkConstraintName PRIMARY KEY ($userNameProperName)" +
                    ")"
        }
    }

    /**
     *
     * ddlId1
     * ```sql
     * ALTER TABLE PERSON ADD ID1 INT
     * ```
     *
     * ddlId2
     * ```sql
     * ALTER TABLE PERSON ADD ID2 INT
     * ```
     *
     * Person.id2.ddl
     * ```sql
     * ALTER TABLE PERSON ADD CONSTRAINT PKConstraintName PRIMARY KEY (ID1, ID2)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `add Composite PrimaryKey To Table`(testDB: TestDB) {
        withDb(testDB) {
            val tableName = Person.tableName
            val tableProperName = tableName.inProperCase()
            val id1ProperName = Person.id1.name.inProperCase()
            val ddlId1 = Person.id1.ddl
            val id2ProperName = Person.id2.name.inProperCase()
            val ddlId2 = Person.id2.ddl
            val pkConstraintName = Person.primaryKey.name

            log.debug { "ddlId1: ${ddlId1.first()}" }
            ddlId1.size shouldBeEqualTo 1
            ddlId1.first() shouldBeEqualTo "ALTER TABLE $tableProperName ADD ${Person.id1.descriptionDdl(false)}"

            when (testDB) {
                in TestDB.ALL_H2 -> {
                    log.debug { "ddlId2: ${ddlId2.first()}" }
                    log.debug { "Person.id2.ddl: ${Person.id2.ddl.last()}" }
                    ddlId2 shouldHaveSize 2
                    ddlId2.first() shouldBeEqualTo "ALTER TABLE $tableProperName ADD $id2ProperName ${Person.id2.columnType.sqlType()}"
                    Person.id2.ddl.last() shouldBeEqualTo "ALTER TABLE $tableProperName ADD CONSTRAINT $pkConstraintName PRIMARY KEY ($id1ProperName, $id2ProperName)"
                }

                else -> {
                    log.debug { "ddlId2: ${ddlId2.first()}" }
                    ddlId2 shouldHaveSize 1
                    ddlId2.first() shouldBeEqualTo
                            "ALTER TABLE $tableProperName ADD ${Person.id2.descriptionDdl(false)}, " +
                            "ADD CONSTRAINT $pkConstraintName PRIMARY KEY ($id1ProperName, $id2ProperName)"
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `add One Column PrimaryKey To Table`(testDB: TestDB) {
        withTables(testDB, Book) {
            val tableProperName = Book.tableName.inProperCase()
            val pkConstraintName = Book.primaryKey.name
            val id1ProperName = Book.id.name.inProperCase()
            val ddlId1 = Book.id.ddl

            when (testDB) {
                in TestDB.ALL_H2 ->
                    ddlId1 shouldBeEqualTo
                            listOf(
                                "ALTER TABLE $tableProperName ADD ${Book.id.descriptionDdl(false)}",
                                "ALTER TABLE $tableProperName ADD CONSTRAINT $pkConstraintName PRIMARY KEY ($id1ProperName)"
                            )

                else ->
                    ddlId1.first() shouldBeEqualTo
                            "ALTER TABLE $tableProperName ADD ${Book.id.descriptionDdl(false)}, " +
                            "ADD CONSTRAINT $pkConstraintName PRIMARY KEY ($id1ProperName)"
            }
        }
    }

    object Book: Table("Book") {
        val id = integer("id").autoIncrement()

        override val primaryKey = PrimaryKey(id, name = "PKConstraintName")
    }

    object Person: Table("Person") {
        val id1 = integer("id1")
        val id2 = integer("id2")

        override val primaryKey = PrimaryKey(id1, id2, name = "PKConstraintName")
    }

    object TableWithDuplicatedColumn: Table("myTable") {
        val id1 = integer("id")
        val id2 = integer("id")
    }

    object IDTable: IntIdTable("IntIdTable")

    object TableDuplicatedColumnReferenceToIntIdTable: IntIdTable("myTable") {
        val reference = reference("id", IDTable)
    }

    object TableDuplicatedColumnReferToTable: Table("myTable") {
        val reference = reference("id", TableWithDuplicatedColumn.id1)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD1 (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID BIGINT NOT NULL,
     *      CONSTRAINT MYFOREIGNKEY1 FOREIGN KEY (PARENT_ID) REFERENCES PARENT1(ID)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit Foreign Key Name1`(testDB: TestDB) {
        val fkName = "MyForeignKey1"
        val parent = object: LongIdTable("parent1") {}
        val child = object: LongIdTable("child1") {
            val parentId = reference(
                name = "parent_id",
                foreign = parent,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
                fkName = fkName
            )
        }
        withDb(testDB) {
            val t = TransactionManager.current()
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.parentId)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.id)})" +
                        ")"
            )
            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS "Child" (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID BIGINT NOT NULL,
     *      CONSTRAINT FK_CHILD_PARENT_ID__ID FOREIGN KEY (PARENT_ID) REFERENCES "Parent"(ID)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Quotes`(testDB: TestDB) {
        val parent = object: LongIdTable("\"Parent\"") {}
        val child = object: LongIdTable("\"Child\"") {
            val parentId = reference(
                name = "parent_id",
                foreign = parent,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
            )
        }
        withDb(testDB) {
            val expected = "CREATE TABLE " + addIfNotExistsIfSupported() + "${this.identity(child)} (" +
                    "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                    " CONSTRAINT ${"fk_Child_parent_id__id".inProperCase()}" +
                    " FOREIGN KEY (${this.identity(child.parentId)})" +
                    " REFERENCES ${this.identity(parent)}(${this.identity(parent.id)})" +
                    ")"

            log.debug { "DDL: ${child.ddl.last()}" }
            child.ddl.last() shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS "'CHILD2'" (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID BIGINT NOT NULL,
     *
     *      CONSTRAINT FK_CHILD2_PARENT_ID__ID FOREIGN KEY (PARENT_ID) REFERENCES "'PARENT2'"(ID)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Single Quotes`(testDB: TestDB) {
        val parent = object: LongIdTable("'Parent2'") {}
        val child = object: LongIdTable("'Child2'") {
            val parentId = reference(
                name = "parent_id",
                foreign = parent,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
            )
        }
        withDb(testDB) {
            val expected = "CREATE TABLE " + addIfNotExistsIfSupported() + "${this.identity(child)} (" +
                    "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                    " CONSTRAINT ${"fk_Child2_parent_id__id".inProperCase()}" +
                    " FOREIGN KEY (${this.identity(child.parentId)})" +
                    " REFERENCES ${this.identity(parent)}(${this.identity(parent.id)})" +
                    ")"
            log.debug { "DDL: ${child.ddl.last()}" }
            child.ddl.last() shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD2 (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID UUID NOT NULL,
     *
     *      CONSTRAINT MYFOREIGNKEY2 FOREIGN KEY (PARENT_ID)
     *          REFERENCES PARENT2("uniqueId"))
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit ForeignKey Name2`(testDB: TestDB) {
        val fkName = "MyForeignKey2"
        val parent = object: LongIdTable("parent2") {
            val uniqueId = uuid("uniqueId").clientDefault { UUID.randomUUID() }.uniqueIndex()
        }
        val child = object: LongIdTable("child2") {
            val parentId = reference(
                name = "parent_id",
                refColumn = parent.uniqueId,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
                fkName = fkName
            )
        }
        withDb(testDB) {
            val t = TransactionManager.current()
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.parentId)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.uniqueId)})" +
                        ")"
            )

            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD3 (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID BIGINT NULL,
     *      CONSTRAINT MYFOREIGNKEY3 FOREIGN KEY (PARENT_ID) REFERENCES PARENT3(ID)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit Foreign Key Name3`(testDB: TestDB) {
        val fkName = "MyForeignKey3"
        val parent = object: LongIdTable("parent3") {}
        val child = object: LongIdTable("child3") {
            val parentId = optReference(
                name = "parent_id",
                foreign = parent,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
                fkName = fkName
            )
        }

        withDb(testDB) {
            val t = TransactionManager.current()
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.parentId)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.id)})" +
                        ")"
            )

            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }

    /**
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD4 (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      PARENT_ID UUID NULL,
     *
     *      CONSTRAINT MYFOREIGNKEY4 FOREIGN KEY (PARENT_ID)
     *          REFERENCES PARENT4("uniqueId")
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit ForeignKey Name4`(testDB: TestDB) {
        val fkName = "MyForeignKey4"
        val parent = object: LongIdTable() {
            override val tableName get() = "parent4"
            val uniqueId = uuid("uniqueId").clientDefault { UUID.randomUUID() }.uniqueIndex()
        }
        val child = object: LongIdTable("child4") {
            val parentId = optReference(
                name = "parent_id",
                refColumn = parent.uniqueId,
                onUpdate = ReferenceOption.NO_ACTION,
                onDelete = ReferenceOption.NO_ACTION,
                fkName = fkName
            )
        }
        withDb(testDB) {
            val t = TransactionManager.current()
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.parentId)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.uniqueId)})" +
                        ")"
            )

            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD1 (
     *      ID_A INT NOT NULL,
     *      ID_B INT NOT NULL,
     *
     *      CONSTRAINT MYFOREIGNKEY1 FOREIGN KEY (ID_A, ID_B)
     *          REFERENCES PARENT1(ID_A, ID_B) ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit Composite ForeignKey Name1`(testDB: TestDB) {
        val fkName = "MyForeignKey1"
        val parent = object: Table("parent1") {
            val idA = integer("id_a")
            val idB = integer("id_b")
            override val primaryKey = PrimaryKey(idA, idB)
        }
        val child = object: Table("child1") {
            val idA = integer("id_a")
            val idB = integer("id_b")

            init {
                foreignKey(
                    idA, idB,
                    target = parent.primaryKey,
                    onUpdate = ReferenceOption.CASCADE,
                    onDelete = ReferenceOption.CASCADE,
                    name = fkName
                )
            }
        }
        withDb(testDB) {
            val t = TransactionManager.current()
            val updateCascadePart = " ON UPDATE CASCADE"
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.idA)}, ${t.identity(child.idB)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.idA)}, ${t.identity(parent.idB)})" +
                        " ON DELETE CASCADE$updateCascadePart" +
                        ")"
            )
            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CHILD2 (
     *      ID_A INT NOT NULL,
     *      ID_B INT NOT NULL,
     *      CONSTRAINT MYFOREIGNKEY2 FOREIGN KEY (ID_A, ID_B) REFERENCES PARENT2(ID_A, ID_B)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With Explicit Composite ForeignKey Name2`(testDB: TestDB) {
        val fkName = "MyForeignKey2"
        val parent = object: Table("parent2") {
            val idA = integer("id_a")
            val idB = integer("id_b")

            init {
                uniqueIndex(idA, idB)
            }
        }
        val child = object: Table("child2") {
            val idA = integer("id_a")
            val idB = integer("id_b")

            init {
                foreignKey(
                    idA to parent.idA, idB to parent.idB,
                    onUpdate = ReferenceOption.NO_ACTION,
                    onDelete = ReferenceOption.NO_ACTION,
                    name = fkName
                )
            }
        }

        withDb(testDB) {
            val t = TransactionManager.current()
            val expected = listOfNotNull(
                child.autoIncColumn?.autoIncColumnType?.sequence?.createStatement()?.single(),
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${t.identity(child)} (" +
                        "${child.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${t.db.identifierManager.cutIfNecessaryAndQuote(fkName).inProperCase()}" +
                        " FOREIGN KEY (${t.identity(child.idA)}, ${t.identity(child.idB)})" +
                        " REFERENCES ${t.identity(parent)}(${t.identity(parent.idA)}, ${t.identity(parent.idB)})" +
                        ")"
            )
            log.debug { "DDL: ${child.ddl}" }
            child.ddl shouldBeEqualTo expected
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create Table With OnDelete Set Default`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL + TestDB.ALL_MARIADB) }

        withDb(testDB) {
            val expected = listOf(
                "CREATE TABLE " + addIfNotExistsIfSupported() + "${this.identity(Item)} (" +
                        "${Item.columns.joinToString { it.descriptionDdl(false) }}," +
                        " CONSTRAINT ${"fk_Item_categoryId__id".inProperCase()}" +
                        " FOREIGN KEY (${this.identity(Item.categoryId)})" +
                        " REFERENCES ${this.identity(Category)}(${this.identity(Category.id)})" +
                        " ON DELETE SET DEFAULT" +
                        ")"
            )

            log.debug { "DDL: ${Item.ddl}" }
            Item.ddl shouldBeEqualTo expected
        }

        withTables(testDB, Category, Item) {
            log.debug { "Item DDL:\n${Item.ddl.first()}" }
            log.debug { "Category DDL:\n${Category.ddl.first()}" }
        }
    }

    object OneTable: IntIdTable("one")
    object OneOneTable: IntIdTable("one.one")

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create table with same name in different schemas`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL + TestDB.ALL_MARIADB) }

        val one = prepareSchemaForTest("one")
        withDb(testDB) {
            OneTable.exists().shouldBeFalse()
            OneOneTable.exists().shouldBeFalse()
            try {
                SchemaUtils.create(OneTable)
                OneTable.exists().shouldBeTrue()
                OneOneTable.exists().shouldBeFalse()

                val schemaPrefixedName = testDB.getDefaultSchemaPrefixedTableName(OneTable.tableName)
                SchemaUtils.listTables().any {
                    it.equals(schemaPrefixedName, ignoreCase = true)
                }.shouldBeTrue()

                SchemaUtils.createSchema(one)
                SchemaUtils.create(OneOneTable)
                OneTable.exists().shouldBeTrue()
                OneOneTable.exists().shouldBeTrue()

                SchemaUtils.listTablesInAllSchemas().any {
                    it.equals(OneOneTable.tableName, ignoreCase = true)
                }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(OneTable, OneOneTable)
                val cascade = true //testDB != TestDB.SQLSERVER
                SchemaUtils.dropSchema(one, cascade = cascade)
            }
        }
    }

    private fun TestDB.getDefaultSchemaPrefixedTableName(tableName: String): String =
        when (io.bluetape4k.workshop.exposed.currentDialectTest) {
            is SQLServerDialect -> "dbo.$tableName"
            is OracleDialect -> "${this.user}.$tableName"
            is MysqlDialect -> "${this.db!!.name}.$tableName"
            is SQLiteDialect -> tableName
            else -> "public.$tableName"
        }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS QUOTEDTABLE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "intColumn" INT NOT NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create table with quoted name with camel case`(testDB: TestDB) {
        val testTable = object: IntIdTable("quotedTable") {
            val int = integer("intColumn")
        }

        withDb(testDB) {
            try {
                SchemaUtils.create(testTable)
                testTable.exists().shouldBeTrue()

                testTable.insert { it[int] = 10 }
                testTable.selectAll().singleOrNull()?.get(testTable.int) shouldBeEqualTo 10
            } finally {
                SchemaUtils.drop(testTable)
            }
        }
    }

    /**
     * ```sql
     * CREATE SCHEMA IF NOT EXISTS one;
     * CREATE TABLE IF NOT EXISTS ONE.ONE (ID INT AUTO_INCREMENT PRIMARY KEY);
     * DROP TABLE IF EXISTS ONE.ONE;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `list Tables in all schemas`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL + TestDB.ALL_MARIADB) }

        withDb(testDB) {
            if (currentDialectTest.supportsCreateSchema) {
                val one = prepareSchemaForTest("one")

                try {
                    SchemaUtils.createSchema(one)
                    // table "one.one" is created in new schema by db because of name
                    // even though current schema has not been set to the new one above
                    SchemaUtils.create(OneOneTable)

                    // so new table will not appear in list of tables in current schema
                    SchemaUtils.listTables()
                        .any { it.equals(OneOneTable.tableName, ignoreCase = true) }.shouldBeFalse()

                    // but new table appears in list of tables from all schema
                    SchemaUtils.listTablesInAllSchemas()
                        .any { it.equals(OneOneTable.tableName, ignoreCase = true) }.shouldBeTrue()

                    OneOneTable.exists().shouldBeTrue()
                } finally {
                    SchemaUtils.drop(OneOneTable)
                    SchemaUtils.dropSchema(one, cascade = true)
                }
            }
        }
    }

    /**
     * Note on Oracle exclusion in this test:
     * Oracle names are not case-sensitive. They can be made case-sensitive by using quotes around them. The Oracle JDBC
     * driver converts the entire SQL INSERT statement to upper case before extracting the table name from it. This
     * happens regardless of whether there is a dot in the name. Even when a name is quoted, the driver converts
     * it to upper case. Therefore, the INSERT statement fails when it contains a quoted table name because it attempts
     * to insert into a table that does not exist (“SOMENAMESPACE.SOMETABLE” is not found) . It does not fail when the
     * table name is not quoted because the case would not matter in that scenario.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS "SomeNamespace.SomeTable" (
     *      id SERIAL PRIMARY KEY,
     *      text_col TEXT NOT NULL
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create table with dot in name without creating schema beforehand`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL }

        withDb(testDB) {
            val q = db.identifierManager.quoteString
            val tableName = "${q}SomeNamespace.SomeTable${q}"

            val tester = object: IntIdTable(tableName) {
                val text_col = text("text_col")
            }

            try {
                runCatching { SchemaUtils.drop(tester) }

                SchemaUtils.create(tester)
                tester.exists().shouldBeTrue()

                val id = tester.insertAndGetId { it[text_col] = "Inserted text" }
                tester.update({ tester.id eq id }) { it[text_col] = "Updated text" }
                tester.deleteWhere { tester.id eq id }
            } finally {
                SchemaUtils.drop(tester)
            }
        }
    }
}
