package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.debug.junit5.CoroutinesTimeout
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.suspendedTransactionAsync
import org.junit.jupiter.api.RepeatedTest
import org.springframework.test.annotation.Commit
import org.springframework.transaction.annotation.Transactional

@Suppress("DEPRECATION")
open class SpringCoroutineTest: SpringTransactionTestBase() {

    companion object: KLogging() {
        private val timeout = CoroutinesTimeout(60_000)
        private const val REPEAT_SIZE = 5
    }

    /**
     * `IdTable<Int>` 와 같다.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS COROUTINE_TESTING (
     *      ID SERIAL PRIMARY KEY
     * );
     * ```
     */
    object Testing: Table("COROUTINE_TESTING") {
        val id = integer("id").autoIncrement()
        override val primaryKey = PrimaryKey(id)
    }

    @RepeatedTest(REPEAT_SIZE)
    @Transactional
    @Commit
    open fun `test nested coroutine transaction`() = runSuspendIO {
        try {
            newSuspendedTransaction {
                log.debug { "Create schema ..." }
                SchemaUtils.create(Testing)
            }

            val mainJob = async {
                val results = List(5) { indx ->
                    suspendedTransactionAsync {
                        Testing.insert { }
                        indx
                    }
                }.awaitAll()

                results.sum() shouldBeEqualTo 10   // 0 + 1 + 2 + 3 + 4
            }

            mainJob.await()

            newSuspendedTransaction {
                log.debug { "Load Testing entities ..." }
                Testing.selectAll().count().toInt() shouldBeEqualTo 5
            }
        } finally {
            newSuspendedTransaction {
                log.debug { "Drop schema ..." }
                SchemaUtils.drop(Testing)
            }
        }
    }
}
