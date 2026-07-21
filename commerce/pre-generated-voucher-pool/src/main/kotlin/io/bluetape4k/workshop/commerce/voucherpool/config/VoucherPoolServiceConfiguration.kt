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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
internal class VoucherPoolServiceConfiguration {
    @Bean
    fun voucherPoolRuntimeKeys(provider: VoucherPoolKeyMaterialProvider): VoucherPoolRuntimeKeys = provider.load()

    @Bean
    fun voucherDigestService(keys: VoucherPoolRuntimeKeys): VoucherDigestService = keys.digests

    @Bean
    fun voucherKekRing(keys: VoucherPoolRuntimeKeys): VoucherKekRing = keys.kekRing

    @Bean
    fun voucherPoolRepository(dataSource: DataSource): VoucherPoolRepository = JdbcVoucherPoolRepository(dataSource)

    @Bean
    fun voucherEnvelopeCrypto(kekRing: VoucherKekRing, digests: VoucherDigestService): VoucherEnvelopeCrypto =
        AesGcmVoucherEnvelopeCrypto(kekRing, digests)

    @Bean
    fun voucherPoolIdempotencyRepository(digests: VoucherDigestService): VoucherPoolIdempotencyRepository =
        JdbcVoucherPoolIdempotencyRepository(digests)

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
}
