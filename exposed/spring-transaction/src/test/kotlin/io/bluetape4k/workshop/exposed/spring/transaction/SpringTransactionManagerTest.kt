package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.spring.SpringTransactionManager
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import kotlin.test.assertFailsWith

class SpringTransactionManagerTest {

    companion object: KLogging()

    private val ds1 = DataSourceSpy(::ConnectionSpy)
    private val con1 = ds1.con as ConnectionSpy

    private val ds2 = DataSourceSpy(::ConnectionSpy)
    private val con2 = ds2.con as ConnectionSpy

    @BeforeEach
    fun beforeEach() {
        con1.clearMock()
        con2.clearMock()
    }

    @AfterEach
    fun afterEach() {
        while (TransactionManager.currentOrNull() != null) {
            TransactionManager.defaultDatabase?.let { TransactionManager.closeAndUnregister(it) }
        }
    }

    @Test
    fun `set manager when transaction started`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert(false)
    }

    @Test
    fun `set right transaction manager when two transaction manager exists`() {
        val tm1 = SpringTransactionManager(ds1)
        tm1.executeAssert(false)

        val tm2 = SpringTransactionManager(ds2)
        tm2.executeAssert(false)
    }

    @Test
    fun `set right transaction manager when two transaction manager with nested transaction template`() {
        val tm1 = SpringTransactionManager(ds1)
        val tm2 = SpringTransactionManager(ds2)

        tm2.executeAssert(false) {
            tm1.executeAssert(false)
            TransactionManager.managerFor(TransactionManager.currentOrNull()?.db) shouldBeEqualTo TransactionManager.manager
        }
    }

    @Test
    fun `connection commit and close when transaction success`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert()

        con1.verifyCallOrder("setAutoCommit", "commit", "close").shouldBeTrue()
        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `connection rollback and close when transaction fail`() {
        val tm = SpringTransactionManager(ds1)
        val ex = RuntimeException("Application exception")
        try {
            tm.executeAssert { throw ex }
        } catch (e: RuntimeException) {
            e shouldBeEqualTo ex
        }
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `connection commit and closed when nested transaction success`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            tm.executeAssert()
        }
        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }


    @Test
    fun `connection commit and closed when two different transaction manager with nested transaction success`() {
        val tm1 = SpringTransactionManager(ds1)
        val tm2 = SpringTransactionManager(ds2)

        tm1.executeAssert {
            tm2.executeAssert()
            TransactionManager.managerFor(TransactionManager.currentOrNull()?.db) shouldBeEqualTo TransactionManager.manager
        }

        con2.commitCallCount shouldBeEqualTo 1
        con2.closeCallCount shouldBeEqualTo 1
        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `connection rollback and closed when two different transaction manager with nested transaction failed`() {
        val tm1 = SpringTransactionManager(ds1)
        val tm2 = SpringTransactionManager(ds2)
        val ex = RuntimeException("Application exception")
        try {
            tm1.executeAssert {
                tm2.executeAssert {
                    throw ex
                }
                TransactionManager.managerFor(TransactionManager.currentOrNull()?.db) shouldBeEqualTo TransactionManager.manager
            }
        } catch (e: Exception) {
            e shouldBeEqualTo ex
        }

        con2.commitCallCount shouldBeEqualTo 0
        con2.rollbackCallCount shouldBeEqualTo 1
        con2.closeCallCount shouldBeEqualTo 1

        con1.commitCallCount shouldBeEqualTo 0
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `transaction commit with lazy connection data source proxy`() {
        val lazyDs = LazyConnectionDataSourceProxy(ds1)
        val tm = SpringTransactionManager(lazyDs)
        tm.executeAssert()

        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `transaction rollback with lazy connection data source proxy`() {
        val lazyDs = LazyConnectionDataSourceProxy(ds1)
        val tm = SpringTransactionManager(lazyDs)
        val ex = RuntimeException("Application exception")
        try {
            tm.executeAssert {
                throw ex
            }
        } catch (e: Exception) {
            e shouldBeEqualTo ex
        }
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `transaction commit with transaction aware data source proxy`() {
        val transactionAwareDs = TransactionAwareDataSourceProxy(ds1)
        val tm = SpringTransactionManager(transactionAwareDs)
        tm.executeAssert()

        con1.verifyCallOrder("setAutoCommit", "commit").shouldBeTrue()
        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeGreaterThan 0
    }

    @Test
    fun `transaction rollback with transaction aware data source proxy`() {
        val transactionAwareDs = TransactionAwareDataSourceProxy(ds1)
        val tm = SpringTransactionManager(transactionAwareDs)
        val ex = RuntimeException("Application exception")
        try {
            tm.executeAssert {
                throw ex
            }
        } catch (e: Exception) {
            e shouldBeEqualTo ex
        }

        con1.verifyCallOrder("setAutoCommit", "rollback").shouldBeTrue()
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeGreaterThan 0
    }

    @Test
    fun `transaction exception on commit and rollback on commit failure`() {
        con1.mockCommit = { throw SQLException("Commit failure") }

        val tm = SpringTransactionManager(ds1)
        tm.isRollbackOnCommitFailure = true
        assertFailsWith<TransactionSystemException> {
            tm.executeAssert()
        }

        con1.verifyCallOrder("setAutoCommit", "commit", "isClosed", "rollback", "close").shouldBeTrue()
        con1.commitCallCount shouldBeEqualTo 1
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `transaction with exception on rollback`() {
        con1.mockRollback = { throw SQLException("Rollback failure") }

        val tm = SpringTransactionManager(ds1)
        assertFailsWith<TransactionSystemException> {
            tm.executeAssert {
                it.isRollbackOnly.shouldBeFalse()
                it.setRollbackOnly()
                it.isRollbackOnly.shouldBeTrue()
            }
        }

        con1.verifyCallOrder("setAutoCommit", "isClosed", "rollback", "close").shouldBeTrue()
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `nested transaction with commit`() {
        val tm = SpringTransactionManager(ds1, DatabaseConfig { useNestedTransactions = true })

        tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_NESTED) {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_NESTED)
            it.isNewTransaction.shouldBeTrue()
        }

        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `nested transaction with rollback`() {
        val tm = SpringTransactionManager(ds1, DatabaseConfig { useNestedTransactions = true })
        tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_NESTED) {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_NESTED) { status ->
                status.setRollbackOnly()
            }
            it.isNewTransaction.shouldBeTrue()
        }

        con1.rollbackCallCount shouldBeEqualTo 1
        con1.releaseSavepointCallCount shouldBeEqualTo 1
        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `requires new with commit`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW) { status ->
                status.isNewTransaction.shouldBeTrue()
            }
            it.isNewTransaction.shouldBeTrue()
        }

        con1.commitCallCount shouldBeEqualTo 2
        con1.closeCallCount shouldBeEqualTo 2
    }

    @Test
    fun `requires new with inner rollback`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW) { status ->
                status.isNewTransaction.shouldBeTrue()
                status.setRollbackOnly()
            }
            it.isNewTransaction.shouldBeTrue()
        }

        con1.commitCallCount shouldBeEqualTo 1
        con1.rollbackCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 2
    }

    @Test
    fun `not support with required transaction`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(
                initializeConnection = false,
                propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
            ) {
                assertFailsWith<IllegalStateException> {
                    TransactionManager.current().connection
                }
            }
            it.isNewTransaction.shouldBeTrue()
            TransactionManager.current().connection
        }

        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `mandatory with transaction`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_MANDATORY)
            it.isNewTransaction.shouldBeTrue()
            TransactionManager.current().connection
        }

        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `mandatory without transaction`() {
        val tm = SpringTransactionManager(ds1)
        assertFailsWith<IllegalTransactionStateException> {
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_MANDATORY)
        }
    }

    @Test
    fun `support with transaction`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert {
            it.isNewTransaction.shouldBeTrue()
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS)
            it.isNewTransaction.shouldBeTrue()
            TransactionManager.current().connection
        }

        con1.commitCallCount shouldBeEqualTo 1
        con1.closeCallCount shouldBeEqualTo 1
    }

    @Test
    fun `support without transaction`() {
        val tm = SpringTransactionManager(ds1)
        assertFailsWith<IllegalStateException> {
            tm.executeAssert(propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS)
        }
        tm.executeAssert(initializeConnection = false, propagationBehavior = TransactionDefinition.PROPAGATION_SUPPORTS)
        con1.commitCallCount shouldBeEqualTo 0
        con1.rollbackCallCount shouldBeEqualTo 0
        con1.closeCallCount shouldBeEqualTo 0
    }

    @Test
    fun `transaction timeout`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert(initializeConnection = true, timeout = 1) {
            TransactionManager.current().queryTimeout shouldBeEqualTo 1
        }
    }

    @Test
    fun `transaction timeout propagation`() {
        val tm = SpringTransactionManager(ds1)
        tm.executeAssert(initializeConnection = true, timeout = 1) {
            tm.executeAssert(initializeConnection = true, timeout = 2) {
                TransactionManager.current().queryTimeout shouldBeEqualTo 1
            }
            TransactionManager.current().queryTimeout shouldBeEqualTo 1
        }
    }

    fun SpringTransactionManager.executeAssert(
        initializeConnection: Boolean = true,
        propagationBehavior: Int = TransactionDefinition.PROPAGATION_REQUIRED,
        timeout: Int? = null,
        body: (TransactionStatus) -> Unit = {},
    ) {
        val tt = TransactionTemplate(this)
        tt.propagationBehavior = propagationBehavior
        timeout?.let { tt.timeout = it }
        tt.executeWithoutResult {
            TransactionManager.managerFor(TransactionManager.currentOrNull()?.db) shouldBeEqualTo TransactionManager.manager

            if (initializeConnection) TransactionManager.current().connection
            body(it)
        }
    }
}
