package io.bluetape4k.workshop.r2dbc.basics

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient

@SpringBootTest(classes = [InfrastructureConfiguration::class])
class CustomerRepositoryIntegrationTest(
    @param:Autowired private val customerRepo: CustomerRepository,
    @param:Autowired private val database: DatabaseClient,
) {
    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() = runSuspendIO {
        val statements = listOf(
            "DROP TABLE IF EXISTS customer;",
            "CREATE TABLE customer (id SERIAL PRIMARY KEY, firstname VARCHAR(100) NOT NULL, lastname VARCHAR(100) NOT NULL);",
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
    fun `execute find all`() = runSuspendIO {
        val dave = Customer("Dave", "Matthews")
        val carter = Customer("Carter", "Beauford")

        insertCustomers(dave, carter)

        val customers = customerRepo.findAll().toList()

        customers shouldBeEqualTo listOf(dave, carter)
    }

    @Test
    fun `execute annotated query`() = runSuspendIO {
        val dave = Customer("Dave", "Matthews")
        val carter = Customer("Carter", "Beauford")

        insertCustomers(dave, carter)

        val customer = customerRepo.findByLastname("Matthews").first()
        customer.shouldNotBeNull() shouldBeEqualTo dave
    }


    private suspend fun insertCustomers(vararg customers: Customer) {
        customerRepo.saveAll(customers.toList()).collect()
    }
}
