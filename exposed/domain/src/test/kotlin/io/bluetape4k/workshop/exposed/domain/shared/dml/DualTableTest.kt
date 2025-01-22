package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.Table.Dual
import org.jetbrains.exposed.sql.intLiteral
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DualTableTest: AbstractExposedTest() {

    /**
     * 더미 테이블인 "DUAL" 테이블을 이용하는 예제입니다.
     * 실제 테이블들을 몰라도 쿼리 구문을 실행 할 수 있습니다.
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
            val result = Dual.select(resultColumn).single()
            result[resultColumn] shouldBeEqualTo 1
        }
    }
}
