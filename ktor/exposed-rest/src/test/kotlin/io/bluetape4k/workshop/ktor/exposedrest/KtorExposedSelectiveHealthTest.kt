package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.ktor.core.ExposedKtorCooperativeReadinessProbe
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.exposed.ktor.core.bluetape4kExposedCoreErrors
import io.bluetape4k.exposed.ktor.core.bluetape4kExposedHealthRoutes
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KtorExposedSelectiveHealthTest {

    @Test
    fun `core readiness route enforces one bounded deadline without a backend adapter`() = testApplication {
        application {
            installBluetape4kKtorCore(Bluetape4kKtorCoreConfig(installStatusPages = false))
            routing {
                bluetape4kExposedHealthRoutes(
                    probes = listOf(SlowProbe),
                    readinessProbeTimeout = 10.milliseconds,
                )
            }
        }

        val readiness = bluetape4kJsonClient()
            .get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
            .decodeJsonBody<HealthResponse>()

        readiness shouldBeEqualTo HealthResponse.down(mapOf("slow" to "TIMEOUT"))
    }

    @Test
    fun `core status pages expose fixed error catalog and redact the original cause`() = testApplication {
        application {
            installBluetape4kKtorCore(Bluetape4kKtorCoreConfig(installStatusPages = false))
            install(StatusPages) {
                bluetape4kExposedCoreErrors()
            }
            routing {
                get("/failure") {
                    val cause = IllegalStateException(
                        "jdbc:postgresql://db.internal/secret?user=admin password=top-secret select * from accounts"
                    )
                    throw ExposedKtorTransactionException().also { it.initCause(cause) }
                }
            }
        }

        val body = bluetape4kJsonClient()
            .get("/failure")
            .shouldHaveStatus(HttpStatusCode.InternalServerError)
            .bodyAsText()

        body shouldNotContain "jdbc:postgresql://db.internal"
        body shouldNotContain "top-secret"
        body shouldNotContain "select * from accounts"
        body shouldNotContain "admin"
    }

    private object SlowProbe : ExposedKtorCooperativeReadinessProbe {
        override val component: String = "slow"
        override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.JDBC

        override suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome {
            delay(1.seconds)
            return ExposedKtorReadinessOutcome.UP
        }
    }
}
