package io.bluetape4k.workshop.exposed.domain.shared.functions

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.div
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.doubleLiteral
import org.jetbrains.exposed.sql.functions.math.AbsFunction
import org.jetbrains.exposed.sql.functions.math.CeilingFunction
import org.jetbrains.exposed.sql.functions.math.ExpFunction
import org.jetbrains.exposed.sql.functions.math.FloorFunction
import org.jetbrains.exposed.sql.functions.math.PowerFunction
import org.jetbrains.exposed.sql.functions.math.RoundFunction
import org.jetbrains.exposed.sql.functions.math.SignFunction
import org.jetbrains.exposed.sql.functions.math.SqrtFunction
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.sql.SQLException

class MathFunctionTest: AbstractFunctionsTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `abs function`(testDB: TestDB) {
        withTable(testDB) {
            AbsFunction(intLiteral(0)) shouldExpressionEqualTo 0
            AbsFunction(intLiteral(100)) shouldExpressionEqualTo 100
            AbsFunction(intLiteral(-100)) shouldExpressionEqualTo 100
            AbsFunction(doubleLiteral(100.0)) shouldExpressionEqualTo 100.0
            AbsFunction(doubleLiteral(-100.0)) shouldExpressionEqualTo 100.0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `sign function`(testDB: TestDB) {
        withTable(testDB) {
            SignFunction(intLiteral(0)) shouldExpressionEqualTo 0
            SignFunction(intLiteral(100)) shouldExpressionEqualTo 1
            SignFunction(intLiteral(-100)) shouldExpressionEqualTo -1
            SignFunction(doubleLiteral(100.0)) shouldExpressionEqualTo 1
            SignFunction(doubleLiteral(-100.0)) shouldExpressionEqualTo -1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `floor function`(testDB: TestDB) {
        withTable(testDB) {
            FloorFunction(intLiteral(100)) shouldExpressionEqualTo 100
            FloorFunction(intLiteral(-100)) shouldExpressionEqualTo -100
            FloorFunction(doubleLiteral(100.0)) shouldExpressionEqualTo 100
            FloorFunction(doubleLiteral(100.30)) shouldExpressionEqualTo 100
            FloorFunction(doubleLiteral(100.70)) shouldExpressionEqualTo 100
            FloorFunction(doubleLiteral(-100.0)) shouldExpressionEqualTo -100
            FloorFunction(doubleLiteral(-100.30)) shouldExpressionEqualTo -101
            FloorFunction(doubleLiteral(-100.70)) shouldExpressionEqualTo -101
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `ceil function`(testDB: TestDB) {
        withTable(testDB) {
            CeilingFunction(intLiteral(100)) shouldExpressionEqualTo 100
            CeilingFunction(intLiteral(-100)) shouldExpressionEqualTo -100
            CeilingFunction(doubleLiteral(100.0)) shouldExpressionEqualTo 100
            CeilingFunction(doubleLiteral(100.30)) shouldExpressionEqualTo 101
            CeilingFunction(doubleLiteral(100.70)) shouldExpressionEqualTo 101
            CeilingFunction(doubleLiteral(-100.0)) shouldExpressionEqualTo -100
            CeilingFunction(doubleLiteral(-100.30)) shouldExpressionEqualTo -100
            CeilingFunction(doubleLiteral(-100.70)) shouldExpressionEqualTo -100
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `power function`(testDB: TestDB) {
        withTable(testDB) {
            PowerFunction(intLiteral(10), intLiteral(2)) shouldExpressionEqualTo 100.toBigDecimal()
            PowerFunction(intLiteral(10), doubleLiteral(2.0)) shouldExpressionEqualTo 100.toBigDecimal()

            PowerFunction(doubleLiteral(10.1), intLiteral(2)) shouldExpressionEqualTo 102.01.toBigDecimal()
            PowerFunction(decimalLiteral(10.1.toBigDecimal()), intLiteral(2)) shouldExpressionEqualTo
                    102.01.toBigDecimal()

            PowerFunction(doubleLiteral(10.1), doubleLiteral(2.0)) shouldExpressionEqualTo
                    102.01.toBigDecimal()
            PowerFunction(decimalLiteral(BigDecimal("10.1")), doubleLiteral(2.0)) shouldExpressionEqualTo
                    102.01.toBigDecimal()

            PowerFunction(doubleLiteral(10.1), doubleLiteral(2.5)) shouldExpressionEqualTo
                    324.1928515714.toBigDecimal()
            PowerFunction(decimalLiteral(BigDecimal("10.1")), doubleLiteral(2.5)) shouldExpressionEqualTo
                    324.1928515714.toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `round function`(testDB: TestDB) {
        withTable(testDB) {
            RoundFunction(intLiteral(10), 0) shouldExpressionEqualTo 10.toBigDecimal()
            RoundFunction(intLiteral(10), 2) shouldExpressionEqualTo "10.00".toBigDecimal()

            RoundFunction(doubleLiteral(10.455), 0) shouldExpressionEqualTo 10.toBigDecimal()
            RoundFunction(doubleLiteral(10.555), 0) shouldExpressionEqualTo 11.toBigDecimal()

            RoundFunction(doubleLiteral(10.555), 2) shouldExpressionEqualTo "10.56".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `sqrt function`(testDB: TestDB) {
        withTable(testDB) {
            SqrtFunction(intLiteral(100)) shouldExpressionEqualTo 10.toBigDecimal()
            SqrtFunction(doubleLiteral(100.0)) shouldExpressionEqualTo 10.toBigDecimal()

            SqrtFunction(doubleLiteral(125.44)) shouldExpressionEqualTo "11.2".toBigDecimal()
            SqrtFunction(decimalLiteral(100.toBigDecimal())) shouldExpressionEqualTo 10.toBigDecimal()

            SqrtFunction(decimalLiteral(BigDecimal("125.44"))) shouldExpressionEqualTo "11.2".toBigDecimal()

            when (testDB) {
                in TestDB.ALL_MYSQL ->
                    SqrtFunction(intLiteral(-100)) shouldExpressionEqualTo null

                else                ->
                    expectException<SQLException> {
                        SqrtFunction(intLiteral(-100)) shouldExpressionEqualTo null
                    }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `exp function`(testDB: TestDB) {
        withTable(testDB) {
            ExpFunction(intLiteral(1)) shouldExpressionEqualTo "2.7182818284590".toBigDecimal()
            ExpFunction(doubleLiteral(2.5)) shouldExpressionEqualTo "12.182493960703473".toBigDecimal()
            ExpFunction(decimalLiteral("2.5".toBigDecimal())) shouldExpressionEqualTo "12.182493960703473".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Column Reference In Default Expression in MySQL`(testDB: TestDB) {
        val foo = object: IntIdTable("foo") {
            val integer = integer("integer")
            val double = double("double")
            val long = long("long")
            val defaultInt = integer("defaultInt").defaultExpression(AbsFunction(integer))
            val defaultInt2 = integer("defaultInt2").defaultExpression(defaultInt.div(100))
            val defaultDecimal =
                decimal("defaultDecimal", 14, 12).nullable().defaultExpression(ExpFunction(defaultInt2))
            val defaultLong = long("defaultLong").nullable().defaultExpression(FloorFunction(double))
            val defaultDecimal2 =
                decimal("defaultDecimal2", 3, 0).nullable().defaultExpression(PowerFunction(long, intLiteral(2)))
            val defaultDecimal3 =
                decimal("defaultDecimal3", 3, 0).nullable().defaultExpression(RoundFunction(double, 0))
            val defaultInt3 = integer("defaultInt3").nullable().defaultExpression(SignFunction(integer))
            val defaultDecimal4 =
                decimal("defaultDecimal4", 3, 0).nullable().defaultExpression(SqrtFunction(defaultDecimal2))
        }

        Assumptions.assumeTrue { testDB in TestDB.ALL_MYSQL && testDB != TestDB.MYSQL_V5 }

        // MySQL and MariaDB are the only supported databases that allow referencing another column in a default expression
        // MySQL 5 does not support functions in default values.
        withTables(testDB, foo) {
            val id = foo.insertAndGetId {
                it[foo.integer] = -100
                it[foo.double] = 100.70
                it[foo.long] = 10L
            }
            val result = foo.selectAll().where { foo.id eq id }.single()

            result[foo.defaultInt] shouldBeEqualTo 100
            result[foo.defaultDecimal] shouldBeEqualTo "2.718281828459".toBigDecimal()
            result[foo.defaultLong] shouldBeEqualTo 100L
            result[foo.defaultDecimal2] shouldBeEqualTo 100.toBigDecimal()
            result[foo.defaultDecimal3] shouldBeEqualTo 101.toBigDecimal()
            result[foo.defaultInt3] shouldBeEqualTo -1
            result[foo.defaultDecimal4] shouldBeEqualTo 10.toBigDecimal()
        }
    }
}
