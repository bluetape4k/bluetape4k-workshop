package io.bluetape4k.workshop.flow.parallel.enrichment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.coroutines.tests.withParallels
import io.bluetape4k.junit5.coroutines.runSuspendTest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test

class OrderEnrichmentPipelineTest {

    private val profileService = CustomerProfileService(
        profiles = mapOf(
            "customer-1001" to LoyaltyGrade.GOLD,
            "customer-1002" to LoyaltyGrade.SILVER,
            "customer-1003" to LoyaltyGrade.REGULAR
        ),
        ioDelayMillis = 5L
    )
    private val inventoryService = InventoryService(
        stockBySku = mapOf(
            "suit-01" to 12,
            "shoe-01" to 5,
            "bag-01" to 1
        ),
        ioDelayMillis = 5L
    )
    private val promotionService = PromotionService(ioDelayMillis = 5L)
    private val pipeline = OrderEnrichmentPipeline(profileService, inventoryService, promotionService)

    @Test
    fun `parallel enrichment enriches valid orders in parallel`() = runSuspendTest {
        withParallels(3) { dispatchers ->
            val input = flowOf(
                OrderCommand("O-1001", "customer-1001", listOf(OrderItem("suit-01", 2))),
                OrderCommand("O-1002", "customer-1002", listOf(OrderItem("shoe-01", 1), OrderItem("bag-01", 1))),
                OrderCommand("O-1003", "customer-1003", listOf(OrderItem("bag-01", 2)))
            )

            val enriched = pipeline.enrichInParallel(input, dispatchers.size) { dispatchers[it] }
                .toList()

            enriched shouldHaveSize 3
            val byId = enriched.associateBy { it.orderId }
            byId["O-1001"]?.let {
                it.loyaltyGrade shouldBeEqualTo LoyaltyGrade.GOLD
                it.discountPercent shouldBeEqualTo 10
                it.fulfillable.shouldBeTrue()
                it.unavailableSkus shouldHaveSize 0
            }.shouldNotBeNull()
            byId["O-1002"]?.let {
                it.fulfillable.shouldBeTrue()
            }.shouldNotBeNull()
            byId["O-1003"]?.let {
                it.fulfillable.shouldBeFalse()
                it.unavailableSkus shouldBeEqualTo listOf("bag-01")
            }.shouldNotBeNull()
        }
    }

    @Test
    fun `sequential and parallel enrichment return the same logical results`() = runSuspendTest {
        val input = flowOf(
            OrderCommand("O-1001", "customer-1001", listOf(OrderItem("suit-01", 2))),
            OrderCommand("O-1002", "customer-1002", listOf(OrderItem("shoe-01", 1))),
            OrderCommand("O-1003", "customer-1003", listOf(OrderItem("bag-01", 1)))
        )

        val sequential = pipeline.enrichSequentially(input).toList()

        withParallels(2) { dispatchers ->
            val parallel = pipeline.enrichInParallel(input, dispatchers.size) { dispatchers[it] }
                .toList()

            sequential.toSet() shouldBeEqualTo parallel.toSet()
        }
    }

    @Test
    fun `invalid commands are filtered out before enrichment`() = runSuspendTest {
        val input = flowOf(
            OrderCommand("O-EMPTY", "customer-1001", emptyList()),
            OrderCommand("", "customer-1002", listOf(OrderItem("suit-01", 1))),
            OrderCommand("O-VALID", "", listOf(OrderItem("shoe-01", 1))),
            OrderCommand("O-OK", "customer-1001", listOf(OrderItem("suit-01", 1)))
        )

        val enriched = pipeline.enrichSequentially(input).toList()

        enriched.map { it.orderId } shouldBeEqualTo listOf("O-OK")
    }

    @Test
    fun `parallel enrichment fails when customer profile is missing`() = runSuspendTest {
        val input = flowOf(
            OrderCommand("O-unknown", "missing-customer", listOf(OrderItem("suit-01", 1)))
        )

        withParallels(1) { dispatchers ->
            assertFailsWith<UnknownCustomerException> {
                pipeline.enrichInParallel(input, dispatchers.size) { dispatchers[it] }.toList()
            }
        }
    }

    @Test
    fun `parallel enrichment fails when product is missing`() = runSuspendTest {
        val input = flowOf(
            OrderCommand("O-no-product", "customer-1001", listOf(OrderItem("no-sku", 1)))
        )

        withParallels(1) { dispatchers ->
            assertFailsWith<UnknownProductException> {
                pipeline.enrichInParallel(input, dispatchers.size) { dispatchers[it] }.toList()
            }
        }
    }

    @Test
    fun `only fulfillable orders can be satisfied`() = runSuspendTest {
        val input = flowOf(
            OrderCommand("O-full", "customer-1001", listOf(OrderItem("bag-01", 1))),
            OrderCommand("O-short", "customer-1002", listOf(OrderItem("bag-01", 3)))
        )

        var result = emptyList<EnrichedOrder>()
        withParallels(2) { dispatchers ->
            result = pipeline.enrichInParallel(input, dispatchers.size) { dispatchers[it % dispatchers.size] }
                .toList()
        }
        val map = result.associateBy { it.orderId }

        val fullOrder = map["O-full"].shouldNotBeNull()
        val shortOrder = map["O-short"].shouldNotBeNull()

        fullOrder.fulfillable.shouldBeTrue()
        fullOrder.unavailableSkus shouldBeEqualTo emptyList<String>()
        shortOrder.fulfillable.shouldBeFalse()
        shortOrder.unavailableSkus shouldBeEqualTo listOf("bag-01")
    }
}
