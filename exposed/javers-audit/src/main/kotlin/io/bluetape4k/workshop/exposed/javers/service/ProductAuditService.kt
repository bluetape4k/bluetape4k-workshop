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
 * Service that combines JaVers change-history auditing with Exposed JDBC persistence.
 *
 * ## Behavior / Contract
 * - [save] commits the product to JaVers (in-memory repository) and upserts it into the Exposed table.
 * - [getHistory] returns all JaVers snapshots for the given product id, oldest-first.
 * - [getLatestSnapshot] returns the most recent JaVers snapshot, or null if never saved.
 * - [diff] computes a JaVers [Diff] between two product instances without persisting either.
 * - [delete] removes the row from Exposed and records a terminal snapshot in JaVers.
 *
 * ```kotlin
 * val service = ProductAuditService(JaversBuilder.javers().build())
 * val product = Product(1L, "Widget", BigDecimal("9.99"), "Tools")
 * service.save("user1", product)
 * val history = service.getHistory(1L)
 * ```
 *
 * @property javers JaVers instance backed by an in-memory repository.
 */
class ProductAuditService(
    private val javers: Javers,
) {
    companion object: KLogging()

    /**
     * Commits [product] to JaVers and upserts the row in [ProductTable].
     *
     * @param author Identifies the change author recorded in the JaVers commit metadata.
     * @param product The product to persist and audit.
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
     * Returns all JaVers snapshots for the given [productId], oldest-first.
     */
    fun getHistory(productId: Long): List<CdoSnapshot> {
        val validProductId = productId.requirePositiveNumber("productId")
        val query = QueryBuilder.byInstanceId(validProductId, Product::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    /**
     * Returns the latest JaVers snapshot for [productId], or null if no commits exist.
     */
    fun getLatestSnapshot(productId: Long): CdoSnapshot? =
        javers.latestSnapshotOrNull<Product>(productId.requirePositiveNumber("productId"))

    /**
     * Computes and returns a JaVers [Diff] between [oldProduct] and [newProduct].
     *
     * Neither object is persisted.
     */
    fun diff(oldProduct: Product, newProduct: Product): Diff =
        javers.compare(
            validateProduct(oldProduct),
            validateProduct(newProduct),
        )

    /**
     * Deletes the [product] row from Exposed and records a terminal JaVers commit.
     *
     * @param author Identifies the change author.
     * @param product The product to delete.
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
