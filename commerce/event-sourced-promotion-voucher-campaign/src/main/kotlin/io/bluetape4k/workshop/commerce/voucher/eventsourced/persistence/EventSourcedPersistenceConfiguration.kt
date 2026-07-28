package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfiguration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionLeaseRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionPoisonStore
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionQuery
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.CampaignProjectionSnapshotReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.ExposedCampaignProjectionSnapshotReader
import io.bluetape4k.workshop.commerce.voucher.eventsourced.web.WaitingCampaignProjectionQuery
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

internal data class EventSourcedExposedDatabaseRegistration(
    val database: Database,
)

@Configuration(proxyBeanMethods = false)
internal class EventSourcedPersistenceConfiguration {

    /**
     * bluetape4k Exposed factory를 유지하면서 Boot의 DataSource ordering gap을 메웁니다.
     *
     * published auto-configuration은 현재 이 application에서 Boot 4가 Hikari DataSource를 등록하기 전에
     * `@ConditionalOnBean(DataSource::class)`를 평가합니다.
     */
    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        ExposedSpringDataAutoConfiguration().springTransactionManager(dataSource)

    @Bean
    fun eventSourcedExposedDatabaseRegistration(
        @Qualifier("springTransactionManager")
        transactionManager: PlatformTransactionManager,
    ): EventSourcedExposedDatabaseRegistration {
        check(transactionManager is SpringTransactionManager) {
            "bluetape4k Exposed springTransactionManager is required"
        }
        return EventSourcedExposedDatabaseRegistration(
            checkNotNull(
                TransactionTemplate(transactionManager).execute {
                    TransactionManager.current().db
                },
            ) {
                "bluetape4k Exposed transaction did not expose its database"
            },
        )
    }

    @Bean
    fun eventSourcedClock(): Clock = Clock.systemUTC()

    @Bean
    fun projectionLeaseRepository(): ProjectionLeaseRepository = ProjectionLeaseRepository()

    @Bean
    fun projectionPoisonStore(): ProjectionPoisonStore = ProjectionPoisonStore()

    @Bean
    fun projectionRepository(
        leases: ProjectionLeaseRepository,
        poisons: ProjectionPoisonStore,
    ): ProjectionRepository = ProjectionRepository(leases = leases, poisons = poisons)

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

    @Bean
    fun campaignProjectionQuery(
        snapshots: CampaignProjectionSnapshotReader,
        clock: Clock,
    ): CampaignProjectionQuery = WaitingCampaignProjectionQuery(snapshots, clock)

}
