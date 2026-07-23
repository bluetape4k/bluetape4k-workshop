package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRecoveryStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
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
    fun eventSourcedRebuildManagementService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        rebuilds: ProjectionRebuildRepository,
        audits: OperatorAuditRepository,
        clock: Clock,
    ): EventSourcedRebuildManagementService =
        EventSourcedRebuildManagementService(
            transactions =
                EventSourcedPermitTransactionRunner(
                    registration.database,
                    permits,
                    EventSourcedDatabaseLane.FOREGROUND,
                ),
            rebuilds = rebuilds,
            audits = audits,
            clock = clock,
        )

    @Bean
    fun projectionRecoveryManagementService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        persistence: ProjectionRecoveryPersistence,
        clock: Clock,
    ): ProjectionRecoveryManagementService =
        ProjectionRecoveryManagementService(
            transactions =
                EventSourcedPermitTransactionRunner(
                    registration.database,
                    permits,
                    EventSourcedDatabaseLane.FOREGROUND,
                ),
            persistence = persistence,
            clock = clock,
        )
}
