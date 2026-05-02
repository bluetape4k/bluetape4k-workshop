package io.bluetape4k.workshop.gateway.orders.controller

import kotlinx.coroutines.CoroutineScope
import org.amshove.kluent.shouldBeFalse
import org.junit.jupiter.api.Test

class ProductControllerScopeTest {

    @Test
    fun `product controller does not keep its own coroutine scope`() {
        (ProductController() is CoroutineScope).shouldBeFalse()
    }
}
