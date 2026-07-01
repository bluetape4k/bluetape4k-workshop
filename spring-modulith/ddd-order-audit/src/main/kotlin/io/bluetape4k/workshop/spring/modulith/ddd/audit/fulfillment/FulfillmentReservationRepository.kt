package io.bluetape4k.workshop.spring.modulith.ddd.audit.fulfillment

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data repository for fulfillment reservation rows.
 */
interface FulfillmentReservationRepository: JpaRepository<FulfillmentReservation, String>
