package io.bluetape4k.workshop.cassandra.streamnullable

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.cassandra.AbstractCassandraTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate
import java.time.ZoneId

@SpringBootTest(classes = [StreamNullableTestConfiguration::class])
class JavaTimesTest(
    @Autowired private val repository: OrderRepository,
): AbstractCassandraTest() {

    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() {
        repository.deleteAll()
    }

    @Test
    fun `find customer by jsr310 types`() {
        val order = Order("42", LocalDate.now(), ZoneId.systemDefault())
        repository.save(order)

        val loaded = repository.findOrderByOrderDateAndZoneId(order.orderDate, order.zoneId)
        loaded.shouldNotBeNull()
        loaded shouldBeEqualTo order
    }
}
