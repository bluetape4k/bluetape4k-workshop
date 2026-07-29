package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationChannel
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryRepository
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationRequest
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 이미 점유된 capacity slot 하나를 FIFO waitlist head로 이전하거나, 대기자가 없으면 해제합니다.
 * caller가 transaction을 소유하며, 이 service를 호출하기 전에 resource를 lock해야 합니다.
 */
@Service
internal class ReservationCapacityHandoffService(
    private val resources: CapacityResourceRepository,
    private val waitlist: WaitlistCommandService,
    private val notifications: NotificationDeliveryRepository,
    private val audits: ReservationAuditRepository,
) {
    fun promoteOrRelease(
        resourceId: Long,
        now: Instant,
    ): ReservationOfferRecord? {
        val offer = waitlist.promote(resourceId)
        if (offer == null) {
            val resource = resources.findByIdForUpdate(resourceId)
            check(resources.release(resource.id, resource.revision)) {
                "capacity release lost after terminal reservation transition"
            }
            audits.record("CAPACITY_RESOURCE", resource.id, resource.revision + 1, "CAPACITY_RELEASED")
            log.debug { "reservation_capacity_released resourceId=$resourceId revision=${resource.revision + 1}" }
            return null
        }
        notifications.enqueue(
            NotificationRequest(
                deliveryId = "offer-created:${offer.id}:${offer.revision}:in-app",
                channel = NotificationChannel.IN_APP,
                templateCode = "RESERVATION_OFFER_CREATED",
                aggregateId = offer.id.toString()
            ),
            now
        )
        log.debug { "reservation_capacity_handed_off resourceId=$resourceId offerId=${offer.id}" }
        return offer
    }

    companion object : KLogging()
}
