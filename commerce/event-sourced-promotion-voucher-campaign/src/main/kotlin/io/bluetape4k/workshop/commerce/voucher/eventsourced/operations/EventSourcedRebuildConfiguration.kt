package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRecoveryStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class EventSourcedRebuildConfiguration {
    @Bean
    fun projectionRebuildRepository(): ProjectionRebuildRepository = ProjectionRebuildRepository()

    @Bean
    fun operatorAuditRepository(): OperatorAuditRepository = OperatorAuditRepository()

    @Bean
    fun projectionRecoveryStore(): ProjectionRecoveryStore = ProjectionRecoveryStore()

    @Bean
    fun projectionRecoveryPersistence(
        projections: ProjectionRepository,
        leases: ProjectionLeaseRepository,
        poisons: ProjectionPoisonStore,
        recovery: ProjectionRecoveryStore,
        audits: OperatorAuditRepository,
    ): ProjectionRecoveryPersistence =
        ProjectionRecoveryPersistence(
            projections = projections,
            leases = leases,
            poisons = poisons,
            recovery = recovery,
            audits = audits,
        )

    @Bean
    fun eventSourcedForegroundTransactionRunner(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
    ): EventSourcedPermitTransactionRunner =
        EventSourcedPermitTransactionRunner(
            registration.database,
            permits,
            EventSourcedDatabaseLane.FOREGROUND,
        )

    @Bean
    fun eventSourcedRebuildManagementService(
        transactions: EventSourcedPermitTransactionRunner,
        rebuilds: ProjectionRebuildRepository,
        audits: OperatorAuditRepository,
        hmacKeyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedRebuildManagementService =
        EventSourcedRebuildManagementService(
            transactions = transactions,
            rebuilds = rebuilds,
            audits = audits,
            hmacKeyRing = hmacKeyRing,
            clock = clock,
        )

    @Bean
    fun projectionRecoveryManagementService(
        transactions: EventSourcedPermitTransactionRunner,
        persistence: ProjectionRecoveryPersistence,
        hmacKeyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): ProjectionRecoveryManagementService =
        ProjectionRecoveryManagementService(
            transactions = transactions,
            persistence = persistence,
            hmacKeyRing = hmacKeyRing,
            clock = clock,
        )
}
