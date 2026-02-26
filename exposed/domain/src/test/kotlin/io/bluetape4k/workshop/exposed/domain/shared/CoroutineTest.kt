package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.collections.intRangeOf
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.idValue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withSuspendedTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.api.ExposedConnection
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.withSuspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith

@Suppress("DEPRECATION")
class CoroutineTest: AbstractExposedTest() {

    companion object: KLogging() {
        private val singleThreadDispatcher =
            Executors.newSingleThreadExecutor()
                .apply { ShutdownQueue.register(this) }
                .asCoroutineDispatcher()
    }

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS coruinte_testing (
     *      id SERIAL PRIMARY KEY
     * )
     * ```
     *
     */
    object Testing: IntIdTable("CORUINTE_TESTING")

    /**
     * Postgres:
     * ```sql
     * CREATE TABLE IF NOT EXISTS coruinte_testing_unique (
     *      id INT NOT NULL
     * )
     *
     * ALTER TABLE coruinte_testing_unique
     *      ADD CONSTRAINT coruinte_testing_unique_id_unique UNIQUE (id)
     * ```
     */
    object TestingUnique: Table("CORUINTE_TESTING_UNIQUE") {
        val id = integer("id").uniqueIndex()
    }

    /**
     * ```sql
     * SELECT coruinte_testing.id
     *   FROM coruinte_testing
     *  WHERE coruinte_testing.id = ?
     * ```
     */
    suspend fun JdbcTransaction.getTestingById(id: Int) = withSuspendTransaction {
        Testing
            .selectAll()
            .where { Testing.id eq id }
            .singleOrNull()
            ?.getOrNull(Testing.id)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended transaction`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, Testing) {
            val mainJob = async(singleThreadDispatcher) {
                val job = launch(singleThreadDispatcher) {
                    newSuspendedTransaction(db = db) {
                        Testing.insert { }
                        flushCache()
                        entityCache.clear()
                        getTestingById(1)?.value shouldBeEqualTo 1
                    }
                }
                job.join()

                val result = newSuspendedTransaction(singleThreadDispatcher, db = db) {
                    getTestingById(1)?.value
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
    fun `suspend transaction with repeatation`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, TestingUnique) {
            val (originalId, updatedId) = 1 to 99

            val mainJob = async(Dispatchers.IO) {
                newSuspendedTransaction(Dispatchers.IO, db = db) {
                    TestingUnique.insert { it[id] = originalId }

                    flushCache()
                    entityCache.clear()

                    TestingUnique.selectAll()
                        .single()[TestingUnique.id] shouldBeEqualTo originalId
                }


                val insertJob = launch {
                    newSuspendedTransaction(Dispatchers.IO, db = db) {
                        maxAttempts = 20

                        // 추가 잡업은 처음에는 unique index 문제로 실패한다.
                        // 하지만 updateJob 이 한번 성공하고 나면, insert가 성공한다.
                        // throws JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation
                        // until original row is updated with a new id
                        TestingUnique.insert { it[id] = originalId }

                        flushCache()
                        entityCache.clear()

                        TestingUnique.selectAll().count().toInt() shouldBeEqualTo 2
                    }
                }

                val updateJob = launch {
                    newSuspendedTransaction(Dispatchers.Default, db = db) {
                        maxAttempts = 20

                        TestingUnique.update({ TestingUnique.id eq originalId }) {
                            it[id] = updatedId
                        }

                        flushCache()
                        entityCache.clear()

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
        withSuspendedTables(testDB, TestingUnique, context = Dispatchers.IO) {
            val (originalId, updatedId) = 1 to 99

            val mainJob = async {
                newSuspendedTransaction(db = db) {
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

                        /**
                         * ```sql
                         * UPDATE coruinte_testing_unique
                         *    SET id=99
                         *  WHERE coruinte_testing_unique.id = 1
                         * ```
                         */
                        TestingUnique.update({ TestingUnique.id eq originalId }) { it[id] = updatedId }
                        TestingUnique.selectAll().count().toInt()
                    }
                ).awaitAll()

                insertResult shouldBeEqualTo 2
                updateResult shouldBeEqualTo 1
                Unit
            }

            mainJob.await()

            TestingUnique.selectAll()
                .orderBy(TestingUnique.id)
                .map { it[TestingUnique.id] } shouldBeEqualTo listOf(originalId, updatedId)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested suspend transaction test`(testDB: TestDB) = runSuspendIO {
        suspend fun insertTesting(db: Database) = newSuspendedTransaction(db = db) {
            Testing.insert { }
        }

        withSuspendedTables(testDB, Testing, context = Dispatchers.IO) {
            val mainJob = async {
                val job = launch(Dispatchers.IO) {
                    newSuspendedTransaction(db = db) {
                        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                        getTestingById(1).shouldBeNull()
                        insertTesting(db)
                        getTestingById(1)?.value shouldBeEqualTo 1
                    }
                }
                job.join()  // job이 완료되기 전까지 기다린다.

                val result = newSuspendedTransaction(Dispatchers.Default, db = db) {
                    getTestingById(1)?.value
                }
                result shouldBeEqualTo 1
            }

            mainJob.await()
            getTestingById(1)?.value shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `nested suspend async transaction test`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, Testing, context = Dispatchers.IO) {
            val recordCount = 10
            
            val mainJob = async {
                val job = launch(Dispatchers.IO) {
                    newSuspendedTransaction(db = db) {
                        repeat(recordCount) {
                            Testing.insert { }
                        }
                        commit()

                        // 동시에 여러개의 Coroutines 방식의 트랜잭션을 실행한다.
                        val lists = List(recordCount) {
                            suspendedTransactionAsync {
                                Testing.selectAll().toList()
                            }
                        }
                        lists.awaitAll()
                    }
                }
                job.join()

                val result = newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    Testing.selectAll().count()
                }
                result shouldBeEqualTo recordCount.toLong()
            }
            mainJob.await()
            Testing.selectAll().count().toInt() shouldBeEqualTo recordCount
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `await all`(testDB: TestDB) = runSuspendIO {
        val recordCount = 5

        withSuspendedTables(testDB, Testing) {
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
    fun `suspended and normal transaction`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, Testing) {
            val db = this.db
            var suspendedOk = true
            var normalOk = true

            val mainJob = launch(Dispatchers.IO) {
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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = "TestingEntity(id=$idValue)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `coroutines with exception within`(testDB: TestDB) = runSuspendIO {
        withSuspendedTables(testDB, Testing) {
            val id = Testing.insertAndGetId { }
            commit()

            var innerConn: ExposedConnection<*>? = null

            assertFailsWith<ExposedSQLException> {
                suspendedTransactionAsync(singleThreadDispatcher, db = db) {
                    innerConn = this.connection
                    // 중복된 id를 삽입하면 예외가 발생한다.
                    TestingEntity.new(id.value) {}
                }.await()
            }
            // Nested transaction은 예외가 발생하고, 해당 connection은 닫힌다.
            innerConn.shouldNotBeNull().isClosed.shouldBeTrue()

            Testing.selectAll().count() shouldBeEqualTo 1L
        }
    }
}
