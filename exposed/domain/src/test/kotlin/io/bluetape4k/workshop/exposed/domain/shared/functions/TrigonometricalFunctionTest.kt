package io.bluetape4k.workshop.exposed.domain.shared.functions

import io.bluetape4k.workshop.exposed.TestDB
import org.jetbrains.exposed.sql.decimalLiteral
import org.jetbrains.exposed.sql.doubleLiteral
import org.jetbrains.exposed.sql.functions.math.ACosFunction
import org.jetbrains.exposed.sql.functions.math.ASinFunction
import org.jetbrains.exposed.sql.functions.math.ATanFunction
import org.jetbrains.exposed.sql.functions.math.CosFunction
import org.jetbrains.exposed.sql.functions.math.CotFunction
import org.jetbrains.exposed.sql.functions.math.DegreesFunction
import org.jetbrains.exposed.sql.functions.math.PiFunction
import org.jetbrains.exposed.sql.functions.math.RadiansFunction
import org.jetbrains.exposed.sql.functions.math.SinFunction
import org.jetbrains.exposed.sql.functions.math.TanFunction
import org.jetbrains.exposed.sql.intLiteral
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class TrigonometricalFunctionTest: AbstractFunctionsTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `ACosFunction test`(testDB: TestDB) {
        withTable(testDB) {
            ACosFunction(intLiteral(0)) shouldExpressionEqualTo "1.5707963".toBigDecimal()
            ACosFunction(intLiteral(1)) shouldExpressionEqualTo "0".toBigDecimal()
            ACosFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "1.3181161".toBigDecimal()
            ACosFunction(decimalLiteral("0.25".toBigDecimal())) shouldExpressionEqualTo "1.3181161".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `ASinFunction test`(testDB: TestDB) {
        withTable(testDB) {
            ASinFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()
            ASinFunction(intLiteral(1)) shouldExpressionEqualTo "1.5707963".toBigDecimal()
            ASinFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "0.252680255".toBigDecimal()
            ASinFunction(decimalLiteral("0.25".toBigDecimal())) shouldExpressionEqualTo "0.252680255".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `ATanFunction test`(testDB: TestDB) {
        withTable(testDB) {
            ATanFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()
            ATanFunction(intLiteral(1)) shouldExpressionEqualTo "0.785398163".toBigDecimal()
            ATanFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "0.244978663".toBigDecimal()
            ATanFunction(decimalLiteral("0.25".toBigDecimal())) shouldExpressionEqualTo "0.244978663".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CosFunction test`(testDB: TestDB) {
        withTable(testDB) {
            CosFunction(intLiteral(0)) shouldExpressionEqualTo "1".toBigDecimal()
            CosFunction(intLiteral(1)) shouldExpressionEqualTo "0.5403023".toBigDecimal()
            CosFunction(doubleLiteral(0.26)) shouldExpressionEqualTo "0.96638998".toBigDecimal()
            CosFunction(decimalLiteral("0.26".toBigDecimal())) shouldExpressionEqualTo "0.96638998".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `CotFunction test`(testDB: TestDB) {
        withTable(testDB) {
            CotFunction(intLiteral(1)) shouldExpressionEqualTo "0.642092616".toBigDecimal()
            CotFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "3.916317365".toBigDecimal()
            CotFunction(decimalLiteral(BigDecimal("0.25"))) shouldExpressionEqualTo "3.916317365".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `DegreesFunction test`(testDB: TestDB) {
        withTable(testDB) {
            DegreesFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()

            DegreesFunction(intLiteral(1)) shouldExpressionEqualTo "57.29577951308232".toBigDecimal()
            DegreesFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "14.32394487827058".toBigDecimal()
            DegreesFunction(decimalLiteral(BigDecimal("0.25"))) shouldExpressionEqualTo "14.32394487827058".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `PiFunction test`(testDB: TestDB) {
        withTable(testDB) {
            when (testDB) {
                in TestDB.ALL_MYSQL_MARIADB ->
                    PiFunction shouldExpressionEqualTo "3.141593".toBigDecimal()

                else                ->
                    PiFunction shouldExpressionEqualTo "3.141592653589793".toBigDecimal()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `RadiansFunction test`(testDB: TestDB) {
        withTable(testDB) {
            RadiansFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()


            RadiansFunction(intLiteral(180)) shouldExpressionEqualTo "3.141592653589793".toBigDecimal()
            RadiansFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "0.004363323129985824".toBigDecimal()
            RadiansFunction(decimalLiteral(BigDecimal("0.25"))) shouldExpressionEqualTo "0.004363323129985824".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `SinFunction test`(testDB: TestDB) {
        withTable(testDB) {
            SinFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()
            SinFunction(intLiteral(1)) shouldExpressionEqualTo "0.841470985".toBigDecimal()
            SinFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "0.2474039593".toBigDecimal()
            SinFunction(decimalLiteral(BigDecimal("0.25"))) shouldExpressionEqualTo "0.2474039593".toBigDecimal()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `TanFunction test`(testDB: TestDB) {
        withTable(testDB) {
            TanFunction(intLiteral(0)) shouldExpressionEqualTo "0".toBigDecimal()
            TanFunction(intLiteral(1)) shouldExpressionEqualTo "1.557407725".toBigDecimal()
            TanFunction(doubleLiteral(0.25)) shouldExpressionEqualTo "0.2553419212".toBigDecimal()
            TanFunction(decimalLiteral(BigDecimal("0.25"))) shouldExpressionEqualTo "0.2553419212".toBigDecimal()
        }
    }
}
