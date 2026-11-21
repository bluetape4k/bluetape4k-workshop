package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.EventSourcedProjectionRuntime
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRecoveryStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRuntimeResources
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRuntimeTelemetry
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionWorkerProperties
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProjectionWorkerProperties::class)
/**
 * EventSourcedRebuildConfiguration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EventSourcedRebuildConfiguration {

    companion object: KLogging()

    /**
     * projectionRebuildRepository은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만
     * 안전하게
     * 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun projectionRebuildRepository(): ProjectionRebuildRepository = ProjectionRebuildRepository()

    /**
     * operatorAuditRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun operatorAuditRepository(): OperatorAuditRepository = OperatorAuditRepository()

    /**
     * projectionRecoveryStore은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만
     * 안전하게 재처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun projectionRecoveryStore(): ProjectionRecoveryStore = ProjectionRecoveryStore()

    /**
     * projectionRecoveryPersistence은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한
     * 항목만 안전하게
     * 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projections 업무 처리에 필요한 projections 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param leases 업무 처리에 필요한 leases 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param poisons 업무 처리에 필요한 poisons 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param recovery 업무 처리에 필요한 recovery 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param audits 업무 처리에 필요한 audits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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

    /**
     * eventSourcedForegroundTransactionRunner은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리
     * 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param permits 업무 처리에 필요한 permits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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

    /**
     * eventSourcedRebuildManagementService은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고,
     * 재시도 가능한
     * 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param transactions 업무 처리에 필요한 transactions 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param rebuilds 업무 처리에 필요한 rebuilds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param audits 업무 처리에 필요한 audits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param hmacKeyRing 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
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

    /**
     * projectionRecoveryManagementService은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도
     * 가능한 항목만
     * 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param transactions 업무 처리에 필요한 transactions 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param persistence 업무 처리에 필요한 persistence 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param hmacKeyRing 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
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

    /**
     * projectionRuntimeResources은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param permits 업무 처리에 필요한 permits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param leases 업무 처리에 필요한 leases 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param projections 업무 처리에 필요한 projections 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param rebuilds 업무 처리에 필요한 rebuilds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun projectionRuntimeResources(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        leases: ProjectionLeaseRepository,
        projections: ProjectionRepository,
        rebuilds: ProjectionRebuildRepository,
    ): ProjectionRuntimeResources =
        ProjectionRuntimeResources(
            database = registration.database,
            permits = permits,
            leases = leases,
            projections = projections,
            rebuilds = rebuilds,
        )

    /**
     * eventSourcedRuntimeWorkers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param operationalState 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedRuntimeWorkers(
        resources: ProjectionRuntimeResources,
        properties: ProjectionWorkerProperties,
        clock: Clock,
        operationalState: EventSourcedOperationalState,
        metrics: EventSourcedMetrics,
    ): EventSourcedRuntimeWorkers =
        EventSourcedProjectionRuntime(
            resources = resources,
            properties = properties,
            clock = clock,
            telemetry = ProjectionRuntimeTelemetry(operationalState, metrics),
        )
}
