package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.UUID
import java.util.concurrent.Executors

internal class OrderEventStreamTest {
    @Test
    fun `failed initial snapshot releases the connection slot`() {
        val queries = mockk<OrderLifecycleQueryService>()
        every { queries.snapshot(any()) } throws NoSuchElementException("order not found")
        val executor = Executors.newSingleThreadExecutor()
        val stream =
            OrderEventStream(
                queries,
                executor,
                MockEnvironment().withProperty("order-lifecycle.sse.max-connections", "1")
            )

        try {
            repeat(2) {
                assertFailsWith<NoSuchElementException> {
                    stream.open(UUID.randomUUID(), 0)
                }
            }
        } finally {
            stream.destroy()
            executor.shutdownNow()
        }
    }
}
