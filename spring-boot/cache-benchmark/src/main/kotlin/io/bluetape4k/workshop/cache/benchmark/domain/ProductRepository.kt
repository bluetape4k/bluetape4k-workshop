package io.bluetape4k.workshop.cache.benchmark.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Spring Data JPA repository for [Product] entities.
 */
@Repository
interface ProductRepository: JpaRepository<Product, Long> {
    fun findByCategory(category: String): List<Product>
    fun findByNameContaining(name: String): List<Product>
}
