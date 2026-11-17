package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherCompatibilityTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationResult
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimTable
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditTable
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxTable
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeKeyRing
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import javax.sql.DataSource

internal abstract class VoucherCommandTestSupport: VoucherCompatibilityTestSupport() {

    protected companion object: KLogging() {
        const val TENANT_ID = "tenant-a"
        val CAMPAIGN_ID: UUID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
        val NOW: Instant = Instant.parse("2026-07-19T10:00:00Z")
    }

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
        check(
            migrationRunner().migrate() in setOf(
                VoucherMigrationResult.APPLIED,
                VoucherMigrationResult.ALREADY_APPLIED
            )
        )
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
        acquireTimeout: Duration = 2.seconds(),
        lockTimeout: Duration = 5.seconds(),
        serviceClock: Clock = clock,
        runtimeDataSource: DataSource = dataSource,
    ) {
        gate = DatabasePermitGate(
            foregroundPermits = foregroundPermits,
            workerPermits = workerPermits,
            sseMaintenancePermits = 3,
            acquireTimeout = acquireTimeout,
        )
        val transactionManager = SpringTransactionManager(runtimeDataSource, DatabaseConfig {}, false)
        exposedDatabases += TransactionTemplate(transactionManager).execute {
            TransactionManager.current().db
        }.shouldNotBeNull()

        jdbc = VoucherJdbcExecutor(gate, transactionManager, lockTimeout)
        campaigns = CampaignRepository(gate)
        claims = ClaimRepository(gate)
        reviews = ReviewRepository(gate)
        audits = AuditRepository(gate)
        codes = VoucherCodeService(
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
        jdbc.foregroundTransaction {
            campaigns.findPublic(TENANT_ID, campaignId).shouldNotBeNull()
        }

    protected fun contributingClaimCount(campaignId: UUID): Long =
        jdbc.foregroundTransaction {
            ClaimTable
                .selectAll()
                .where {
                    (ClaimTable.tenantId eq TENANT_ID) and
                            (ClaimTable.campaignId eq campaignId) and
                            (ClaimTable.capacityReserved eq true) and
                            (ClaimTable.state inList listOf(ClaimState.ALLOCATED, ClaimState.REVIEW_REQUIRED, ClaimState.REDEEMED))
                }
                .count()
        }

    protected fun totalClaimCount(campaignId: UUID): Long =
        jdbc.foregroundTransaction {
            ClaimTable.selectAll().where {
                (ClaimTable.tenantId eq TENANT_ID) and (ClaimTable.campaignId eq campaignId)
            }.count()
        }

    protected fun auditCount(): Long =
        jdbc.foregroundTransaction { AuditTable.selectAll().count() }

    protected fun eventInboxCount(status: InboxStatus): Long =
        jdbc.foregroundTransaction {
            EventInboxTable.selectAll().where { EventInboxTable.status eq status }.count()
        }

    protected fun distinctEventInboxCount(status: InboxStatus): Long =
        jdbc.foregroundTransaction {
            EventInboxTable
                .select(EventInboxTable.eventId)
                .where { EventInboxTable.status eq status }
                .map { it[EventInboxTable.eventId] }
                .distinct()
                .count()
                .toLong()
        }

}
