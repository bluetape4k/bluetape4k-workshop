package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data repository for current order rows.
 */
interface OrderJpaRepository: JpaRepository<OrderEntity, String>
