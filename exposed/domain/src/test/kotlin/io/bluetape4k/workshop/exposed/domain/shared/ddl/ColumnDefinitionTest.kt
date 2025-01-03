package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.sql.FieldSet
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.stringLiteral
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException

class ColumnDefinitionTest: AbstractExposedTest() {

    val columnCommentSupportedDB = TestDB.ALL_H2 + TestDB.MYSQL_V8

    /**
     * Define a column with a comment.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS TESTER (AMOUNT INT COMMENT 'Amount of testers' NOT NULL)
     * ```
     */
    @ParameterizedTest
    @FieldSource("columnCommentSupportedDB")
    fun `column comment`(dialect: TestDB) {
        val comment = "Amount of testers"
        val tester = object: Table("tester") {
            val amount = integer("amount")
                .withDefinition("COMMENT", stringLiteral(comment))
        }

        withTables(dialect, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()

            tester.insert { it[amount] = 9 }
            tester.selectAll().single()[tester.amount] shouldBeEqualTo 9

            val tableName = tester.nameInDatabaseCase()
            val showStatement = when (dialect) {
                in TestDB.ALL_MYSQL -> "SHOW FULL COLUMNS FROM $tableName"
                else                -> "SELECT REMARKS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$tableName'"
            }
            val resultLabel = when (dialect) {
                in TestDB.ALL_MYSQL -> "Comment"
                else                -> "REMARKS"
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
    fun `column data masking`(dialect: TestDB) {
        // Assumptions.assumeTrue { dialect == TestDB.SQLSERVER }

        val tester = object: Table("tester") {
            val email = varchar("email", 128)
                .uniqueIndex()
                .withDefinition("MASKED WITH (FUNCTION = 'email()')")
        }

        // withTables(TestDB.SQLSERVER, tester) {
        withTables(TestDB.MYSQL_V5, tester) {
            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()

            val testEmail = "mysecretemail123@gmail.com"
            tester.insert {
                it[email] = testEmail
            }

            // create a new user with limited permissions
            exec("CREATE USER MaskingTestUser WITHOUT LOGIN;")
            exec("GRANT SELECT ON ${tester.nameInDatabaseCase()} TO MaskingTestUser;")
            exec("EXECUTE AS USER = 'MaskingTestUser';", explicitStatementType = StatementType.OTHER)

            // Email function obfuscates data of all length to form 'aXXX@XXXX.com', where 'a' is original first letter
            val maskedEmail = "${testEmail.first()}XXX@XXXX.com"
            val maskedResult = tester.selectAll().single()[tester.email]
            maskedResult shouldBeEqualTo maskedEmail

            exec("REVERT;")
            exec("DROP USER MaskingTestUser;")
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column definition on null`(dialect: TestDB) {
        Assumptions.assumeTrue { dialect in TestDB.ALL_H2 }

        val itemA = "Item A"

        withDb(dialect) { testDb ->
            val tester = object: Table("tester") {
                val amount = integer("amount")
                val item = varchar("item", 32).apply {
                    default(itemA).withDefinition("DEFAULT ON NULL")
                }
            }

            SchemaUtils.create(tester)

            SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()

            tester.insert {
                it[amount] = 111
            }

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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `column visibility`(dialect: TestDB) {
        Assumptions.assumeTrue { dialect in (TestDB.ALL_H2 + TestDB.MYSQL_V8) }

        val tester = object: Table("tester") {
            val amount = integer("amount")
            val active = bool("active").nullable().withDefinition("INVISIBLE")
        }

        // this Query uses SELECT * FROM instead of the usual SELECT column_1, column_2, ... FROM
        class ImplicitQuery(set: FieldSet, where: Op<Boolean>?): Query(set, where) {
            override fun prepareSQL(builder: QueryBuilder): String {
                return super.prepareSQL(builder).replaceBefore(" FROM ", "SELECT *")
            }
        }

        fun FieldSet.selectImplicitAll(): Query = ImplicitQuery(this, null)

        withTables(dialect, tester) {
            if (dialect == TestDB.MYSQL_V8) {
                // H2 metadata query does not return invisible column info
                // Bug in MariaDB with nullable column - metadata default value returns as NULL - EXPOSED-415
                SchemaUtils.statementsRequiredToActualizeScheme(tester).shouldBeEmpty()
            }

            tester.insert {
                it[amount] = 999
            }

            // an invisible column is only returned in ResultSet if explicitly named
            tester.selectAll()
                .where { tester.amount greater 100 }
                .execute(this)
                .use { result1 ->
                    result1.shouldNotBeNull()
                    result1.next()
                    result1.getInt(tester.amount.name) shouldBeEqualTo 999
                    result1.getBoolean(tester.active.name).shouldBeFalse()
                }

            tester.selectImplicitAll()
                .where { tester.amount greater 100 }
                .execute(this)
                .use { result2 ->
                    result2.shouldNotBeNull()
                    result2.next()
                    result2.getInt(tester.amount.name) shouldBeEqualTo 999
                    expectException<SQLException> {
                        result2.getBoolean(tester.active.name)
                    }
                }
        }
    }
}
