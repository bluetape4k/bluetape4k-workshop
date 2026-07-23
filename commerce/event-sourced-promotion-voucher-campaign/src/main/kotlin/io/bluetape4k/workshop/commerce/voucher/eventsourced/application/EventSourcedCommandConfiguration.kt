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
        events: EventStoreRepository,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedCampaignCommands = DefaultEventSourcedCampaignCommands(commands, events, identities, keyRing, clock)

    @Bean
    fun eventSourcedVoucherCommands(
        commands: EventSourcedCommandService,
        events: EventStoreRepository,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedVoucherCommands = DefaultEventSourcedVoucherCommands(commands, events, identities, keyRing, clock)

    @Bean
    fun eventSourcedVoucherLifecycleCommands(
        commands: EventSourcedCommandService,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        vouchers: EventSourcedVoucherCommands,
        clock: Clock,
    ): EventSourcedVoucherLifecycleCommands =
        DefaultEventSourcedVoucherLifecycleCommands(commands, identities, keyRing, vouchers, clock)

    @Bean
    fun campaignCommandHttpService(
        commands: EventSourcedCampaignCommands,
        snapshots: CampaignProjectionSnapshotReader,
    ): CampaignCommandHttpService = CampaignCommandHttpService(commands, snapshots)

    @Bean
    fun voucherCommandHttpService(
        commands: EventSourcedVoucherCommands,
        lifecycle: EventSourcedVoucherLifecycleCommands,
        snapshots: CampaignProjectionSnapshotReader,
    ): io.bluetape4k.workshop.commerce.voucher.eventsourced.web.VoucherCommandHttpService =
        io.bluetape4k.workshop.commerce.voucher.eventsourced.web.VoucherCommandHttpService(
            commands,
            lifecycle,
            snapshots,
        )
}
