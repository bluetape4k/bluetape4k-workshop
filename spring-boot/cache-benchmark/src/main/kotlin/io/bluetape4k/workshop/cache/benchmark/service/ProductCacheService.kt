package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.workshop.cache.benchmark.domain.Product

/**
 * 7개 cache profile service 구현체가 공유하는 interface 입니다.
 *
 * 모든 구현체는 같은 input 에 대해 같은 logical result 를 반환해야 하며,
 * 이를 통해 cache strategy 사이의 functional equivalence 를 보장합니다.
 */
interface ProductCacheService {
    /** ID 로 product 를 찾습니다. 찾지 못하면 null 을 반환합니다. */
    fun findById(id: Long): Product?

    /**
     * product 를 저장하거나 갱신합니다.
     *
     * Writer-backed Redisson profile 은 기존의 stable [Product.id] 를 요구합니다.
     * map key 가 write contract 이기 때문입니다. generated-ID insert 는 canonical
     * write-through/write-behind benchmark path 에 포함하지 않습니다.
     */
    fun save(product: Product): Product

    /** cache 와 storage 에서 product 를 제거합니다. */
    fun evict(id: Long)

    /** 모든 cached entry(local 또는 remote)를 지웁니다. */
    fun clearAll()
}
