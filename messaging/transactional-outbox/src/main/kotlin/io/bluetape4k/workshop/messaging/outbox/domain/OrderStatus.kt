package io.bluetape4k.workshop.messaging.outbox.domain

/**
 * [Order] 의 lifecycle status 를 표현합니다.
 *
 * ## States
 * - [PENDING] — order 가 place 되었지만 아직 confirmed 되지 않았습니다.
 * - [CONFIRMED] — system 이 order 를 accepted 했습니다.
 * - [SHIPPED] — order 가 dispatched 되었습니다.
 * - [CANCELLED] — shipment 전에 order 가 cancelled 되었습니다.
 */
enum class OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    CANCELLED,
}
