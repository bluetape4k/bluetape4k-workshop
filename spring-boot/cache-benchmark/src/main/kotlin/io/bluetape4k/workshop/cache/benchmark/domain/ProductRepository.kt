package io.bluetape4k.workshop.cache.benchmark.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * [Product] entity 를 다루는 Spring Data JPA repository 입니다.
 */
@Repository
interface ProductRepository: JpaRepository<Product, Long> {
    fun findByCategory(category: String): List<Product>
    fun findByNameContaining(name: String): List<Product>
}
