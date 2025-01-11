package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Key
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.nullableTransactionScope
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager

private val logger = KotlinLogging.logger {}

private val registeredOnShutdown = mutableSetOf<TestDB>()

internal var currentTestDB by nullableTransactionScope<TestDB>()

object CurrentTestDBInterceptor: StatementInterceptor {
    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> {
        return userData.filterValues { it is TestDB }
    }
}

fun withDb(
    testDb: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
    logger.info { "Running `withDb` for $testDb" }

    val unregistered = testDb !in registeredOnShutdown
    val newConfiguration = configure != null && !unregistered

    if (unregistered) {
        testDb.beforeConnection()
        Runtimex.addShutdownHook {
            testDb.afterTestFinished()
            registeredOnShutdown.remove(testDb)
        }
        registeredOnShutdown += testDb
        testDb.db = testDb.connect(configure ?: {})
    }

    val registeredDb = testDb.db!!
    try {
        if (newConfiguration) {
            testDb.db = testDb.connect(configure ?: {})
        }
        val database = testDb.db!!
        transaction(database.transactionManager.defaultIsolationLevel, db = database) {
            maxAttempts = 1
            registerInterceptor(CurrentTestDBInterceptor)  // interceptor 를 통해 다양한 작업을 할 수 있다
            currentTestDB = testDb
            statement(testDb)
        }
    } finally {
        // revert any new configuration to not be carried over to the next test in suite
        if (configure != null) {
            testDb.db = registeredDb
        }
    }
}

suspend fun withSuspendedDb(
    testDb: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    logger.info { "Running withSuspendedDb for $testDb" }

    val unregistered = testDb !in registeredOnShutdown
    val newConfiguration = configure != null && !unregistered

    if (unregistered) {
        testDb.beforeConnection()
        Runtimex.addShutdownHook {
            testDb.afterTestFinished()
            registeredOnShutdown.remove(testDb)
        }
        registeredOnShutdown += testDb
        testDb.db = testDb.connect(configure ?: {})
    }

    val registeredDb = testDb.db!!

    try {
        if (newConfiguration) {
            testDb.db = testDb.connect(configure ?: {})
        }
        val database = testDb.db!!

        newSuspendedTransaction(
            db = database,
            transactionIsolation = database.transactionManager.defaultIsolationLevel
        ) {
            maxAttempts = 1
            registerInterceptor(CurrentTestDBInterceptor)
            currentTestDB = testDb
            statement(testDb)
        }
    } finally {
        // revert any new configuration to not be carried over to the next test in suite
        if (configure != null) {
            testDb.db = registeredDb
        }
    }
}
