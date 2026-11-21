package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.spring.modulith.exposed.config.ExposedModulithProperties
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.reconciliation.AuditingVoucherDelayedEventHandler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.LettuceVoucherLeaderRunner
import io.bluetape4k.workshop.commerce.voucher.reconciliation.ReconciliationFaultInjector
import io.bluetape4k.workshop.commerce.voucher.reconciliation.SpringVoucherEventPublisher
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherDelayedEventHandler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherEventPublisher
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherInboxAppliedEventListener
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherLeaderRunner
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationScheduler
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationWorker
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/** durable Spring Modulith publication과 PostgreSQL-authoritative reconciliation path를 연결합니다. */
/** durable Spring Modulith publication과 PostgreSQL-authoritative reconciliation path를 연결합니다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class VoucherPublicationConfiguration {

    companion object: KLogging()

    /**
     * eventPublicationRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventPublicationTable 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     * @param eventPublicationArchiveTable 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복
     * 여부를 보존해야 합니다.
     * @param serializer 업무 처리에 필요한 serializer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventPublicationRepository(
        eventPublicationTable: ExposedEventPublicationTable,
        eventPublicationArchiveTable: ExposedEventPublicationTable,
        serializer: EventSerializer,
        properties: ExposedModulithProperties,
    ): ExposedEventPublicationRepository =
        ExposedEventPublicationRepository(
            table = eventPublicationTable,
            archiveTable = eventPublicationArchiveTable,
            serializer = serializer,
            completionMode = properties.completionMode,
        ).also {
            log.info { "voucher_publication_repository_configured completionMode=${properties.completionMode}" }
        }

    /**
     * voucherEventPublisher은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와
     * 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    @Bean
    fun voucherEventPublisher(events: ApplicationEventPublisher): VoucherEventPublisher =
        SpringVoucherEventPublisher(events)

    /**
     * voucherDelayedEventHandler은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param audits 업무 처리에 필요한 audits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    @Bean
    fun voucherDelayedEventHandler(
        audits: AuditRepository,
        events: VoucherEventPublisher,
    ): VoucherDelayedEventHandler =
        AuditingVoucherDelayedEventHandler(audits, events)

    /**
     * voucherInboxAppliedEventListener은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun voucherInboxAppliedEventListener(): VoucherInboxAppliedEventListener =
        VoucherInboxAppliedEventListener()

    /**
     * voucherReconciliationService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param jdbc 업무 처리에 필요한 jdbc 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param inbox 업무 처리에 필요한 inbox 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param handler 업무 처리에 필요한 handler 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherReconciliationService(
        jdbc: VoucherJdbcExecutor,
        inbox: EventInboxRepository,
        handler: VoucherDelayedEventHandler,
        clock: Clock,
        properties: VoucherProperties,
    ): VoucherReconciliationService =
        VoucherReconciliationService(
            jdbc = jdbc,
            inbox = inbox,
            handler = handler,
            clock = clock,
            transactionTimeout = properties.worker.transactionTimeout,
            claimOwner = properties.worker.instanceId,
            maxAttempts = properties.worker.maxAttempts,
            faultInjector = ReconciliationFaultInjector.NONE,
        )

    /**
     * voucherLeaderRunner은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param resources 업무 처리에 필요한 resources 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherLeaderRunner(
        resources: ObjectProvider<VoucherRedisResources>,
        properties: VoucherProperties,
    ): VoucherLeaderRunner =
        LettuceVoucherLeaderRunner(
            electorProvider = { resources.ifAvailable?.leaderElector() },
            instanceId = properties.worker.instanceId,
        )

    /**
     * voucherReconciliationWorker은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reconciliation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param leader 업무 처리에 필요한 leader 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param degradation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun voucherReconciliationWorker(
        reconciliation: VoucherReconciliationService,
        properties: VoucherProperties,
        leader: VoucherLeaderRunner,
        degradation: VoucherDegradationState,
        metrics: VoucherMetrics,
    ): VoucherReconciliationWorker =
        VoucherReconciliationWorker(
            reconciliation = reconciliation,
            properties = properties.worker,
            leader = leader,
            degradation = degradation,
            metrics = metrics
        )

    @Bean
    @ConditionalOnProperty(prefix = "workshop.voucher.redis", name = ["enabled"], havingValue = "true")
    @ConditionalOnProperty(
        prefix = "workshop.voucher.worker",
        name = ["scheduling-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    /**
     * voucherReconciliationScheduler은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param worker 업무 처리에 필요한 worker 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun voucherReconciliationScheduler(
        worker: VoucherReconciliationWorker,
    ): VoucherReconciliationScheduler =
        VoucherReconciliationScheduler(worker)
}
