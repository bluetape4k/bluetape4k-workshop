package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.collections.intRangeOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withSuspendedTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedConnection
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.experimental.withSuspendTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith

class CoroutineTest: AbstractExposedTest() {

    companion object: KLogging() {
        private val singleThreadDispatcher =
            Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    object Testing: IntIdTable("CORUINTE_TESTING")

    object TestingUnique: Table("CORUINTE_TESTING_UNIQUE") {
        val id = integer("id").uniqueIndex()
    }

    suspend fun Transaction.getTestingById(id: Int) = withSuspendTransaction {
        Testing.selectAll().where { Testing.id eq id }.singleOrNull()?.getOrNull(Testing.id)
    }

    // @RepeatedTest(REPEAT_SIZE)
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended transaction`(testDb: TestDB) = runSuspendIO {
        withSuspendedTables(testDb, Testing) {
            val mainJob = async(singleThreadDispatcher) {
                val job = launch(singleThreadDispatcher) {
                    newSuspendedTransaction(db = db) {
                        Testing.insert { }

                        getTestingById(1)?.value shouldBeEqualTo 1
                    }
                }
                job.join()

                val result = newSuspendedTransaction(singleThreadDispatcher, db = db) {
                    Testing.selectAll().where { Testing.id eq 1 }.singleOrNull()?.getOrNull(Testing.id)?.value
                }
                result shouldBeEqualTo 1
            }
            mainJob.await()
            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            getTestingById(1)?.value shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend transaction with repeatation`(testDb: TestDB) = runSuspendIO {
        withSuspendedTables(testDb, TestingUnique) {
            val (originalId, updatedId) = 1 to 99

            val mainJob = async(Dispatchers.Default) {
                newSuspendedTransaction(Dispatchers.Default, db = db) {
                    TestingUnique.insert { it[id] = originalId }

                    TestingUnique.selectAll()
                        .single()[TestingUnique.id] shouldBeEqualTo originalId
                }


                val insertJob = launch {
                    newSuspendedTransaction(Dispatchers.Default, db = db) {
                        maxAttempts = 20

                        // 추가 잡업은 처음에는 unique index 문제로 실패한다.
                        // 하지만 updateJob 이 한번 성공하고 나면, insert가 성공한다.
                        // throws JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation
                        // until original row is updated with a new id
                        TestingUnique.insert { it[id] = originalId }

                        TestingUnique.selectAll().count().toInt() shouldBeEqualTo 2
                    }
                }

                val updateJob = launch {
                    newSuspendedTransaction(Dispatchers.Default, db = db) {
                        maxAttempts = 20

                        TestingUnique.update({ TestingUnique.id eq originalId }) {
                            it[id] = updatedId
                        }

                        TestingUnique.selectAll()
                            .single()[TestingUnique.id] shouldBeEqualTo updatedId
                    }
                }

                insertJob.join()
                updateJob.join()

                val result = newSuspendedTransaction(Dispatchers.Default, db = db) {
                    TestingUnique.selectAll().count()
                }
                result shouldBeEqualTo 2L
            }

            mainJob.await()
            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            TestingUnique.selectAll()
                .orderBy(TestingUnique.id)
                .map { it[TestingUnique.id] } shouldBeEqualTo listOf(originalId, updatedId)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend transaction async with repeatition`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, TestingUnique) {
            val (originalId, updatedId) = 1 to 99

            val mainJob = async(Dispatchers.Default) {
                newSuspendedTransaction(Dispatchers.Default, db = db) {
                    TestingUnique.insert { it[id] = originalId }

                    TestingUnique.selectAll()
                        .single()[TestingUnique.id] shouldBeEqualTo originalId
                }

                val (insertResult, updateResult) = listOf(
                    suspendedTransactionAsync(db = db) {
                        maxAttempts = 20

                        // 추가 잡업은 처음에는 unique index 문제로 실패한다.
                        // 하지만 updateJob 이 한번 성공하고 나면, insert가 성공한다.
                        // throws JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation
                        // until original row is updated with a new id
                        TestingUnique.insert { it[id] = originalId }

                        TestingUnique.selectAll().count().toInt()
                    },
                    suspendedTransactionAsync(db = db) {
                        maxAttempts = 20

                        TestingUnique.update({ TestingUnique.id eq originalId }) { it[id] = updatedId }

                        TestingUnique.selectAll().count().toInt()
                    }
                ).awaitAll()

                insertResult shouldBeEqualTo 2
                updateResult shouldBeEqualTo 1
            }

            mainJob.await()
            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            TestingUnique.selectAll()
                .orderBy(TestingUnique.id)
                .map { it[TestingUnique.id] } shouldBeEqualTo listOf(originalId, updatedId)
        }
    }

    // @RepeatedTest(REPEAT_SIZE)
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested suspend transaction test`(testDb: TestDB) = runSuspendIO {
        suspend fun insertTesting(db: Database) = newSuspendedTransaction(db = db) {
            Testing.insert { }
        }

        withSuspendedTables(testDb, Testing) {
            val mainJob = async {
                val job = launch(Dispatchers.IO) {
                    newSuspendedTransaction(db = db) {
                        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                        getTestingById(1).shouldBeNull()

                        insertTesting(db)

                        getTestingById(1)?.value shouldBeEqualTo 1
                    }
                }
                job.join()

                val result = newSuspendedTransaction(Dispatchers.Default, db = db) {
                    getTestingById(1)?.value
                }
                result shouldBeEqualTo 1
            }

            mainJob.await()
            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            getTestingById(1)?.value shouldBeEqualTo 1
        }
    }

    // @RepeatedTest(REPEAT_SIZE)
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested suspend async transaction test`(testDb: TestDB) = runSuspendIO {
        val recordCount = 10

        withSuspendedTables(testDb, Testing) {
            val mainJob = async {
                val job = launch(Dispatchers.IO) {
                    newSuspendedTransaction(db = db) {
                        repeat(recordCount) {
                            Testing.insert { }
                        }
                        commit()

                        // 동시에 여러개의 트랜잭션을 실행한다.
                        val lists = List(5) {
                            suspendedTransactionAsync {
                                Testing.selectAll().toList()
                            }
                        }
                        lists.awaitAll()
                    }
                }
                job.join()

                val result = newSuspendedTransaction(Dispatchers.Default, db = db) {
                    Testing.selectAll().count()
                }
                result shouldBeEqualTo recordCount.toLong()
            }
            mainJob.await()
            mainJob.getCompletionExceptionOrNull()?.let { throw it }

            Testing.selectAll().count().toInt() shouldBeEqualTo recordCount
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `await all`(testDb: TestDB) = runSuspendIO {
        val recordCount = 5

        withSuspendedTables(testDb, Testing) {
            val results = List(recordCount) { index ->
                suspendedTransactionAsync(Dispatchers.IO, db = db) {
                    Testing.insert { }
                    index + 1
                }
            }.awaitAll()

            results shouldBeEqualTo intRangeOf(1, recordCount)

            Testing.selectAll().count().toInt() shouldBeEqualTo recordCount
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended and normal transaction`(testDb: TestDB) = runSuspendIO {
        withSuspendedTables(testDb, Testing) {
            val db = this.db
            var suspendedOk = true
            var normalOk = true

            val mainJob = launch {
                newSuspendedTransaction(db = db) {
                    try {
                        Testing.selectAll().toList()
                    } catch (e: Throwable) {
                        suspendedOk = false
                    }
                }

                transaction(db) {
                    try {
                        Testing.selectAll().toList()
                    } catch (e: Throwable) {
                        normalOk = false
                    }
                }
            }

            mainJob.join()
            suspendedOk.shouldBeTrue()
            normalOk.shouldBeTrue()

        }
    }

    class TestingEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestingEntity>(Testing)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coroutines with exception within`(testDb: TestDB) = runSuspendIO {
        withSuspendedTables(testDb, Testing) {
            val id = Testing.insertAndGetId { }
            commit()

            var connection: ExposedConnection<*>? = null

            assertFailsWith<ExposedSQLException> {
                suspendedTransactionAsync(singleThreadDispatcher, db = db) {
                    connection = this.connection
                    // 중복된 id를 삽입하면 예외가 발생한다.
                    TestingEntity.new(id.value) {}
                }.await()
            }
            // Nested transaction은 예외가 발생하고, connection은 닫힌다.
            connection.shouldNotBeNull().isClosed.shouldBeTrue()

            Testing.selectAll().count().toInt() shouldBeEqualTo 1
        }
    }
}
