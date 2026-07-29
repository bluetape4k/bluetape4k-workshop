package io.bluetape4k.workshop.exposed.javers.approval.model

import org.jetbrains.exposed.v1.core.Table

/**
 * 현재 승인된 product policy row이다.
 */
object ProductPolicyTable: Table("product_policies") {
    val id = long("id")
    val title = varchar("title", 255)
    val status = enumerationByName("status", 16, PolicyStatus::class)
    val pricingCurrency = varchar("pricing_currency", 3)
    val pricingAmount = decimal("pricing_amount", precision = 19, scale = 2)
    val pricingApprovalLimit = decimal("pricing_approval_limit", precision = 19, scale = 2)
    val owner = varchar("owner", 100)

    override val primaryKey = PrimaryKey(id)
}
