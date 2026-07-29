package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherCompatibilityTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationResult
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeKeyRing
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal abstract class VoucherCommandTestSupport : VoucherCompatibilityTestSupport() {
    private val exposedDatabases = mutableListOf<Database>()
    private var previousDefaultDatabase: Database? = null

    protected lateinit var gate: DatabasePermitGate
    protected lateinit var jdbc: VoucherJdbcExecutor
    protected lateinit var campaigns: CampaignRepository
    protected lateinit var claims: ClaimRepository
    protected lateinit var reviews: ReviewRepository
    protected lateinit var audits: AuditRepository
    protected lateinit var codes: VoucherCodeService
    protected lateinit var campaignCommands: CampaignCommandService
    protected lateinit var allocation: AllocationService
    protected lateinit var claimCommands: ClaimCommandService
    protected lateinit var reviewCommands: ReviewCommandService

    protected val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @BeforeEach
    fun createCommandRuntime() {
        previousDefaultDatabase = TransactionManager.defaultDatabase
        check(migrationRunner().migrate() in setOf(VoucherMigrationResult.APPLIED, VoucherMigrationResult.ALREADY_APPLIED))
        configureCommandRuntime()
    }

    @AfterEach
    fun closeCommandDatabase() {
        exposedDatabases.asReversed().forEach(TransactionManager::closeAndUnregister)
        exposedDatabases.clear()
        TransactionManager.defaultDatabase = previousDefaultDatabase
        previousDefaultDatabase = null
    }

    protected fun configureCommandRuntime(
        foregroundPermits: Int = 12,
        workerPermits: Int = 1,
        acquireTimeout: Duration = Duration.ofSeconds(2),
        lockTimeout: Duration = Duration.ofSeconds(5),
        serviceClock: Clock = clock,
        runtimeDataSource: DataSource = dataSource,
    ) {
        gate =
            DatabasePermitGate(
                foregroundPermits = foregroundPermits,
                workerPermits = workerPermits,
                sseMaintenancePermits = 3,
                acquireTimeout = acquireTimeout,
            )
        val transactionManager = SpringTransactionManager(runtimeDataSource, DatabaseConfig {}, false)
        exposedDatabases +=
            checkNotNull(
                TransactionTemplate(transactionManager).execute {
                    TransactionManager.current().db
                },
            )
        jdbc = VoucherJdbcExecutor(gate, transactionManager, lockTimeout)
        campaigns = CampaignRepository(gate)
        claims = ClaimRepository(gate)
        reviews = ReviewRepository(gate)
        audits = AuditRepository(gate)
        codes =
            VoucherCodeService(
                VoucherCodeKeyRing(
                    currentGenerationVersion = 5,
                    currentVerificationVersion = 7,
                    generationKeys = mapOf(5 to ByteArray(32) { 0x15 }),
                    verificationKeys = mapOf(7 to ByteArray(32) { 0x27 }),
                ),
            )
        campaignCommands = CampaignCommandService(jdbc, campaigns, audits, serviceClock)
        allocation = AllocationService(jdbc, campaigns, claims, reviews, audits, codes, serviceClock)
        claimCommands = ClaimCommandService(jdbc, campaigns, claims, reviews, audits, codes, serviceClock)
        reviewCommands = ReviewCommandService(jdbc, campaigns, claims, reviews, audits, codes, serviceClock)
    }

    protected fun createCampaign(
        capacity: Int = 10,
        perUserLimit: Int = 1,
        state: CampaignState = CampaignState.ACTIVE,
        campaignId: UUID = CAMPAIGN_ID,
    ): CampaignRecord =
        jdbc.foregroundTransaction {
            campaigns.create(
                CampaignRecord(
                    id = 0,
                    tenantId = TENANT_ID,
                    campaignId = campaignId,
                    state = state,
                    startsAt = NOW.minusSeconds(60),
                    endsAt = NOW.plusSeconds(3600),
                    capacity = capacity,
                    allocatedCount = 0,
                    perUserLimit = perUserLimit,
                    redemptionTtlSeconds = 3600,
                    policyVersion = 1,
                    revision = 0,
                ),
            )
        }

    protected fun campaignSnapshot(campaignId: UUID = CAMPAIGN_ID): CampaignRecord =
        jdbc.foregroundTransaction { checkNotNull(campaigns.findPublic(TENANT_ID, campaignId)) }

    protected companion object {
        val NOW: Instant = Instant.parse("2026-07-19T10:00:00Z")
        const val TENANT_ID = "tenant-a"
        val CAMPAIGN_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
    }
}
