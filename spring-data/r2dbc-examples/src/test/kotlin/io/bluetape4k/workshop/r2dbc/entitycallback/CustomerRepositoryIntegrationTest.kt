package io.bluetape4k.workshop.r2dbc.entitycallback

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient

@SpringBootTest(classes = [ApplicationConfiguration::class])
class CustomerRepositoryIntegrationTest(
    @param:Autowired private val repository: CustomerRepository,
    @param:Autowired private val database: DatabaseClient,
) {
    companion object : KLoggingChannel()

    @Test
    fun `context loading`() {
        repository.shouldNotBeNull()
        database.shouldNotBeNull()
    }

    @Test
    fun `generates id on insert`() = runSuspendIO {
        val dave = Customer("Dave", "Matthews")

        val saved = repository.save(dave)

        dave.hasId.shouldBeFalse()
        saved.hasId.shouldBeTrue()
    }
}
