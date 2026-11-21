package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfiguration
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionQuery
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionSnapshotReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.ExposedCampaignProjectionSnapshotReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.WaitingCampaignProjectionQuery
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import javax.sql.DataSource
/**
 * EventSourcedExposedDatabaseRegistration은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property database 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


internal data class EventSourcedExposedDatabaseRegistration(
    /**
     * `database`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val database: Database,
)

@Configuration(proxyBeanMethods = false)
/**
 * EventSourcedPersistenceConfiguration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이,
 * 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EventSourcedPersistenceConfiguration {

    companion object: KLogging()

    /**
     * bluetape4k Exposed factory를 유지하면서 Boot의 DataSource ordering gap을 메웁니다.
     *
     * published auto-configuration은 현재 이 application에서 Boot 4가 Hikari DataSource를 등록하기 전에
     * `@ConditionalOnBean(DataSource::class)`를 평가합니다.
     */
    @Bean("springTransactionManager")
    fun springTransactionManager(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig,
    ): PlatformTransactionManager =
        ExposedSpringDataAutoConfiguration().springTransactionManager(dataSource, databaseConfig)

    /**
     * eventSourcedExposedDatabaseRegistration은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency
     * key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param transactionManager 업무 처리에 필요한 transactionManager 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedExposedDatabaseRegistration(
        @Qualifier("springTransactionManager")
        transactionManager: PlatformTransactionManager,
    ): EventSourcedExposedDatabaseRegistration {
        check(transactionManager is SpringTransactionManager) {
            "bluetape4k Exposed springTransactionManager is required"
        }

        return EventSourcedExposedDatabaseRegistration(
            TransactionTemplate(transactionManager)
                .execute {
                    TransactionManager.current().db
                }
                .checkNotNull { "bluetape4k Exposed transaction did not expose its database" },
        )
    }

    /**
     * eventSourcedClock은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun eventSourcedClock(): Clock = Clock.systemUTC()

    /**
     * projectionLeaseRepository은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자
     * 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun projectionLeaseRepository(): ProjectionLeaseRepository = ProjectionLeaseRepository()

    /**
     * projectionPoisonStore은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun projectionPoisonStore(): ProjectionPoisonStore = ProjectionPoisonStore()

    /**
     * projectionRepository은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param leases 업무 처리에 필요한 leases 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param poisons 업무 처리에 필요한 poisons 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun projectionRepository(
        leases: ProjectionLeaseRepository,
        poisons: ProjectionPoisonStore,
    ): ProjectionRepository =
        ProjectionRepository(leases = leases, poisons = poisons)

    /**
     * campaignProjectionSnapshotReader은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나
     * 캐시만으로 현재
     * 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param permits 업무 처리에 필요한 permits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun campaignProjectionSnapshotReader(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        repository: ProjectionRepository,
    ): CampaignProjectionSnapshotReader =
        ExposedCampaignProjectionSnapshotReader(
            database = registration.database,
            permits = permits,
            repository = repository,
        )

    /**
     * campaignProjectionQuery은 프로모션 바우처의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param snapshots 업무 처리에 필요한 snapshots 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean
    fun campaignProjectionQuery(
        snapshots: CampaignProjectionSnapshotReader,
        clock: Clock,
    ): CampaignProjectionQuery =
        WaitingCampaignProjectionQuery(snapshots, clock)

}
