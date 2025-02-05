package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.exposed.sql.selectImplicitAll
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.MYSQL_V8
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType.OTHER
import org.jetbrains.exposed.sql.stringLiteral
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException

class ColumnDefinitionTest: AbstractExposedTest() {

    val columnCommentSupportedDB = TestDB.ALL_H2 + TestDB.MYSQL_V8

    /**
     * Define a column with a comment.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS TESTER (
     *      AMOUNT INT COMMENT 'Amount of testers' NOT NULL
     * );
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column comment`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in columnCommentSupportedDB }

        val comment = "Amount of testers"
        val tester = object: Table("tester") {
            val amount = integer("amount")
                .withDefinition("COMMENT", stringLiteral(comment))
        }

        withTables(testDB, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
            // MigrationUtils.statementsRequiredForDatabaseMigration(tester).shouldBeEmpty()

            tester.insert { it[amount] = 9 }
            tester.selectAll().single()[tester.amount] shouldBeEqualTo 9

            val tableName = tester.nameInDatabaseCase()
            val showStatement = when (testDB) {
                in TestDB.ALL_MYSQL -> "SHOW FULL COLUMNS FROM $tableName"
                else -> "SELECT REMARKS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$tableName'"
            }
            val resultLabel = when (testDB) {
                in TestDB.ALL_MYSQL -> "Comment"
                else -> "REMARKS"
            }

            val result = exec(showStatement) { rs ->
                rs.next()
                rs.getString(resultLabel)
            }
            result.shouldNotBeNull() shouldBeEqualTo comment
        }
    }

    @Disabled("SQL Server 에서만 지원됩니다.")
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column data masking`(testDB: TestDB) {
        /**
         * Define a column with a data masking function.
         */
        val tester = object: Table("tester") {
            val email = varchar("email", 128)
                .uniqueIndex()
                .withDefinition("MASKED WITH (FUNCTION = 'email()')")
        }

        // withTables(TestDB.SQLSERVER, tester) {
        withTables(testDB, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
            // MigrationUtils.statementsRequiredForDatabaseMigration(tester).shouldBeEmpty()

            val testEmail = "mysecretemail123@gmail.com"
            tester.insert {
                it[email] = testEmail
            }

            // create a new user with limited permissions
            exec("CREATE USER MaskingTestUser WITHOUT LOGIN;")
            exec("GRANT SELECT ON ${tester.nameInDatabaseCase()} TO MaskingTestUser;")
            exec("EXECUTE AS USER = 'MaskingTestUser';", explicitStatementType = OTHER)

            // Email function obfuscates data of all length to form 'aXXX@XXXX.com', where 'a' is original first letter
            val maskedEmail = "${testEmail.first()}XXX@XXXX.com"
            val maskedResult = tester.selectAll().single()[tester.email]
            maskedResult shouldBeEqualTo maskedEmail

            exec("REVERT;")
            exec("DROP USER MaskingTestUser;")
        }
    }

    /**
     * Define a column with a default value.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS TESTER (
     *      AMOUNT INT NOT NULL,
     *      ITEM VARCHAR(32) DEFAULT 'Item A' DEFAULT ON NULL NOT NULL
     *     -- HINT: DEFAULT ON NULL 은 H2 에서만 지원됩니다.
     * );
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column definition on null`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        val itemA = "Item A"
        val tester = object: Table("tester") {
            val amount = integer("amount")
            val item = varchar("item", 32).apply {
                default(itemA).withDefinition("DEFAULT ON NULL")
            }
        }
        withDb(testDB) {
            SchemaUtils.create(tester)

            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
            // MigrationUtils.statementsRequiredForDatabaseMigration(tester).shouldBeEmpty()

            // INSERT INTO TESTER (AMOUNT) VALUES (111)
            tester.insert {
                it[amount] = 111
            }

            // INSERT INTO TESTER (AMOUNT, ITEM) VALUES (222, null)
            exec(
                """
                INSERT INTO ${tester.nameInDatabaseCase()}
                (${tester.amount.nameInDatabaseCase()}, ${tester.item.nameInDatabaseCase()})
                VALUES (222, null)    
                """.trimIndent()
            )

            val result1 = tester.selectAll().map { it[tester.item] }.distinct().single()
            result1 shouldBeEqualTo itemA

            SchemaUtils.drop(tester)
        }
    }

    /**
     * Invisible 컬럼은 조회시 명시적으로 컬럼명을 지정해야만 반환됩니다. (`*` 를 사용하면 반환되지 않습니다.)
     *
     * HINT: H2, MySQL 에서만 지원됩니다.
     *
     * MySQL V8:
     * ```sql
     * CREATE TABLE IF NOT EXISTS tester (
     *      amount INT NOT NULL,
     *      active BOOLEAN INVISIBLE NULL   -- INVISIBLE 컬럼 (`*` 로 조회 시 반환되지 않음)
     * )
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column visibility`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in columnCommentSupportedDB }

        val tester = object: Table("tester") {
            val amount = integer("amount")
            val active = bool("active").nullable().withDefinition("INVISIBLE")
        }

        withTables(testDB, tester) {
            if (testDB == MYSQL_V8) {
                // H2 metadata query does not return invisible column info
                // Bug in MariaDB with nullable column - metadata default value returns as NULL - EXPOSED-415
                SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
                // MigrationUtils.statementsRequiredForDatabaseMigration(tester).shouldBeEmpty()
            }

            tester.insert {
                it[amount] = 999
            }

            /**
             * Invisible 컬럼은 명시적으로 컬럼명을 지정해야만 반환됩니다.
             *
             * ```sql
             * SELECT TESTER.AMOUNT,
             *        TESTER.ACTIVE
             *   FROM TESTER
             *  WHERE TESTER.AMOUNT > 100
             * ```
             */
            tester.selectAll()
                .where { tester.amount greater 100 }
                .execute(this)       // HINT: 이렇게 Statement.execute(transaction) 을 수행하면 java.sql.ResultSet 를 반환합니다.
                .use { rs ->
                    rs.shouldNotBeNull()
                    rs.next()
                    rs.getInt(tester.amount.name) shouldBeEqualTo 999
                    rs.getBoolean(tester.active.name).shouldBeFalse()
                }

            /**
             *  묵시적으로 컬럼 전체를 뜻하는 `*` 를 사용하면, INVISIBLE 컬럼은 반환되지 않습니다.
             *
             * ```sql
             * SELECT *
             *   FROM TESTER
             *  WHERE TESTER.AMOUNT > 100
             * ```
             */
            tester.selectImplicitAll()
                .where { tester.amount greater 100 }
                .execute(this)     // HINT: 이렇게 Statement.execute(transaction) 을 수행하면 java.sql.ResultSet 를 반환합니다.
                .use { rs ->
                    rs!!.next()
                    rs.getInt(tester.amount.name) shouldBeEqualTo 999

                    expectException<SQLException> {
                        rs.getBoolean(tester.active.name)
                    }
                }
        }
    }
}
