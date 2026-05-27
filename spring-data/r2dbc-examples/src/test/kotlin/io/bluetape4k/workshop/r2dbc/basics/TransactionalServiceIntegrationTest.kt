package io.bluetape4k.workshop.r2dbc.basics

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import io.bluetape4k.assertions.assertFailsWith

@SpringBootTest(classes = [InfrastructureConfiguration::class])
class TransactionalServiceIntegrationTest @Autowired constructor(
    private val service: TransactionalService,
    private val repository: CustomerRepository,
    private val database: DatabaseClient,
) {
    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() = runSuspendIO {
        val statements = listOf(
            "DROP TABLE IF EXISTS customer;",
            """
            CREATE TABLE customer (
                id SERIAL PRIMARY KEY,
                firstname VARCHAR(100) NOT NULL,
                lastname VARCHAR(100) NOT NULL
            );
            """.trimIndent(),
        )

        statements.forEach {
            database.sql(it)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    @Test
    fun `context loading`() {
        database.shouldNotBeNull()
    }

    @Test
    fun `exception triggers rollback`() = runSuspendIO {

        // Dave 저장 시 예외가 발생하여 Rollback 하게 된다.
        assertFailsWith<IllegalStateException> {
            service.save(Customer("Dave", "Matthews"))
        }

        repository.findByLastname("Matthews").firstOrNull().shouldBeNull()
    }

    @Test
    fun `insert data transactionally`() = runSuspendIO {
        // 저장된다.
        service.save(Customer("Carter", "Beauford")).hasId.shouldBeTrue()

        repository.findByLastname("Beauford").firstOrNull().shouldNotBeNull().hasId.shouldBeTrue()
    }
}
