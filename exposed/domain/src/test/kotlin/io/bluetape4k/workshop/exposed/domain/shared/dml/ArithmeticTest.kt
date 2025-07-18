package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.DivideOp.Companion.withScale
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.div
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.minus
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.times
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Calculate column values using arithmetic operators.
 * 단, EntityID<T> 는 산술 연산을 지원하지 않는다.
 */
class ArithmeticTest: AbstractExposedTest() {

    /**
     * ```sql
     * SELECT USERDATA."value",
     *        (((USERDATA."value" - 5) * 2) / 2)
     *   FROM USERDATA
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `operator precedence of minus, plus, div times`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { _, _, userData ->
            val calculatedColumn = ((DMLTestData.UserData.value - 5) * 2) / 2

            userData
                .select(DMLTestData.UserData.value, calculatedColumn)
                .forEach {
                    val value = it[DMLTestData.UserData.value]
                    val actualResult = it[calculatedColumn]
                    val expectedResult = ((value - 5) * 2) / 2
                    actualResult shouldBeEqualTo expectedResult
                }
        }
    }

    /**
     * `Expression.build { ten / three }`
     *
     * ```sql
     * SELECT (10 / 3) FROM CITIES LIMIT 1
     * ```
     *
     * `withScale(2)`
     *
     * ```sql
     * SELECT (10.0 / 3) FROM CITIES LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `big decimal division with scale and without`(testDB: TestDB) {
        withCitiesAndUsers(testDB) { cities, _, _ ->
            val ten = decimalLiteral(10.toBigDecimal())
            val three = decimalLiteral(3.toBigDecimal())

            // SELECT (10 / 3) FROM CITIES LIMIT 1
            val divTenToThreeWithoutScale = Expression.build { ten / three }
            val resultWithoutScale = cities
                .select(divTenToThreeWithoutScale)
                .limit(1)
                .single()[divTenToThreeWithoutScale]

            resultWithoutScale shouldBeEqualTo 3.toBigDecimal()

            // SELECT (10.0 / 3) FROM CITIES LIMIT 1
            val divTenToThreeWithScale = divTenToThreeWithoutScale.withScale(2)
            val resultWithScale = cities
                .select(divTenToThreeWithScale)
                .limit(1)
                .single()[divTenToThreeWithScale]

            resultWithScale shouldBeEqualTo 3.33.toBigDecimal()
        }
    }
}
