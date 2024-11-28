package io.bluetape4k.workshop.exposed.virtualthread.domain

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.VirtualthreadTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import io.bluetape4k.workshop.exposed.virtualthread.domain.mapper.toActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.schema.Actors
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class DomainSQLTest(
    @Autowired private val db: Database,
): AbstractExposedTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    @Nested
    inner class PlatformThread {

        @RepeatedTest(REPEAT_SIZE)
        fun `get all actors in platform threads`() {
            transaction {
                val actors = Actors.selectAll().map { it.toActorDTO() }
                actors.shouldNotBeEmpty()
            }
        }

        @Test
        fun `get all actors in multiple platform threads`() {
            MultithreadingTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerThread(4)
                .add {
                    transaction(db) {
                        addLogger(StdOutSqlLogger)
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
            }.await()
        }

        @Test
        fun `get all actors in multiple virtual threads`() {
            VirtualthreadTester()
                .numThreads(Runtimex.availableProcessors * 2)
                .roundsPerThread(4)
                .add {
                    transaction(db) {
                        addLogger(StdOutSqlLogger)
                        val actors = Actors.selectAll().map { it.toActorDTO() }
                        actors.shouldNotBeEmpty()
                    }
                }
                .run()
        }
    }
}
