package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.springframework.test.annotation.Commit
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLTimeoutException
import kotlin.random.Random
import kotlin.test.DefaultAsserter.fail

open class ExposedTransactionManagerTest: SpringTransactionTestBase() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

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

    @Transactional
    @Commit
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection combine with exposed transaction`() {
        transaction {
            val rnd = Random.nextInt().toString()
            T1.insert {
                it[c1] = rnd
            }
            T1.selectAll().single()[T1.c1] shouldBeEqualTo rnd

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
     * Test for Propagation.NESTED
     * Execute within a nested transaction if a current transaction exists, behave like REQUIRED otherwise.
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
     * Test for Propagation.NESTED with inner roll-back
     * The nested transaction will be roll-back only inner transaction when the transaction marks as rollback.
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

        T1.selectAll().count() shouldBeEqualTo 1L
    }

    /**
     * Test for Propagation.NESTED with outer roll-back
     * The nested transaction will be roll-back entire transaction when the transaction marks as rollback.
     */
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with nested transaction outer rollback`() {
        transactionManager.execute {
            T1.insertRandom()
            T1.selectAll().count() shouldBeEqualTo 1L
            it.setRollbackOnly()

            // NESTED Transaction
            transactionManager.execute(TransactionDefinition.PROPAGATION_NESTED) { status ->
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
     * Test for Propagation.REQUIRES_NEW
     * Create a new transaction, and suspend the current transaction if one exists.
     */
    @Transactional
    @RepeatedTest(REPEAT_SIZE)
    open fun `connection with required new`() {
        T1.insertRandom()
        T1.selectAll().count() shouldBeEqualTo 1L

        // REQUIRES_NEW Transaction
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

            transactionManager.execute(TransactionDefinition.PROPAGATION_REQUIRES_NEW) { status ->
                T1.insertRandom()
                T1.selectAll().count() shouldBeEqualTo 1L
                status.setRollbackOnly()
            }

            T1.selectAll().count() shouldBeEqualTo 1L
        }

        transactionManager.execute {
            T1.selectAll().count() shouldBeEqualTo 1L
        }
    }

    /**
     * Test for [Propagation.NEVER]
     * Execute non-transactionally, throw an exception if a transaction exists.
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
     * Test for [Propagation.MANDATORY]
     * Support a current transaction, throw an exception if none exists.
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
     * Throw an exception cause no transaction exists.
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
     * Support a current transaction, execute non-transactionally if none exists.
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
     * Execute non-transactionally if none exists.
     */
    @RepeatedTest(REPEAT_SIZE)
    fun `propagation support without transaction`() {
        // @Transactional is not annotated
        transactionManager.execute(TransactionDefinition.PROPAGATION_SUPPORTS) {
            assertFailsWith<IllegalStateException> {
                T1.insertRandom()
            }
        }
    }

    /**
     * Test for Isolation Level
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
                fail("Should throw SQLTimeoutException")
            } catch (cause: ExposedSQLException) {
                cause.cause shouldBeInstanceOf SQLTimeoutException::class
            }
        }
    }

    private fun assertTransactionIsolationLevel(expectedIsolationLevel: Int) {
        TransactionManager.current().connection.transactionIsolation shouldBeEqualTo expectedIsolationLevel
    }
}
