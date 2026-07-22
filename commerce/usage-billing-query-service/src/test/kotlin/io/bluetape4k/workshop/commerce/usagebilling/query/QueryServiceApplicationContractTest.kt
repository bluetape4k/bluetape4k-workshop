package io.bluetape4k.workshop.commerce.usagebilling.query

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class QueryServiceApplicationContractTest {
    @Test
    fun `query service exposes its own boot application class`() {
        Class.forName("io.bluetape4k.workshop.commerce.usagebilling.query.QueryServiceApplication").name shouldBeEqualTo
            "io.bluetape4k.workshop.commerce.usagebilling.query.QueryServiceApplication"
    }
}
