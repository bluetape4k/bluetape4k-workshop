@file:Suppress("TooManyFunctions") // Explicit bean methods keep the production graph auditable and unambiguous.

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcAllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcCampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcRedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcVoucherPoolRevocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcVoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.RedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolRevocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryStore
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.query.VoucherPoolQueryStore
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkers
import io.bluetape4k.workshop.commerce.voucherpool.worker.VoucherPoolWorkers
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class VoucherPoolServiceConfiguration {
    @Bean
    fun voucherPoolRuntimeKeys(provider: VoucherPoolKeyMaterialProvider): VoucherPoolRuntimeKeys = provider.load()

    @Bean
    fun voucherDigestService(keys: VoucherPoolRuntimeKeys): VoucherDigestService = keys.digests

    @Bean
    fun voucherKekRing(keys: VoucherPoolRuntimeKeys): VoucherKekRing = keys.kekRing

    @Bean
    fun voucherPoolMigrationRunner(dataSource: DataSource): VoucherPoolMigrationRunner =
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = VOUCHER_POOL_MIGRATION_LOCK_KEY,
        )

    @Bean
    fun voucherPoolReferencedKeyPreflight(
        dataSource: DataSource,
        digests: VoucherDigestService,
        kekRing: VoucherKekRing,
    ): VoucherPoolReferencedKeyPreflight = VoucherPoolReferencedKeyPreflight(dataSource, digests, kekRing)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["startup-initializer-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun voucherPoolStartupInitializer(
        migration: VoucherPoolMigrationRunner,
        keyPreflight: VoucherPoolReferencedKeyPreflight,
        health: VoucherPoolHealthState,
    ): VoucherPoolStartupInitializer = VoucherPoolStartupInitializer(migration, keyPreflight, health)

    @Bean
    fun voucherPoolRepository(): VoucherPoolRepository = JdbcVoucherPoolRepository()

    @Bean
    fun voucherEnvelopeCrypto(kekRing: VoucherKekRing, digests: VoucherDigestService): VoucherEnvelopeCrypto =
        AesGcmVoucherEnvelopeCrypto(kekRing, digests)

    @Bean
    fun voucherPoolRetentionPolicy(): VoucherPoolRetentionPolicy = VoucherPoolRetentionPolicy()

    @Bean
    fun voucherPoolRetention(dataSource: DataSource, policy: VoucherPoolRetentionPolicy): VoucherPoolRetention =
        VoucherPoolRetention(dataSource, policy)

    @Bean
    fun voucherPoolIdempotencyRepository(
        digests: VoucherDigestService,
        policy: VoucherPoolRetentionPolicy,
    ): VoucherPoolIdempotencyRepository =
        JdbcVoucherPoolIdempotencyRepository(digests, descriptorRetention = policy.descriptor)

    @Bean
    fun voucherCryptoStorage(repository: VoucherPoolRepository, crypto: VoucherEnvelopeCrypto): VoucherCryptoStorage =
        VoucherCryptoStorage(repository, crypto)

    @Bean
    fun campaignBatchCommandService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        crypto: VoucherEnvelopeCrypto,
    ): CampaignBatchCommandService = JdbcCampaignBatchCommandService(executor, repository, idempotency, digests, crypto)

    @Bean
    fun reservationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
    ): ReservationService = JdbcReservationService(executor, repository, idempotency, digests)

    @Bean
    fun allocationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        cryptoStorage: VoucherCryptoStorage,
    ): AllocationService = JdbcAllocationService(executor, repository, idempotency, digests, cryptoStorage)

    @Bean
    fun redemptionService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
    ): RedemptionService = JdbcRedemptionService(executor, repository, idempotency, digests)

    @Bean
    fun voucherPoolQueryStore(): VoucherPoolQueryStore = JdbcVoucherPoolQueryStore()

    @Bean
    fun voucherPoolQueryService(
        executor: VoucherPoolJdbcExecutor,
        store: VoucherPoolQueryStore,
        digests: VoucherDigestService,
    ): VoucherPoolQueryService = JdbcVoucherPoolQueryService(executor, store, digests)

    @Bean
    fun voucherPoolWorkerRepository(executor: VoucherPoolJdbcExecutor): JdbcVoucherPoolWorkerRepository =
        JdbcVoucherPoolWorkerRepository(executor)

    @Bean
    fun voucherPoolRevocationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        claims: JdbcVoucherPoolWorkerRepository,
    ): VoucherPoolRevocationService =
        JdbcVoucherPoolRevocationService(executor, repository, idempotency, digests, claims)

    @Bean
    fun voucherPoolReconciliationCommandService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        claims: JdbcVoucherPoolWorkerRepository,
    ): VoucherPoolReconciliationCommandService =
        JdbcVoucherPoolReconciliationCommandService(executor, repository, idempotency, claims)

    @Bean
    fun voucherPoolWorkers(
        executor: VoucherPoolJdbcExecutor,
        claims: JdbcVoucherPoolWorkerRepository,
        repository: VoucherPoolRepository,
    ): VoucherPoolWorkers = JdbcVoucherPoolWorkers(executor, claims, repository)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["worker-dispatcher-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun voucherPoolWorkerTrigger(
        workers: VoucherPoolWorkers,
        runtime: VoucherPoolRuntimeControl,
        leaders: ObjectProvider<VoucherPoolLeaderTrigger>,
    ): VoucherPoolWorkerTrigger = VoucherPoolWorkerTrigger(workers, runtime, leaders.ifAvailable)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["worker-dispatcher-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun voucherPoolWorkerDispatcher(
        claims: JdbcVoucherPoolWorkerRepository,
        trigger: VoucherPoolWorkerTrigger,
        properties: VoucherPoolProperties,
    ): VoucherPoolWorkerDispatcher = VoucherPoolWorkerDispatcher(claims, trigger, properties.workerInstanceId)

    private companion object {
        const val VOUCHER_POOL_MIGRATION_LOCK_KEY = 537_001L
    }
}
