package io.bluetape4k.workshop.exposed.javers.model

import org.jetbrains.exposed.v1.core.Table

/**
 * [Product] 영속화를 위한 Exposed table 정의이다.
 *
 * ## 동작 / 계약
 * - H2 호환 column type을 사용한다.
 * - [id]는 primary key이며 uniqueness는 DB level에서 강제된다.
 * - floating-point 반올림을 피하기 위해 [price]를 DECIMAL(19,4)로 저장한다.
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
