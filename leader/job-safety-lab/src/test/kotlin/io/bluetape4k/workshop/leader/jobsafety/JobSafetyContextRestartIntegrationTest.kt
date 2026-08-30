package io.bluetape4k.workshop.leader.jobsafety

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.leader.jobsafety.config.JobSafetyAuditScope
import io.bluetape4k.workshop.leader.jobsafety.domain.EffectDeliveryState
import io.bluetape4k.workshop.leader.jobsafety.domain.OperationId
import io.bluetape4k.workshop.leader.jobsafety.effect.DeterministicEffect
import io.bluetape4k.workshop.leader.jobsafety.effect.DeterministicExternalEffectAdapter
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectOperations
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectWorkResult
import io.bluetape4k.workshop.leader.jobsafety.effect.ExternalEffectPort
import io.bluetape4k.workshop.leader.jobsafety.persistence.JOB_SAFETY_TABLES
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobOutboxEntity
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobOutboxEntries
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyJdbcExecutor
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.net.http.HttpClient
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ExecutorService

@Tag("integration")
internal class JobSafetyContextRestartIntegrationTest {
    @Test
    fun `ambiguous effect survives context restart and duplicate delivery remains idempotent`() {
        val operationId = OperationId("restart-operation")
        provider.script(operationId, DeterministicEffect.APPLIED_BUT_TIMEOUT)

        lateinit var auditExporter: LeaderAuditExporter
        lateinit var auditScope: JobSafetyAuditScope
        lateinit var auditExecutor: ExecutorService
        lateinit var auditScheduler: ScheduledThreadPoolExecutor
        lateinit var auditHttpClient: HttpClient

        startContext().use { first ->
            auditExporter = first.getBean(LeaderAuditExporter::class.java)
            auditScope = first.getBean("jobSafetyAuditScope", JobSafetyAuditScope::class.java)
            auditExecutor = first.getBean("jobSafetyAuditExecutor", ExecutorService::class.java)
            auditScheduler = first.getBean("jobSafetyAuditScheduler", ScheduledThreadPoolExecutor::class.java)
            auditHttpClient = first.getBean("jobSafetyAuditHttpClient", HttpClient::class.java)
            resetSchema(first)
            seedOutbox(first, operationId)

            first.getBean(EffectOperations::class.java).deliverNext() shouldBeEqualTo
                EffectWorkResult.RECONCILIATION_REQUIRED
        }

        auditExporter.snapshot().closed.shouldBeTrue()
        auditExporter.snapshot().queued shouldBeEqualTo 0
        auditExporter.snapshot().inFlight shouldBeEqualTo 0
        auditExporter.snapshot().scheduledRetries shouldBeEqualTo 0
        auditScope.isActive.shouldBeFalse()
        auditExecutor.isTerminated.shouldBeTrue()
        auditScheduler.isTerminated.shouldBeTrue()
        auditScheduler.queue.shouldBeEmpty()
        auditHttpClient.isTerminated.shouldBeTrue()

        startContext().use { restarted ->
            val worker = restarted.getBean(EffectOperations::class.java)
            val repositories = restarted.getBean(JobSafetyRepositories::class.java)

            worker.reconcileNext() shouldBeEqualTo EffectWorkResult.CONFIRMED
            repositories.effectReceipt.count(provider.providerName, operationId) shouldBeEqualTo 1L

            requeue(restarted, operationId)
            worker.deliverNext() shouldBeEqualTo EffectWorkResult.CONFIRMED

            provider.applicationCount(operationId) shouldBeEqualTo 1
            repositories.effectReceipt.count(provider.providerName, operationId) shouldBeEqualTo 1L
        }
    }

    private fun startContext(): ConfigurableApplicationContext =
        SpringApplicationBuilder(JobSafetyApplication::class.java, SharedProviderConfiguration::class.java)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username ?: PostgreSQLServer.USERNAME}",
                "--spring.datasource.password=${postgres.password ?: PostgreSQLServer.PASSWORD}",
                "--spring.datasource.hikari.minimum-idle=0",
                "--workshop.job-safety.redis.uri=${redis.url}",
            )

    private fun resetSchema(context: ConfigurableApplicationContext) {
        context.getBean(JobSafetyJdbcExecutor::class.java).transaction {
            withExposed {
                SchemaUtils.drop(*JOB_SAFETY_TABLES.reversedArray())
                SchemaUtils.createMissingTablesAndColumns(*JOB_SAFETY_TABLES)
            }
        }
    }

    private fun seedOutbox(context: ConfigurableApplicationContext, operationId: OperationId) {
        val now = Instant.now()
        context.getBean(JobSafetyJdbcExecutor::class.java).transaction {
            JobOutboxEntity.new {
                this.operationId = operationId.value
                effectType = "SUMMARY_PUBLISHED"
                status = EffectDeliveryState.PENDING.name
                attemptCount = 0
                nextAttemptAt = now
                createdAt = now
                updatedAt = now
            }
        }
    }

    private fun requeue(context: ConfigurableApplicationContext, operationId: OperationId) {
        context.getBean(JobSafetyJdbcExecutor::class.java).transaction {
            withExposed {
                JobOutboxEntries.update({ JobOutboxEntries.operationId eq operationId.value }) {
                    it[status] = EffectDeliveryState.PENDING.name
                    it[updatedAt] = Instant.now()
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SharedProviderConfiguration {
        @Bean
        @Primary
        fun sharedExternalEffectPort(): ExternalEffectPort = provider
    }

    companion object {
        private val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
        private val redis: RedisServer = RedisServer.Launcher.redis
        private val provider = DeterministicExternalEffectAdapter()
    }
}
