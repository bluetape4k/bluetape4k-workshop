@file:Suppress("TooManyFunctions") // Explicit bean methods keep the production graph auditable and unambiguous.

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcAllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcCampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcRedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcVoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcVoucherPoolRevocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.RedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolReconciliationCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolRevocationService
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
/**
 * VoucherPoolServiceConfiguration은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherPoolServiceConfiguration {

    private companion object {
        /**
         * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
         */
        const val VOUCHER_POOL_MIGRATION_LOCK_KEY = 537_001L
    }

    /**
     * voucherPoolRuntimeKeys은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param provider 업무 처리에 필요한 provider 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolRuntimeKeys(provider: VoucherPoolKeyMaterialProvider): VoucherPoolRuntimeKeys =
        provider.load()

    /**
     * voucherDigestService은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keys 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun voucherDigestService(keys: VoucherPoolRuntimeKeys): VoucherDigestService =
        keys.digests

    /**
     * voucherKekRing은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keys 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun voucherKekRing(keys: VoucherPoolRuntimeKeys): VoucherKekRing =
        keys.kekRing

    /**
     * voucherPoolMigrationRunner은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean
    fun voucherPoolMigrationRunner(dataSource: DataSource): VoucherPoolMigrationRunner =
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = VOUCHER_POOL_MIGRATION_LOCK_KEY,
        )

    /**
     * voucherPoolReferencedKeyPreflight은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에
     * 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param kekRing 업무 처리에 필요한 kekRing 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolReferencedKeyPreflight(
        dataSource: DataSource,
        digests: VoucherDigestService,
        kekRing: VoucherKekRing,
    ): VoucherPoolReferencedKeyPreflight =
        VoucherPoolReferencedKeyPreflight(dataSource, digests, kekRing)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["startup-initializer-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    /**
     * voucherPoolStartupInitializer은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은
     * revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param migration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param keyPreflight 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param health 업무 처리에 필요한 health 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun voucherPoolStartupInitializer(
        migration: VoucherPoolMigrationRunner,
        keyPreflight: VoucherPoolReferencedKeyPreflight,
        health: VoucherPoolHealthState,
    ): VoucherPoolStartupInitializer =
        VoucherPoolStartupInitializer(migration, keyPreflight, health)

    /**
     * voucherPoolRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun voucherPoolRepository(): VoucherPoolRepository =
        JdbcVoucherPoolRepository()

    /**
     * voucherEnvelopeCrypto은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param kekRing 업무 처리에 필요한 kekRing 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun voucherEnvelopeCrypto(kekRing: VoucherKekRing, digests: VoucherDigestService): VoucherEnvelopeCrypto =
        AesGcmVoucherEnvelopeCrypto(kekRing, digests)

    /**
     * voucherPoolRetentionPolicy은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun voucherPoolRetentionPolicy(): VoucherPoolRetentionPolicy = VoucherPoolRetentionPolicy()

    /**
     * voucherPoolRetention은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param policy 업무 처리에 필요한 policy 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolRetention(dataSource: DataSource, policy: VoucherPoolRetentionPolicy): VoucherPoolRetention =
        VoucherPoolRetention(dataSource, policy)

    /**
     * voucherPoolIdempotencyRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param policy 업무 처리에 필요한 policy 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolIdempotencyRepository(
        digests: VoucherDigestService,
        policy: VoucherPoolRetentionPolicy,
    ): VoucherPoolIdempotencyRepository =
        JdbcVoucherPoolIdempotencyRepository(digests, descriptorRetention = policy.descriptor)

    /**
     * voucherCryptoStorage은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param crypto 업무 처리에 필요한 crypto 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherCryptoStorage(
        repository: VoucherPoolRepository,
        crypto: VoucherEnvelopeCrypto,
    ): VoucherCryptoStorage =
        VoucherCryptoStorage(repository, crypto)

    /**
     * campaignBatchCommandService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param crypto 업무 처리에 필요한 crypto 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun campaignBatchCommandService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        crypto: VoucherEnvelopeCrypto,
    ): CampaignBatchCommandService =
        JdbcCampaignBatchCommandService(executor, repository, idempotency, digests, crypto)

    /**
     * reservationService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun reservationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
    ): ReservationService = JdbcReservationService(executor, repository, idempotency, digests)

    /**
     * allocationService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param cryptoStorage 업무 처리에 필요한 cryptoStorage 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun allocationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        cryptoStorage: VoucherCryptoStorage,
    ): AllocationService = JdbcAllocationService(executor, repository, idempotency, digests, cryptoStorage)

    /**
     * redemptionService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun redemptionService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
    ): RedemptionService = JdbcRedemptionService(executor, repository, idempotency, digests)

    /**
     * voucherPoolQueryStore은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun voucherPoolQueryStore(): VoucherPoolQueryStore = JdbcVoucherPoolQueryStore()

    /**
     * voucherPoolQueryService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param store 업무 처리에 필요한 store 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     */
    @Bean
    fun voucherPoolQueryService(
        executor: VoucherPoolJdbcExecutor,
        store: VoucherPoolQueryStore,
        digests: VoucherDigestService,
    ): VoucherPoolQueryService = JdbcVoucherPoolQueryService(executor, store, digests)

    /**
     * voucherPoolWorkerRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolWorkerRepository(executor: VoucherPoolJdbcExecutor): JdbcVoucherPoolWorkerRepository =
        JdbcVoucherPoolWorkerRepository(executor)

    /**
     * voucherPoolRevocationService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param digests 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param claims 업무 처리에 필요한 claims 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolRevocationService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        digests: VoucherDigestService,
        claims: JdbcVoucherPoolWorkerRepository,
    ): VoucherPoolRevocationService =
        JdbcVoucherPoolRevocationService(executor, repository, idempotency, digests, claims)

    /**
     * voucherPoolReconciliationCommandService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리
     * 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param idempotency 업무 처리에 필요한 idempotency 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claims 업무 처리에 필요한 claims 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolReconciliationCommandService(
        executor: VoucherPoolJdbcExecutor,
        repository: VoucherPoolRepository,
        idempotency: VoucherPoolIdempotencyRepository,
        claims: JdbcVoucherPoolWorkerRepository,
    ): VoucherPoolReconciliationCommandService =
        JdbcVoucherPoolReconciliationCommandService(executor, repository, idempotency, claims)

    /**
     * voucherPoolWorkers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param claims 업무 처리에 필요한 claims 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherPoolWorkers(
        executor: VoucherPoolJdbcExecutor,
        claims: JdbcVoucherPoolWorkerRepository,
        repository: VoucherPoolRepository,
    ): VoucherPoolWorkers =
        JdbcVoucherPoolWorkers(executor, claims, repository)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["worker-dispatcher-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    /**
     * voucherPoolWorkerTrigger은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param workers 업무 처리에 필요한 workers 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param runtime 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param leaders 업무 처리에 필요한 leaders 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun voucherPoolWorkerTrigger(
        workers: VoucherPoolWorkers,
        runtime: VoucherPoolRuntimeControl,
        leaders: ObjectProvider<VoucherPoolLeaderTrigger>,
    ): VoucherPoolWorkerTrigger =
        VoucherPoolWorkerTrigger(workers, runtime, leaders.ifAvailable)

    @Bean
    @ConditionalOnProperty(
        prefix = "workshop.voucher-pool",
        name = ["worker-dispatcher-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    /**
     * voucherPoolWorkerDispatcher은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복
     * 소비 방지와
     * 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param claims 업무 처리에 필요한 claims 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param trigger 업무 처리에 필요한 trigger 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun voucherPoolWorkerDispatcher(
        claims: JdbcVoucherPoolWorkerRepository,
        trigger: VoucherPoolWorkerTrigger,
        properties: VoucherPoolProperties,
    ): VoucherPoolWorkerDispatcher =
        VoucherPoolWorkerDispatcher(claims, trigger, properties.workerInstanceId)
}
