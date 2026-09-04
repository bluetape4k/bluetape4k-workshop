package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.exposed.ktor.core.bluetape4kExposedCoreErrors
import io.bluetape4k.exposed.ktor.core.bluetape4kExposedHealthRoutes
import io.bluetape4k.exposed.ktor.jdbc.bluetape4kExposedJdbcErrors
import io.bluetape4k.exposed.ktor.jdbc.exposedKtorJdbcReadinessProbe
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.bluetape4kErrorResponses
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds

internal fun Application.installKtorExposedRest(resources: KtorExposedRestResources) {
    monitor.subscribe(ApplicationStopped) {
        resources.close()
    }

    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installStatusPages = false,
            installHealthRoutes = false,
        )
    )
    install(StatusPages) {
        bluetape4kErrorResponses()
        bluetape4kExposedCoreErrors()
        bluetape4kExposedJdbcErrors()
    }

    val readinessProbes = listOf(
        exposedKtorJdbcReadinessProbe(
            db = resources.jdbcDatabase,
            blockingDispatcher = resources.jdbcDispatcher,
            jdbcQueryTimeout = 2.seconds,
        ),
    )

    routing {
        bluetape4kExposedHealthRoutes(
            probes = readinessProbes,
            readinessProbeTimeout = 2.seconds,
        )
        bookRoutes(resources)
    }
}

fun main() {
    val resources = KtorExposedRestResources.create(
        jdbcUrl = environmentValue("POSTGRES_JDBC_URL", "jdbc:postgresql://localhost:5432/postgres"),
        username = environmentValue("POSTGRES_USERNAME", "postgres"),
        password = environmentValue("POSTGRES_PASSWORD", "postgres"),
        driverClassName = environmentValue("POSTGRES_DRIVER_CLASS_NAME", "org.postgresql.Driver"),
    )
    embeddedServer(Netty, port = environmentValue("PORT", "8080").toInt()) {
        installKtorExposedRest(resources)
    }.start(wait = true)
}

private fun environmentValue(name: String, defaultValue: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(name.lowercase().replace('_', '.'))?.takeIf { it.isNotBlank() }
        ?: defaultValue
