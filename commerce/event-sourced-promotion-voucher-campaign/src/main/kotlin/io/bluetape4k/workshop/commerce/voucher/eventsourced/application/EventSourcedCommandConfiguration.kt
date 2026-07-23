package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.EventSourcedIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignCommandHttpService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionSnapshotReader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class EventSourcedCommandConfiguration {
    @Bean
    fun eventSourcedIdempotencyRepository(): EventSourcedIdempotencyRepository =
        EventSourcedIdempotencyRepository()

    @Bean
    fun eventStoreRepository(registration: EventSourcedExposedDatabaseRegistration): EventStoreRepository =
        EventStoreRepository(ExposedEventStoreTransactionRunner(registration.database))

    @Bean
    fun eventSourcedCommandService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        receipts: EventSourcedIdempotencyRepository,
        events: EventStoreRepository,
        keyRing: EventSourcedHmacKeyRing,
    ): EventSourcedCommandService =
        EventSourcedCommandService(
            transactions = ExposedCommandTransactionRunner(registration.database, permits),
            receipts = receipts,
            eventStore = events,
            keyVersionAvailable = keyRing::isAvailable,
        )

    @Bean
    fun eventSourcedCampaignCommands(
        commands: EventSourcedCommandService,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedCampaignCommands = DefaultEventSourcedCampaignCommands(commands, identities, keyRing, clock)

    @Bean
    fun campaignCommandHttpService(
        commands: EventSourcedCampaignCommands,
        snapshots: CampaignProjectionSnapshotReader,
    ): CampaignCommandHttpService = CampaignCommandHttpService(commands, snapshots)
}
