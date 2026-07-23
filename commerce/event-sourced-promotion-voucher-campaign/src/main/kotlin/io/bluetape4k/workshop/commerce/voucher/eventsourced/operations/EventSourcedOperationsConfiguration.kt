package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.sql.Connection
import javax.sql.DataSource

/** Wires the bounded database admission gate and its low-cardinality operational meters. */
@Configuration(proxyBeanMethods = false)
internal class EventSourcedOperationsConfiguration {

    @Bean
    fun eventSourcedMetrics(registry: MeterRegistry): EventSourcedMetrics = EventSourcedMetrics(registry)

    @Bean
    fun eventSourcedDatabasePermitGate(metrics: EventSourcedMetrics): EventSourcedDatabasePermitGate =
        EventSourcedDatabasePermitGate(metrics = metrics).also(metrics::bind)

    @Bean
    fun eventSourcedHikariMetricsBinding(
        metrics: EventSourcedMetrics,
        dataSource: DataSource,
    ): EventSourcedHikariMetricsBinding {
        val hikari = checkNotNull(dataSource as? HikariDataSource) { "event-sourced datasource must use HikariCP" }
        metrics.bind(hikari)
        return EventSourcedHikariMetricsBinding
    }

    @Bean
    fun eventSourcedStartupProbe(dataSource: DataSource): EventSourcedStartupProbe =
        EventSourcedStartupProbe {
            dataSource.connection.use(::verifyAuthoritySchema)
        }

    private fun verifyAuthoritySchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SELECT 1")
            statement.executeQuery("SELECT to_regclass('voucher_event_log')").use { result ->
                check(result.next() && result.getString(1) != null) { "voucher_event_log schema is unavailable" }
            }
        }
    }
}

internal data object EventSourcedHikariMetricsBinding
