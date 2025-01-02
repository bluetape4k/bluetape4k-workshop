package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.junit5.utils.MultiException
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
    db: Collection<TestDB>? = null,
    excludeSettings: Collection<TestDB> = emptyList(),
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
    val me = MultiException()

    TestDB.enabledDialects()
        .filter { db?.run { it in db } ?: false }
        .filter { it !in excludeSettings }
        .forEach { dbSettings ->
            try {
                withDb(dbSettings, configure, statement)
            } catch (e: Throwable) {
                me.add(e)
            }
        }

    me.throwIfNotEmpty()
}

fun withDb(
    dialect: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
//    if(dialect !in TestDB.enabledDialects()) {
//        return
//    }

    logger.info { "Running `withDb` for $dialect" }

    val unregistered = dialect !in registeredOnShutdown
    val newConfiguration = configure != null && !unregistered

    if (unregistered) {
        dialect.beforeConnection()
        Runtimex.addShutdownHook {
            dialect.afterTestFinished()
            registeredOnShutdown.remove(dialect)
        }
        registeredOnShutdown += dialect
        dialect.db = dialect.connect(configure ?: {})
    }

    val registeredDb = dialect.db!!
    try {
        if (newConfiguration) {
            dialect.db = dialect.connect(configure ?: {})
        }
        val database = dialect.db!!
        transaction(database.transactionManager.defaultIsolationLevel, db = database) {
            maxAttempts = 1
            registerInterceptor(CurrentTestDBInterceptor)  // interceptor 를 통해 다양한 작업을 할 수 있다
            currentTestDB = dialect
            statement(dialect)
        }
    } finally {
        // revert any new configuration to not be carried over to the next test in suite
        if (configure != null) {
            dialect.db = registeredDb
        }
    }
}

suspend fun withSuspendedDb(
    db: Collection<TestDB>? = null,
    excludeSettings: Collection<TestDB> = emptyList(),
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    val me = MultiException()
    TestDB.enabledDialects()
        .filter { db?.run { it in db } ?: false }
        .filter { it !in excludeSettings }
        .forEach { dbSettings ->
            try {
                withSuspendedDb(dbSettings, configure, statement)
            } catch (e: Throwable) {
                me.add(e)
            }
        }
    me.throwIfNotEmpty()
}

suspend fun withSuspendedDb(
    dbSettings: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
//    if (dbSettings !in TestDB.enabledDialects()) {
//        return
//    }

    logger.info { "Running withSuspendedDb for $dbSettings" }

    val unregistered = dbSettings !in registeredOnShutdown
    val newConfiguration = configure != null && !unregistered

    if (unregistered) {
        dbSettings.beforeConnection()
        Runtimex.addShutdownHook {
            dbSettings.afterTestFinished()
            registeredOnShutdown.remove(dbSettings)
        }
        registeredOnShutdown += dbSettings
        dbSettings.db = dbSettings.connect(configure ?: {})
    }

    val registeredDb = dbSettings.db!!

    try {
        if (newConfiguration) {
            dbSettings.db = dbSettings.connect(configure ?: {})
        }
        val database = dbSettings.db!!

        newSuspendedTransaction(
            db = database,
            transactionIsolation = database.transactionManager.defaultIsolationLevel
        ) {
            maxAttempts = 1
            registerInterceptor(CurrentTestDBInterceptor)
            currentTestDB = dbSettings
            statement(dbSettings)
        }
    } finally {
        // revert any new configuration to not be carried over to the next test in suite
        if (configure != null) {
            dbSettings.db = registeredDb
        }
    }
}
