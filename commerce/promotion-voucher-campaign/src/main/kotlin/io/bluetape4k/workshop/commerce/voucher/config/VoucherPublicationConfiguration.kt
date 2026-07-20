package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.spring.modulith.exposed.config.ExposedModulithProperties
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.reconciliation.AuditingVoucherDelayedEventHandler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.LettuceVoucherLeaderRunner
import io.bluetape4k.workshop.commerce.voucher.reconciliation.ReconciliationFaultInjector
import io.bluetape4k.workshop.commerce.voucher.reconciliation.SpringVoucherEventPublisher
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherDelayedEventHandler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherEventPublisher
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherInboxAppliedEventListener
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherLeaderRunner
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationScheduler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationWorker
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/** Wires durable Spring Modulith publication and the PostgreSQL-authoritative reconciliation path. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class VoucherPublicationConfiguration {
    @Bean
    fun eventPublicationRepository(
        eventPublicationTable: ExposedEventPublicationTable,
        eventPublicationArchiveTable: ExposedEventPublicationTable,
        serializer: EventSerializer,
        properties: ExposedModulithProperties,
    ): ExposedEventPublicationRepository =
        ExposedEventPublicationRepository(
            table = eventPublicationTable,
            archiveTable = eventPublicationArchiveTable,
            serializer = serializer,
            completionMode = properties.completionMode,
        ).also {
            log.info { "voucher_publication_repository_configured completionMode=${properties.completionMode}" }
        }

    @Bean
    fun voucherEventPublisher(events: ApplicationEventPublisher): VoucherEventPublisher =
        SpringVoucherEventPublisher(events)

    @Bean
    fun voucherDelayedEventHandler(
        audits: AuditRepository,
        events: VoucherEventPublisher,
    ): VoucherDelayedEventHandler = AuditingVoucherDelayedEventHandler(audits, events)

    @Bean
    fun voucherInboxAppliedEventListener(): VoucherInboxAppliedEventListener =
        VoucherInboxAppliedEventListener()

    @Bean
    fun voucherReconciliationService(
        jdbc: VoucherJdbcExecutor,
        inbox: EventInboxRepository,
        handler: VoucherDelayedEventHandler,
        clock: Clock,
        properties: VoucherProperties,
    ): VoucherReconciliationService =
        VoucherReconciliationService(
            jdbc = jdbc,
            inbox = inbox,
            handler = handler,
            clock = clock,
            transactionTimeout = properties.worker.transactionTimeout,
            claimOwner = properties.worker.instanceId,
            maxAttempts = properties.worker.maxAttempts,
            faultInjector = ReconciliationFaultInjector.NONE,
        )

    @Bean
    fun voucherLeaderRunner(
        resources: ObjectProvider<VoucherRedisResources>,
        properties: VoucherProperties,
    ): VoucherLeaderRunner =
        LettuceVoucherLeaderRunner(
            electorProvider = { resources.ifAvailable?.leaderElector() },
            instanceId = properties.worker.instanceId,
        )

    @Bean
    fun voucherReconciliationWorker(
        reconciliation: VoucherReconciliationService,
        properties: VoucherProperties,
        leader: VoucherLeaderRunner,
        degradation: VoucherDegradationState,
        metrics: VoucherMetrics,
    ): VoucherReconciliationWorker =
        VoucherReconciliationWorker(reconciliation, properties.worker, leader, degradation, metrics)

    @Bean
    @ConditionalOnProperty(prefix = "workshop.voucher.redis", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(
        prefix = "workshop.voucher.worker",
        name = ["scheduling-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun voucherReconciliationScheduler(worker: VoucherReconciliationWorker): VoucherReconciliationScheduler =
        VoucherReconciliationScheduler(worker)

    companion object : KLogging()
}
