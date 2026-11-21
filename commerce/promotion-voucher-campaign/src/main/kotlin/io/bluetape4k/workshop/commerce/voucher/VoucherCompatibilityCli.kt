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
/** migration compatibility verification task에서만 사용하는 격리된 packaged-artifact entry point입니다. */
internal object VoucherCompatibilityCli: KLogging() {
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */
    private const val MODE_PREFIX = "--voucher-compatibility-mode="

    /**
     * supports은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param args 업무 처리에 필요한 args 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun supports(args: Array<String>): Boolean = args.any { it.startsWith(MODE_PREFIX) }

    /**
     * run은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param args 업무 처리에 필요한 args 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
