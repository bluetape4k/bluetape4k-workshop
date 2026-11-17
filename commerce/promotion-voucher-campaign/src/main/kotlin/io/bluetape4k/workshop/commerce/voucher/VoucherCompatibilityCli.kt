@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigration
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationRunner
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

/** migration compatibility verification task에서만 사용하는 격리된 packaged-artifact entry point입니다. */
internal object VoucherCompatibilityCli: KLogging() {
    private const val MODE_PREFIX = "--voucher-compatibility-mode="

    fun supports(args: Array<String>): Boolean = args.any { it.startsWith(MODE_PREFIX) }

    fun run(args: Array<String>) {
        val values = args.associate { argument ->
            val separator = argument.indexOf('=')
            require(argument.startsWith("--") && separator > 2) { "invalid compatibility argument" }

            argument.substring(2, separator) to argument.substring(separator + 1)
        }
        require(values.getValue("voucher-compatibility-mode") == "migrate-read-write")

        val dataSource = DriverManagerDataSource(
            values.getValue("voucher-database-url"),
            values.getValue("voucher-database-username"),
            values.getValue("voucher-database-password"),
        )
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = 534001L,
        ).migrate()

        val database = Database.connect(dataSource)
        try {
            transaction(database) {
                val now = Instant.now()
                CampaignTable.insertIgnore {
                    it[CampaignTable.tenantId] = values.getValue("voucher-tenant")
                    it[CampaignTable.campaignId] = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
                    it[CampaignTable.state] = CampaignState.ACTIVE
                    it[CampaignTable.startsAt] = now.minusSeconds(60)
                    it[CampaignTable.endsAt] = now.plusSeconds(3600)
                    it[CampaignTable.capacity] = 10
                    it[CampaignTable.allocatedCount] = 0
                    it[CampaignTable.perUserLimit] = 1
                    it[CampaignTable.redemptionTtlSeconds] = 3600
                    it[CampaignTable.policyVersion] = 1
                    it[CampaignTable.revision] = 0
                }
                check(CampaignTable.selectAll().count() >= 2L) { "compatibility rows are missing" }
            }
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
        log.info { "voucher_compatibility_current_completed" }
    }
}
