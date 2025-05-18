package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.inventory.Inventory
import io.bluetape4k.workshop.spring.modulith.events.util.IntegrationTest
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeInstanceOf
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
