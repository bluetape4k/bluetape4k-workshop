package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.dml.DMLTestData
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.fail
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeEqualTo
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.concurrent.thread
import kotlin.test.assertFails

@Suppress("DEPRECATION")
class ThreadLocalManagerTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * MYSQL V5 에서만 지원됩니다.
     */
    fun `re-connection`() {
        Assumptions.assumeTrue { TestDB.MYSQL_V5 in TestDB.enabledDialects() }

        var secondThreadTm: TransactionManager? = null
        val db1: Database = TestDB.MYSQL_V5.connect()
        lateinit var db2: Database

        transaction {
            val firstThreadTm = db1.transactionManager
            SchemaUtils.create(DMLTestData.Cities)

            thread {
                db2 = TestDB.MYSQL_V5.connect()
                transaction {
                    DMLTestData.Cities.selectAll().toList()
                    secondThreadTm = db2.transactionManager
                    secondThreadTm shouldNotBeEqualTo firstThreadTm
                }
            }.join()
            db1.transactionManager shouldBeEqualTo firstThreadTm
            SchemaUtils.drop(DMLTestData.Cities)
        }
        db2.transactionManager shouldBeEqualTo secondThreadTm
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testReadOnly(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in READ_ONLY_EXCLUDED_VENDORS }

        withTables(testDB, RollbackTable) {
            assertFails {
                // read-only 이므로 INSERT 작업은 실패합니다.
                inTopLevelTransaction(db.transactionManager.defaultIsolationLevel, true) {
                    maxAttempts = 1
                    RollbackTable.insert { it[value] = "random-something" }
                }
            }.message
                ?.let { it shouldContain "read-only" }
                ?: fail("error message should not be null")
        }
    }

    /**
     * Coroutines 환경에서 [newSuspendedTransaction] 으로 readOnly 작업을 수행하기
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended read-only`(testDB: TestDB) = runTest {
        Assumptions.assumeTrue { testDB !in READ_ONLY_EXCLUDED_VENDORS }

        val database: Database = testDB.connect()

        // 읽기 전용에서 테이블 생성은 실패합니다.
        newSuspendedTransaction(db = database, readOnly = true) {
            expectException<ExposedSQLException> {
                SchemaUtils.create(RollbackTable)
            }
        }

        transaction(db = database) {
            SchemaUtils.create(RollbackTable)
        }

        // 읽기 전용에서 데이터 추가는 실패합니다.
        newSuspendedTransaction(db = database, readOnly = true) {
            expectException<ExposedSQLException> {
                RollbackTable.insert { it[value] = "random-something" }
            }
        }

        transaction(db = database) {
            RollbackTable.insert { it[value] = "random-something" }
        }

        transaction(db = database) {
            SchemaUtils.drop(RollbackTable)
        }
    }
}
