package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import org.springframework.data.jpa.repository.JpaRepository

/**
 * 현재 order row 를 다루는 Spring Data repository 입니다.
 */
interface OrderJpaRepository: JpaRepository<OrderEntity, String>
