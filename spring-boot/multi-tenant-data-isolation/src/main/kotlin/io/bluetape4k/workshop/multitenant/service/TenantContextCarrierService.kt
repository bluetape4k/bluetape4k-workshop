package io.bluetape4k.workshop.multitenant.service

import io.bluetape4k.tenant.ScopedValueTenantContext
import io.bluetape4k.tenant.ThreadLocalTenantContext
import io.bluetape4k.tenant.reactor.ReactorTenantContext
import io.bluetape4k.workshop.multitenant.domain.InvoiceRecord
import io.bluetape4k.workshop.multitenant.domain.TenantId
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.util.context.ContextView

/**
 * HTTP, virtual-thread, and Reactor execution boundaries에 공통 tenant carrier를 연결합니다.
 *
 * 이 예제의 carrier는 tenant를 추론하거나 전역 기본값으로 보정하지 않습니다. 호출 경계가
 * 명시적으로 scope를 만들고, scope를 벗어나면 이전 값 또는 unbound 상태로 복원합니다.
 * 누락된 scope는 [io.bluetape4k.tenant.MissingTenantContextException]으로 거부됩니다.
 */
@Service
class TenantContextCarrierService(
    private val invoiceService: TenantInvoiceService,
    private val meterRegistry: MeterRegistry,
) {

    private val mvcContext = ThreadLocalTenantContext()
    private val virtualThreadContext = ScopedValueTenantContext()

    /**
     * Spring MVC와 같은 blocking request 경계에 ThreadLocal tenant scope를 엽니다.
     */
    fun <T> withMvcTenant(tenantId: TenantId, block: () -> T): T =
        mvcContext.withTenant(tenantId.toCarrierTenantId(), block)

    /**
     * 현재 MVC ThreadLocal scope의 tenant를 읽습니다.
     */
    fun currentMvcTenant(): TenantId? = mvcContext.currentOrNull()?.let { TenantId(it.value) }

    /**
     * 현재 MVC ThreadLocal scope를 요구하고, 누락이면 공통 예외를 그대로 전달합니다.
     */
    fun requireMvcTenant(): TenantId = TenantId(mvcContext.requireCurrent().value)

    /**
     * MVC carrier가 공급한 tenant로 기존 tenant-safe invoice service를 호출합니다.
     */
    fun findInvoiceWithMvcTenant(invoiceId: Long): InvoiceRecord? =
        invoiceService.findInvoice(requireMvcTenant(), invoiceId)

    /**
     * JDK virtual thread 경계에 ScopedValue tenant scope를 엽니다.
     */
    fun <T> withVirtualThreadTenant(tenantId: TenantId, block: () -> T): T =
        virtualThreadContext.withTenant(tenantId.toCarrierTenantId(), block)

    /**
     * 현재 virtual-thread ScopedValue scope의 tenant를 읽습니다.
     */
    fun currentVirtualThreadTenant(): TenantId? = virtualThreadContext.currentOrNull()?.let { TenantId(it.value) }

    /**
     * 현재 virtual-thread ScopedValue scope를 요구합니다.
     */
    fun requireVirtualThreadTenant(): TenantId = TenantId(virtualThreadContext.requireCurrent().value)

    /**
     * ScopedValue carrier가 공급한 tenant로 기존 tenant-safe invoice service를 호출합니다.
     */
    fun findInvoiceWithVirtualThreadTenant(invoiceId: Long): InvoiceRecord? =
        invoiceService.findInvoice(requireVirtualThreadTenant(), invoiceId)

    /**
     * Reactor publisher에 구독별 tenant scope를 부착합니다.
     *
     * Reactor [ContextView]는 immutable이므로 scheduler hop과 concurrent subscription 사이에서
     * 값을 공유하지 않고, publisher가 종료되거나 취소되면 호출자 쪽 상태로 남지 않습니다.
     */
    fun <T : Any> withReactorTenant(tenantId: TenantId, publisher: Mono<T>): Mono<T> =
        publisher.contextWrite { context ->
            ReactorTenantContext.withTenant(context, tenantId.toCarrierTenantId())
        }

    /**
     * Reactor context의 tenant를 읽습니다.
     */
    fun currentReactorTenant(contextView: ContextView): TenantId? =
        ReactorTenantContext.currentOrNull(contextView)?.let { TenantId(it.value) }

    /**
     * Reactor context의 tenant를 요구합니다.
     */
    fun requireReactorTenant(contextView: ContextView): TenantId =
        TenantId(ReactorTenantContext.requireCurrent(contextView).value)

    /**
     * 구독별 Reactor carrier가 공급한 tenant로 기존 tenant-safe invoice service를 호출합니다.
     */
    fun findInvoiceWithReactorTenant(tenantId: TenantId, invoiceId: Long): Mono<InvoiceRecord> =
        withReactorTenant(
            tenantId,
            Mono.deferContextual { contextView ->
                Mono.justOrEmpty(invoiceService.findInvoice(requireReactorTenant(contextView), invoiceId))
            },
        )

    /**
     * metrics에는 tenant 원문 대신 안정적인 bounded fingerprint만 사용합니다.
     */
    fun invoiceReadMeter(tenantId: TenantId): Meter =
        meterRegistry
            .get(TenantMetrics.INVOICE_READS)
            .tag(TenantMetrics.TENANT_FINGERPRINT_TAG, TenantMetrics.tenantFingerprint(tenantId))
            .meter()

    private fun TenantId.toCarrierTenantId(): io.bluetape4k.tenant.TenantId =
        io.bluetape4k.tenant.TenantId(value)
}
