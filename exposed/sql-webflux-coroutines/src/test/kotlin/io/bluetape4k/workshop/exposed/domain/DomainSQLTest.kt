package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.junit5.concurrency.VirtualthreadTester
import io.bluetape4k.junit5.coroutines.MultijobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.exposed.AbstractExposedSqlTest
import io.bluetape4k.workshop.exposed.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.domain.schema.Actors
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class DomainSQLTest: AbstractExposedSqlTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    @Nested
    inner class Coroutines {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in coroutines`() = runSuspendIO {
            newSuspendedTransaction {
                val actors = Actors.selectAll().map { it.toActorDTO() }
                actors.shouldNotBeEmpty()
            }
        }

        @Test
        fun `get all actors in multiple coroutines`() = runSuspendIO {
            MultijobTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerJob(4)
                .add {
                    newSuspendedTransaction {
                        // addLogger(StdOutSqlLogger)
                        val actors = Actors.selectAll().map { it.toActorDTO() }
                        actors.shouldNotBeEmpty()
                    }
                }
                .run()
        }
    }

    @Nested
    inner class VirtualThread {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in virtual threads`() {
            virtualFuture {
                transaction {
                    val actors = Actors.selectAll().map { it.toActorDTO() }
                    actors.shouldNotBeEmpty()
                }
            }.get()
        }

        @Test
        fun `get all actors in multiple virtual threads`() {
            VirtualthreadTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerThread(4)
                .add {
                    transaction {
                        // addLogger(StdOutSqlLogger)
                        val actors = Actors.selectAll().map { it.toActorDTO() }
                        actors.shouldNotBeEmpty()
                    }
                }
                .run()
        }
    }
}
