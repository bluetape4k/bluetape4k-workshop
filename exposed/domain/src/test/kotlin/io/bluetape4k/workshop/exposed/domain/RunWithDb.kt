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
    db: Collection<TestDB>? = null,
    excludeSettings: Collection<TestDB> = emptyList(),
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
    TestDB.enabledDialects()
        .filterNot { db != null && it !in db }
        .filterNot { it in excludeSettings }
        .filter { it in TestDB.enabledDialects() }
        .forEach { dbSettings ->
            runCatching {
                withDb(dbSettings, configure, statement)
            }
        }
}

fun withDb(
    dbSettings: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: Transaction.(TestDB) -> Unit,
) {
    if (dbSettings !in TestDB.enabledDialects()) {
        return
    }

    logger.info { "Running `withDb` for $dbSettings" }

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
    if (newConfiguration) {
        dbSettings.db = dbSettings.connect(configure ?: {})
    }
    val database = dbSettings.db!!
    transaction(database.transactionManager.defaultIsolationLevel, db = database) {
        maxAttempts = 1
        registerInterceptor(CurrentTestDBInterceptor)
        currentTestDB = dbSettings
        statement(dbSettings)
    }

    // revert any new configuration to not be carried over to the next test in suite
    if (configure != null) {
        dbSettings.db = registeredDb
    }
}

suspend fun withSuspendedDb(
    db: Collection<TestDB>? = null,
    excludeSettings: Collection<TestDB> = emptyList(),
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    TestDB.enabledDialects()
        .filterNot { db != null && it !in db }
        .filterNot { it in excludeSettings }
        .filter { it in TestDB.enabledDialects() }
        .forEach { dbSettings ->
            runCatching {
                withSuspendedDb(dbSettings, configure, statement)
            }
        }
}

suspend fun withSuspendedDb(
    dbSettings: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend Transaction.(TestDB) -> Unit,
) {
    if (dbSettings !in TestDB.enabledDialects()) {
        return
    }

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

    // revert any new configuration to not be carried over to the next test in suite
    if (configure != null) {
        dbSettings.db = registeredDb
    }
}
