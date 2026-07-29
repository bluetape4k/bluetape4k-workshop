package io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api

/**
 * product fact 가 필요한 module 이 사용하는 exported catalog lookup 계약입니다.
 */
fun interface CatalogLookup {
    /**
     * 지정한 SKU 의 read-only item snapshot 을 반환합니다.
     * catalog 가 해당 item 을 노출하지 않으면 `null` 을 반환합니다.
     */
    fun findItem(sku: String): CatalogItemSnapshot?
}
