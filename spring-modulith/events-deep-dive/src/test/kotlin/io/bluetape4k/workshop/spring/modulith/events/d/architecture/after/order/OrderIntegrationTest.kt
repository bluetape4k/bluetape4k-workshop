package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.inventory.Inventory
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.dao.InvalidDataAccessApiUsageException

@IntegrationTest
class OrderIntegrationTest(
    private val orders: OrderManagement,
    private val inventory: Inventory,
) {

    companion object: KLogging()

    @Test
    fun `transaction rollback if inventory update fails`() {
        inventory.fail = true

        assertFailsWith<InvalidDataAccessApiUsageException> {
            orders.completeOrder(Order())
        }.cause shouldBeInstanceOf IllegalStateException::class

        inventory.fail = false
    }
}
