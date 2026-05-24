package io.bluetape4k.workshop.exposed.javers.model

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for [Product] persistence.
 *
 * ## Behavior / Contract
 * - Uses H2-compatible column types.
 * - [id] is the primary key; uniqueness is enforced at the DB level.
 * - [price] stored as DECIMAL(19,4) to avoid floating-point rounding.
 *
 * ```kotlin
 * transaction {
 *     SchemaUtils.create(ProductTable)
 *     ProductTable.insert { row ->
 *         row[id] = 1L
 *         row[name] = "Widget"
 *         row[price] = BigDecimal("9.99")
 *         row[category] = "Tools"
 *     }
 * }
 * ```
 */
object ProductTable: Table("products") {
    val id = long("id")
    val name = varchar("name", 255)
    val price = decimal("price", precision = 19, scale = 4)
    val category = varchar("category", 100)

    override val primaryKey = PrimaryKey(id)
}
