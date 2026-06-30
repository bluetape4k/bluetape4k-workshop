package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.bluetape4kExposedErrors
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
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
        bluetape4kExposedErrors()
        bluetape4kErrorResponses()
    }
    installBluetape4kExposedKtor(
        Bluetape4kExposedKtorConfig(
            jdbcDatabase = resources.jdbcDatabase,
            jdbcBlockingDispatcher = resources.jdbcDispatcher,
            installHealthRoutes = true,
            readinessProbeTimeout = 2.seconds,
            jdbcQueryTimeout = 2.seconds,
        )
    )

    routing {
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
