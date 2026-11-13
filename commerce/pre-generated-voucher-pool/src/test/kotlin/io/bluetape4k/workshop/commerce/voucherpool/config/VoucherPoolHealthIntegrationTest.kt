@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.http.HttpStatus
import java.time.Duration
import javax.sql.DataSource

@Suppress("VarCouldBeVal")
internal class VoucherPoolHealthIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    private companion object: KLogging() {
        val FORBIDDEN_TAGS = setOf("tenant", "campaign", "batch", "allocation", "request", "user", "digest", "code")
    }

    private val management by lazy { testClient(managementPort) }

    @LocalManagementPort
    private var managementPort: Int = 0

    @Autowired
    private lateinit var healthState: VoucherPoolHealthState

    @Autowired
    private lateinit var metrics: VoucherPoolMetrics

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `startup applies the authoritative migration before readiness opens`() {
        val applied = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM voucher_pool_schema_history WHERE version='001'",
            ).executeQuery().use { result ->
                result.singleLong()
            }
        }

        applied shouldBeEqualTo 1L

        management.get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun `management uses the common sixty second timeout and exposes only health and metrics`() {
        HTTP_TIMEOUT shouldBeEqualTo 60.seconds()

        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isNotFound
        management.get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")

        management.get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")

        management.get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk

        listOf("env", "configprops", "heapdump", "threaddump").forEach { endpoint ->
            management.get()
                .uri("/actuator/$endpoint")
                .exchange()
                .expectStatus().isNotFound
        }
    }

    @Test
    fun `advisory failure degrades readiness while process liveness stays up`() {
        healthState.degrade(VoucherPoolHealthComponent.REDIS, VoucherPoolHealthReason.REDIS_UNAVAILABLE)
        try {
            management.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.status").isEqualTo("DEGRADED")
                .jsonPath("$.components.voucherPoolReadiness.details.reason").isEqualTo("REDIS_UNAVAILABLE")

            management.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")

        } finally {
            healthState.recover(VoucherPoolHealthComponent.REDIS)
        }
    }

    @Test
    fun `authoritative failure makes readiness down without exposing arbitrary reasons`() {
        healthState.fail(VoucherPoolHealthComponent.REFERENCED_KEYS, VoucherPoolHealthReason.REFERENCED_KEY_UNAVAILABLE)
        try {
            management.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.components.voucherPoolReadiness.details.reason").isEqualTo("REFERENCED_KEY_UNAVAILABLE")
        } finally {
            healthState.recover(VoucherPoolHealthComponent.REFERENCED_KEYS)
        }
    }

    @Test
    fun `health matrix distinguishes migration quarantine leader and recovery`() {
        healthState.fail(VoucherPoolHealthComponent.MIGRATION, VoucherPoolHealthReason.MIGRATION_UNAVAILABLE)
        healthState.snapshot().level shouldBeEqualTo VoucherPoolHealthLevel.DOWN
        healthState.recover(VoucherPoolHealthComponent.MIGRATION)

        healthState.degrade(VoucherPoolHealthComponent.QUARANTINE, VoucherPoolHealthReason.QUARANTINED_ENTRY)
        healthState.snapshot().level shouldBeEqualTo VoucherPoolHealthLevel.DEGRADED
        healthState.recover(VoucherPoolHealthComponent.QUARANTINE)

        healthState.degrade(VoucherPoolHealthComponent.LEADER, VoucherPoolHealthReason.LEADER_UNAVAILABLE)
        healthState.snapshot().level shouldBeEqualTo VoucherPoolHealthLevel.DEGRADED
        healthState.recover(VoucherPoolHealthComponent.LEADER)

        healthState.recovering()
        healthState.snapshot().level shouldBeEqualTo VoucherPoolHealthLevel.RECOVERING
        healthState.recover(VoucherPoolHealthComponent.RECOVERY)
    }

    @Test
    fun `operational metrics and alert windows remain bounded`() {
        metrics.hikari(active = 3, pending = 1)
        metrics.permitWait(PermitLane.FOREGROUND, "accepted", 5.millis())
        metrics.timedOut(JdbcExecutionLane.WORKER, JdbcTimeoutPhase.TRANSACTION)
        metrics.worker(WorkerKind.RECONCILIATION, backlog = 4, oldestAge = 31.seconds(), checkpoint = 7)
        metrics.sseSubscribers(2)
        metrics.sseReset()
        metrics.eligiblePoolRatio(0.09)
        metrics.degraded(VoucherPoolHealthComponent.REDIS)
        metrics.quarantine(1)
        metrics.purgeLag(25.hours())
        metrics.restoreFailed()

        meterRegistry.get("voucher.pool.hikari.pending").gauge().value() shouldBeEqualTo 1.0
        meterRegistry.get("voucher.pool.permit.wait").timer().count() shouldBeEqualTo 1L
        meterRegistry.get("voucher.pool.jdbc.timeout").counter().count() shouldBeGreaterThan 0.0
        meterRegistry.meters.flatMap { it.id.tags }.none { it.key in FORBIDDEN_TAGS }.shouldBeTrue()

        VoucherPoolAlertPolicy[VoucherPoolAlert.HIKARI_PENDING].duration shouldBeEqualTo 10.seconds()
        VoucherPoolAlertPolicy[VoucherPoolAlert.WORKER_NO_PROGRESS].duration shouldBeEqualTo 30.seconds()
        VoucherPoolAlertPolicy[VoucherPoolAlert.REDIS_DEGRADED].duration shouldBeEqualTo 30.seconds()
        VoucherPoolAlertPolicy[VoucherPoolAlert.ELIGIBLE_DEPTH_LOW].threshold shouldBeEqualTo 0.10
        VoucherPoolAlertPolicy[VoucherPoolAlert.SSE_RESET_BURST].threshold shouldBeEqualTo 10.0
        VoucherPoolAlertPolicy[VoucherPoolAlert.QUARANTINE_PRESENT].threshold shouldBeEqualTo 1.0
        VoucherPoolAlertPolicy[VoucherPoolAlert.PURGE_LAG].duration shouldBeEqualTo 24.hours()
        VoucherPoolAlertPolicy[VoucherPoolAlert.RESTORE_FAILURE].duration shouldBeEqualTo Duration.ZERO
    }
}
