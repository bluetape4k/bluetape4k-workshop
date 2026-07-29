package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.FakeRefundProvider
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseEventPublisher
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseService
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.RefundService
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleService
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import java.time.Clock
import javax.sql.DataSource

/** transaction-coupled Modulith publication을 lookup-first effect worker에 연결합니다. */
@Configuration(proxyBeanMethods = false)
internal class TicketPublicationConfiguration {
    @Bean
    fun ticketClock(): Clock = Clock.systemUTC()

    @Bean
    fun ticketJdbcExecutor(dataSource: DataSource, properties: TicketProperties): TicketJdbcExecutor =
        TicketJdbcExecutor(
            dataSource = dataSource,
            foregroundPermits = properties.db.foregroundPermits,
            permitTimeout = properties.db.permitTimeout,
        )

    @Bean
    fun ticketSaleService(): SaleService = SaleService()

    @Bean
    fun ticketAdmissionService(jdbc: TicketJdbcExecutor, clock: Clock): AdmissionService = AdmissionService(jdbc, clock)

    @Bean
    fun purchaseEventPublisher(events: ApplicationEventPublisher): PurchaseEventPublisher =
        PurchaseEventPublisher(events::publishEvent)

    @Bean
    fun purchaseService(
        jdbc: TicketJdbcExecutor,
        sale: SaleService,
        admission: AdmissionService,
        clock: Clock,
        events: PurchaseEventPublisher,
    ): PurchaseService = PurchaseService(jdbc, sale, admission, clock, events)

    @Bean
    fun paymentProvider(): FakePaymentProvider = FakePaymentProvider()

    @Bean
    fun paymentWorker(jdbc: TicketJdbcExecutor, purchases: PurchaseService, provider: FakePaymentProvider): PaymentWorker =
        PaymentWorker(jdbc, purchases, provider)

    @Bean
    fun refundService(jdbc: TicketJdbcExecutor, provider: FakeRefundProvider): RefundService = RefundService(jdbc, provider)

    @Bean
    fun refundProvider(): FakeRefundProvider = FakeRefundProvider()

    @Bean
    fun ticketProvider(): FakeTicketProvider = FakeTicketProvider()

    @Bean
    fun ticketEffectWorker(
        jdbc: TicketJdbcExecutor,
        purchases: PurchaseService,
        provider: FakeTicketProvider,
    ): TicketEffectWorker = TicketEffectWorker(jdbc, purchases, provider)

    @Bean
    fun authorizationPublicationListener(worker: PaymentWorker): AuthorizationPublicationListener =
        AuthorizationPublicationListener(worker)
}

internal class AuthorizationPublicationListener(private val worker: PaymentWorker) {
    @ApplicationModuleListener
    fun on(event: AuthorizationRequested) {
        worker.run(event.operationId)
    }
}
