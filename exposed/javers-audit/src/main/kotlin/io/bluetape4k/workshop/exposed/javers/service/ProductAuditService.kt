package io.bluetape4k.workshop.exposed.javers.service

import io.bluetape4k.javers.latestSnapshotOrNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.exposed.javers.model.Product
import io.bluetape4k.workshop.exposed.javers.model.ProductTable
import org.javers.core.Javers
import org.javers.core.diff.Diff
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.repository.jql.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * JaVers 변경 이력 감사와 Exposed JDBC 영속화를 결합하는 서비스이다.
 *
 * ## 동작 / 계약
 * - [save]는 product를 JaVers(in-memory repository)에 commit하고 Exposed table에 upsert한다.
 * - [getHistory]는 주어진 product id의 모든 JaVers snapshot을 오래된 순서로 반환한다.
 * - [getLatestSnapshot]은 가장 최근 JaVers snapshot을 반환하고, 저장된 적이 없으면 null을 반환한다.
 * - [diff]는 두 product 인스턴스 중 어느 것도 저장하지 않고 JaVers [Diff]를 계산한다.
 * - [delete]는 Exposed에서 row를 제거하고 JaVers에 terminal snapshot을 기록한다.
 *
 * ```kotlin
 * val service = ProductAuditService(JaversBuilder.javers().build())
 * val product = Product(1L, "Widget", BigDecimal("9.99"), "Tools")
 * service.save("user1", product)
 * val history = service.getHistory(1L)
 * ```
 *
 * @property javers in-memory repository를 사용하는 JaVers 인스턴스이다.
 */
class ProductAuditService(
    private val javers: Javers,
) {
    companion object: KLogging()

    /**
     * [product]를 JaVers에 commit하고 [ProductTable] row를 upsert한다.
     *
     * @param author JaVers commit metadata에 기록될 변경 작성자를 식별한다.
     * @param product 영속화하고 감사할 product이다.
     */
    fun save(author: String, product: Product) {
        author.requireNotBlank("author")
        validateProduct(product)
        javers.commit(author, product)
        transaction {
            ProductTable.upsert {
                it[id] = product.id
                it[name] = product.name
                it[price] = product.price
                it[category] = product.category
            }
        }
        log.debug { "Saved and audited product id=${product.id} by $author" }
    }

    /**
     * 주어진 [productId]의 모든 JaVers snapshot을 오래된 순서로 반환한다.
     */
    fun getHistory(productId: Long): List<CdoSnapshot> {
        val validProductId = productId.requirePositiveNumber("productId")
        val query = QueryBuilder.byInstanceId(validProductId, Product::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * [productId]의 최신 JaVers snapshot을 반환하고, commit이 없으면 null을 반환한다.
     */
    fun getLatestSnapshot(productId: Long): CdoSnapshot? =
        javers.latestSnapshotOrNull<Product>(productId.requirePositiveNumber("productId"))

    /**
     * [oldProduct]와 [newProduct] 사이의 JaVers [Diff]를 계산해 반환한다.
     *
     * 두 객체 모두 영속화하지 않는다.
     */
    fun diff(oldProduct: Product, newProduct: Product): Diff =
        javers.compare(
            validateProduct(oldProduct),
            validateProduct(newProduct),
        )

    /**
     * Exposed에서 [product] row를 삭제하고 terminal JaVers commit을 기록한다.
     *
     * @param author 변경 작성자를 식별한다.
     * @param product 삭제할 product이다.
     */
    fun delete(author: String, product: Product) {
        author.requireNotBlank("author")
        val current = requireNotNull(findCurrentProduct(product.id)) {
            "Product ${product.id} does not exist"
        }
        javers.commitShallowDelete(author, current)
        transaction {
            ProductTable.deleteWhere { ProductTable.id eq current.id }
        }
        log.debug { "Deleted and audited product id=${current.id} by $author" }
    }

    private fun validateProduct(product: Product): Product {
        product.id.requirePositiveNumber("product.id")
        product.name.requireNotBlank("product.name")
        product.category.requireNotBlank("product.category")
        require(product.price.signum() >= 0) { "product.price must be zero or positive" }
        return product
    }

    private fun findCurrentProduct(productId: Long): Product? =
        transaction {
            ProductTable.selectAll()
                .where { ProductTable.id eq productId.requirePositiveNumber("productId") }
                .singleOrNull()
                ?.toProduct()
        }

    private fun ResultRow.toProduct(): Product =
        Product(
            id = this[ProductTable.id],
            name = this[ProductTable.name],
            price = this[ProductTable.price],
            category = this[ProductTable.category],
        )
}
