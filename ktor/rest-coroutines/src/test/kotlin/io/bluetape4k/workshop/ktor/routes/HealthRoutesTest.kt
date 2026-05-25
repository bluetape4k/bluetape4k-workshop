package io.bluetape4k.workshop.ktor.routes

import io.bluetape4k.workshop.ktor.AbstractKtorTest
import io.bluetape4k.workshop.ktor.module
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class HealthRoutesTest : AbstractKtorTest() {

    @Test
    fun `GET health returns 200 OK`() = testApplication {
        application { module() }

        val response = client.get("/health")

        response.status shouldBeEqualTo HttpStatusCode.OK
    }
}
