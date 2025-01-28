package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.jetbrains.exposed.sql.Table.Dual
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.javatime.CurrentDate
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 더미 테이블인 "DUAL" 테이블을 이용하는 예제입니다.
 */
class DualTableTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * 실제 테이블들을 지정하지 않아도 간단한 쿼리 구문을 실행 할 수 있습니다.
     *
     * ```sql
     * SELECT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testDualTable(testDb: TestDB) {
        withDb(testDb) {
            val resultColumn = intLiteral(1)
            val result: Int = Dual.select(resultColumn).single()[resultColumn]
            result shouldBeEqualTo 1
        }
    }

    /**
     * 현재 날짜를 조회하는 쿼리를 실행합니다.
     *
     * ```sql
     * SELECT CURRENT_DATE
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `dual table with CurrentDate`(testDB: TestDB) {
        withDb(testDB) {
            val today: LocalDate = Dual.select(CurrentDate).single()[CurrentDate]
            today shouldBeEqualTo LocalDate.now()
        }
    }

    /**
     * 현재 날짜를 조회하는 쿼리를 실행합니다.
     *
     * ```sql
     * SELECT CURRENT_TIMESTAMP
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `dual table with CurrentDateTime`(testDB: TestDB) {
        withDb(testDB) {
            val now: LocalDateTime = Dual.select(CurrentDateTime).single()[CurrentDateTime]
            now shouldBeLessOrEqualTo LocalDateTime.now()
        }
    }
}
