package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun eventSourcedStartupProbe(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
    ): EventSourcedStartupProbe =
        EventSourcedPermitTransactionRunner(
            registration.database,
            permits,
            EventSourcedDatabaseLane.READINESS,
        ).let { transactions ->
            EventSourcedStartupProbe {
                transactions.inTransaction {
                    val transaction = TransactionManager.current()
                    transaction.exec("SELECT 1")
                    val authorityAvailable =
                        transaction.exec("SELECT to_regclass('voucher_event_log')") { result ->
                            result.next() && result.getString(1) != null
                        } ?: false
                    check(authorityAvailable) {
                        "voucher_event_log schema is unavailable"
                    }
                }
            }
        }
}

internal data object EventSourcedHikariMetricsBinding
