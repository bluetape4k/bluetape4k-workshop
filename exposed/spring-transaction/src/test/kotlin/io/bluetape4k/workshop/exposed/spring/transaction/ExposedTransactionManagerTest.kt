package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.springframework.test.annotation.Commit
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLTimeoutException
import kotlin.random.Random
import kotlin.test.DefaultAsserter.fail

open class ExposedTransactionManagerTest: SpringTransactionTestBase() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 3
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS T1 (
     *      C1 VARCHAR(11) NOT NULL
     * )
     * ```
     */
    object T1: Table() {
        val c1 = varchar("c1", Int.MIN_VALUE.toString().length)
    }

    private fun T1.insertRandom() {
        insert {
            it[c1] = Random.nextInt().toString()
        }
    }

    @BeforeEach
    fun beforeEach() {
        transactionManager.execute {
            SchemaUtils.create(T1)
        }
    }

    @AfterEach
    fun afterEach() {
        transactionManager.execute {
            SchemaUtils.drop(T1)
        }
    }

    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `test connection`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1
    }

    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `test connection 2`() {
        val rnd = Random.nextInt().toString()
        T1.insert {
            it[c1] = rnd
        }
        T1.selectAll().single()[T1.c1] shouldBeEqualTo rnd
    }

    /**
     * `transaction` 함수는 Exposed의 transaction으로 spring의 `@Transactional` 과 함께 사용할 수 있다.
     */
    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection combine with exposed transaction`() {
        // Exposed의 `transaction` 함수 
        transaction {
            val rnd = Random.nextInt().toString()
            T1.insert {
                it[c1] = rnd
            }
            T1.selectAll().single()[T1.c1] shouldBeEqualTo rnd

            // Spring의 `TransactionManager`
            transactionManager.execute {
                T1.insertRandom()
                T1.selectAll().count() shouldBeEqualTo 2L
            }
        }
    }

    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection combine with exposed transaction 2`() {
        val rnd = Random.nextInt().toString()
        T1.insert {
            it[c1] = rnd
        }
        T1.selectAll().single()[T1.c1] shouldBeEqualTo rnd

        transaction {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 2L
        }
    }

    /**
     * `PROPAGATION_NESTED` 는 현재 트랜잭션이 존재하는 경우 중첩된 트랜잭션으로 동작하며,
     * 그렇지 않은 경우 `REQUIRED` 와 동일하게 동작한다.
     */
    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with nested transaction commit`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1L

        // NESTED Transaction
        transactionManager.execute(TransactionDefinition.PROPAGATION_NESTED) {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 2L
        }

        T1.selectAll().count() shouldBeEqualTo 2L
    }

    /**
     * `PROPAGATION_NESTED` 는 현재 트랜잭션이 존재하는 경우 중첩된 트랜잭션으로 동작하며, rollback 시 중첩된 트랜잭션만 롤백된다.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with nested transaction inner rollback`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1L

        // NESTED Transaction
        transactionManager.execute(TransactionDefinition.PROPAGATION_NESTED) { status ->
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 2L

            // Rollback only inner transaction
            status.setRollbackOnly()
        }

        // Outer transaction is not rollback
        T1.selectAll().count() shouldBeEqualTo 1L
    }

    /**
     * `PROPAGATION_NESTED` 는 outer transaction이 롤백되면 중첩된 트랜잭션도 롤백된다.
     */
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with nested transaction outer rollback`() {
        transactionManager.execute { status ->
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 1L

            // outer transaction rollback
            status.setRollbackOnly()

            // NESTED Transaction
            transactionManager.execute(TransactionDefinition.PROPAGATION_NESTED) {
                T1.insertRandom()
                T1.selectAll().count() shouldBeEqualTo 2L
            }

            T1.selectAll().count() shouldBeEqualTo 2L
        }

        transactionManager.execute {
            T1.selectAll().count() shouldBeEqualTo 0L
        }
    }

    /**
     * `PROPAGATION_REQUIRES_NEW` 는 항상 새로운 트랜잭션을 생성한다.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with required new`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1L

        // REQUIRES_NEW Transaction (새로운 트랜잭션으로 동작)
        transactionManager.execute(TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            T1.selectAll().count() shouldBeEqualTo 0L
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 1L
        }
        T1.selectAll().count() shouldBeEqualTo 2L
    }

    /**
     * Test for Propagation.REQUIRES_NEW with inner transaction roll-back
     * The inner transaction will be roll-back only inner transaction when the transaction marks as rollback.
     * And since isolation level is READ_COMMITTED, the inner transaction can't see the changes of outer transaction.
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `connection with requires new with inner transaction rollback`() {
        transactionManager.execute {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 1L

            // outer transaction이 존재해도, 새로운 transaction을 생성한다
            transactionManager.execute(TransactionDefinition.PROPAGATION_REQUIRES_NEW) { status ->
                T1.insertRandom()
                T1.selectAll().count() shouldBeEqualTo 1L

                // Rollback only inner transaction
                status.setRollbackOnly()
            }

            T1.selectAll().count() shouldBeEqualTo 1L
        }

        transactionManager.execute {
            T1.selectAll().count() shouldBeEqualTo 1L
        }
    }

    /**
     * [Propagation.NEVER]
     * Transaction 없이 수행되고, 이미 transaction이 존재하는 경우 예외를 발생시킨다.
     */
    @Transactional(propagation = Propagation.NEVER)
    @RepeatedTest(REPEAT_SIZE)
    open fun `propagation never`() {
        assertFailsWith<IllegalStateException> {
            T1.insertRandom()
        }
    }

    /**
     * Test for [Propagation.NEVER]
     *
     * Throw an exception cause outer transaction exists.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `propagation never with existing transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            T1.insertRandom()
            transactionManager.execute(TransactionDefinition.PROPAGATION_NEVER) {
                T1.insertRandom()
            }
        }
    }

    /**
     * [Propagation.MANDATORY] 테스트
     *
     * 이미 transaction이 존재하는 경우 수행되고, transaction이 없는 경우 예외를 발생시킨다.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `propagation mandatory with transaction`() {
        T1.insertRandom()
        transactionManager.execute(TransactionDefinition.PROPAGATION_MANDATORY) {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 2L
        }
        T1.selectAll().count() shouldBeEqualTo 2L
    }

    /**
     * Test for [Propagation.MANDATORY]
     *
     * Transaction이 존재하지 않으면 예외를 발생시킵니다.
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `propagation mandatory without transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            transactionManager.execute(TransactionDefinition.PROPAGATION_MANDATORY) {
                T1.insertRandom()
            }
        }
    }

    /**
     * Test for [Propagation.SUPPORTS]
     * 현 트랜잭션을 지원합니다. 만약 트랜잭션이 없으면, 없는대로 실행합니다.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `propagation support with transaction`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1L

        transactionManager.execute(TransactionDefinition.PROPAGATION_SUPPORTS) {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 2L
        }

        T1.selectAll().count() shouldBeEqualTo 2L
    }

    /**
     * Test for [Propagation.SUPPORTS]
     *
     * 트랜잭션이 없으므로, 없는 상태로 실행합니다.
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `propagation support without transaction`() {
        // @Transactional is not annotated
        transactionManager.execute(TransactionDefinition.PROPAGATION_SUPPORTS) {
            // Transaction이 없다는 예외가 발생한다.
            assertFailsWith<IllegalStateException> {
                T1.insertRandom()
            }
        }
    }

    /**
     * Test for Isolation Level `READ_UNCOMMITTED`
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RepeatedTest(REPEAT_SIZE)
    open fun `isolation level read committed`() {
        assertTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED)

        T1.insertRandom()
        val count = T1.selectAll().count()

        transactionManager.execute(
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            isolationLevel = TransactionDefinition.ISOLATION_READ_UNCOMMITTED
        ) {
            // 새로운 transaction 이비만, `ISOLATION_READ_UNCOMMITTED` 이므로, 다른 transaction의 변경사항을 볼 수 있다.
            assertTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_UNCOMMITTED)
            T1.selectAll().count() shouldBeEqualTo count
        }

        assertTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED)
    }

    /**
     * Test for Timeout
     * Execute with query timeout
     */
    @RepeatedTest(REPEAT_SIZE)
    open fun `transaction timeout`() {
        transactionManager.execute {
            repeat(100) {
                T1.insertRandom()
            }
        }
        try {
            transactionManager.execute(timeout = 1) {
                try {
                    TransactionManager.current().exec(
                        """
                        WITH RECURSIVE T(N) AS (
                           SELECT 1
                           UNION ALL
                           SELECT N+1 FROM T WHERE N < 1000000000
                       )
                       SELECT * FROM T;                     
                        """.trimIndent(),
                        explicitStatementType = StatementType.SELECT
                    )
                    fail("여기까지 실행되면 안됩니다. SQLTimeoutException이 발생해야 합니다.")
                } catch (cause: ExposedSQLException) {
                    log.info(cause) { "ExposedSQLException is thrown" }
                    cause.cause shouldBeInstanceOf SQLTimeoutException::class
                }
            }
        } catch (e: TransactionSystemException) {
            log.debug(e) { "TransactionSystemException is thrown" }
        }
    }

    private fun assertTransactionIsolationLevel(expectedIsolationLevel: Int) {
        TransactionManager.current().connection.transactionIsolation shouldBeEqualTo expectedIsolationLevel
    }
}
