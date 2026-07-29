package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import org.springframework.data.jpa.repository.JpaRepository

/**
 * fulfillment reservation row 를 다루는 Spring Data repository 입니다.
 */
interface FulfillmentReservationRepository: JpaRepository<FulfillmentReservation, String>
