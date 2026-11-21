package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.EventSourcedIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedMetrics
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.PermittedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignCommandHttpService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionSnapshotReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.VoucherCommandHttpService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
/**
 * EventSourcedCommandConfiguration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EventSourcedCommandConfiguration {

    companion object: KLogging()

    /**
     * `eventSourcedIdempotencyRepository`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventSourcedIdempotencyRepository()
     * ```
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun eventSourcedIdempotencyRepository(): EventSourcedIdempotencyRepository =
        EventSourcedIdempotencyRepository()

    /**
     * `eventStoreRepository`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventStoreRepository(registration = registration, permits = permits, metrics
     * = metrics)
     * ```
     * @param registration 요청 경계에서 검증된 `registration`입니다.
     * @param permits 요청 경계에서 검증된 `permits`입니다.
     * @param metrics 요청 경계에서 검증된 `metrics`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun eventStoreRepository(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        metrics: EventSourcedMetrics,
    ): EventStoreRepository =
        EventStoreRepository(PermittedEventStoreTransactionRunner(registration.database, permits), metrics)

    /**
     * `eventSourcedCommandService`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventSourcedCommandService(registration = registration, permits = permits,
     * receipts =
     * receipts, events = events, keyRing = keyRing, metrics = metrics)
     * ```
     * @param registration 요청 경계에서 검증된 `registration`입니다.
     * @param permits 요청 경계에서 검증된 `permits`입니다.
     * @param receipts 요청 경계에서 검증된 `receipts`입니다.
     * @param events 요청 경계에서 검증된 `events`입니다.
     * @param keyRing 요청 경계에서 검증된 `keyRing`입니다.
     * @param metrics 요청 경계에서 검증된 `metrics`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    @Suppress("LongParameterList") // Explicit Spring bean dependencies document the command commit boundary.
    fun eventSourcedCommandService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        receipts: EventSourcedIdempotencyRepository,
        events: EventStoreRepository,
        keyRing: EventSourcedHmacKeyRing,
        metrics: EventSourcedMetrics,
    ): EventSourcedCommandService =
        EventSourcedCommandService(
            transactions = ExposedCommandTransactionRunner(registration.database, permits),
            receipts = receipts,
            eventStore = events,
            keyVersionAvailable = keyRing::isAvailable,
            metrics = metrics,
        )

    /**
     * `eventSourcedCampaignCommands`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventSourcedCampaignCommands(commands = commands, events = events, identities
     * =
     * identities, keyRing = keyRing, clock = clock)
     * ```
     * @param commands 요청 경계에서 검증된 `commands`입니다.
     * @param events 요청 경계에서 검증된 `events`입니다.
     * @param identities 요청 경계에서 검증된 `identities`입니다.
     * @param keyRing 요청 경계에서 검증된 `keyRing`입니다.
     * @param clock 요청 경계에서 검증된 `clock`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun eventSourcedCampaignCommands(
        commands: EventSourcedCommandService,
        events: EventStoreRepository,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedCampaignCommands = DefaultEventSourcedCampaignCommands(commands, events, identities, keyRing, clock)

    /**
     * `eventSourcedVoucherCommands`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventSourcedVoucherCommands(commands = commands, events = events, identities =
     * identities, keyRing = keyRing, clock = clock)
     * ```
     * @param commands 요청 경계에서 검증된 `commands`입니다.
     * @param events 요청 경계에서 검증된 `events`입니다.
     * @param identities 요청 경계에서 검증된 `identities`입니다.
     * @param keyRing 요청 경계에서 검증된 `keyRing`입니다.
     * @param clock 요청 경계에서 검증된 `clock`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun eventSourcedVoucherCommands(
        commands: EventSourcedCommandService,
        events: EventStoreRepository,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): EventSourcedVoucherCommands = DefaultEventSourcedVoucherCommands(commands, events, identities, keyRing, clock)

    /**
     * `eventSourcedVoucherLifecycleCommands`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = eventSourcedVoucherLifecycleCommands(commands = commands, identities =
     * identities,
     * keyRing = keyRing, vouchers = vouchers, clock = clock)
     * ```
     * @param commands 요청 경계에서 검증된 `commands`입니다.
     * @param identities 요청 경계에서 검증된 `identities`입니다.
     * @param keyRing 요청 경계에서 검증된 `keyRing`입니다.
     * @param vouchers 요청 경계에서 검증된 `vouchers`입니다.
     * @param clock 요청 경계에서 검증된 `clock`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun eventSourcedVoucherLifecycleCommands(
        commands: EventSourcedCommandService,
        identities: SubjectIdentityService,
        keyRing: EventSourcedHmacKeyRing,
        vouchers: EventSourcedVoucherCommands,
        clock: Clock,
    ): EventSourcedVoucherLifecycleCommands =
        DefaultEventSourcedVoucherLifecycleCommands(commands, identities, keyRing, vouchers, clock)

    /**
     * `campaignCommandHttpService`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = campaignCommandHttpService(commands = commands, snapshots = snapshots)
     * ```
     * @param commands 요청 경계에서 검증된 `commands`입니다.
     * @param snapshots 요청 경계에서 검증된 `snapshots`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun campaignCommandHttpService(
        commands: EventSourcedCampaignCommands,
        snapshots: CampaignProjectionSnapshotReader,
    ): CampaignCommandHttpService = CampaignCommandHttpService(commands, snapshots)

    /**
     * `voucherCommandHttpService`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = voucherCommandHttpService(commands = commands, lifecycle = lifecycle,
     * snapshots =
     * snapshots)
     * ```
     * @param commands 요청 경계에서 검증된 `commands`입니다.
     * @param lifecycle 요청 경계에서 검증된 `lifecycle`입니다.
     * @param snapshots 요청 경계에서 검증된 `snapshots`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Bean
    fun voucherCommandHttpService(
        commands: EventSourcedVoucherCommands,
        lifecycle: EventSourcedVoucherLifecycleCommands,
        snapshots: CampaignProjectionSnapshotReader,
    ): VoucherCommandHttpService =
        VoucherCommandHttpService(
            commands,
            lifecycle,
            snapshots,
        )
}
