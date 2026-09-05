package io.bluetape4k.workshop.exposed.javers.approval

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javers.codecs.JaversCodecs
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyProposalTable
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyStatus
import io.bluetape4k.workshop.exposed.javers.approval.model.PricingPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicyTable
import io.bluetape4k.workshop.exposed.javers.approval.model.ProposalStatus
import io.bluetape4k.workshop.exposed.javers.approval.service.ProductPolicyApprovalService
import org.javers.core.JaversBuilder
import org.javers.core.metamodel.`object`.SnapshotType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductPolicyApprovalServiceTest {

    private fun withApprovalService(testDB: TestDB, statement: (ProductPolicyApprovalService) -> Unit) {
        withTables(testDB, ProductPolicyTable, PolicyProposalTable) {
            statement(ProductPolicyApprovalService(JaversBuilder.javers().build()))
        }
    }

    @Test
    fun `submit proposal returns scalar and nested value object diff summary`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 100L)
            service.publishInitial("alice", current)

            val proposed = current.copy(
                title = "Enterprise Support Policy",
                pricing = current.pricing.copy(amount = BigDecimal("129.00")),
            )

            val proposal = service.submitProposal("bob", proposed)

            proposal.status shouldBeEqualTo ProposalStatus.PENDING
            proposal.changedFields.map { it.path } shouldContain "title"
            proposal.changedFields.map { it.path } shouldContain "pricing.amount"
            proposal.changedFields.first { it.path == "pricing.amount" }.left shouldBeEqualTo "99.00"
            proposal.changedFields.first { it.path == "pricing.amount" }.right shouldBeEqualTo "129.00"
        }

    @Test
    fun `approve proposal updates current row and appends approved JaVers snapshot`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 110L)
            service.publishInitial("alice", current)
            val proposal = service.submitProposal(
                requester = "bob",
                proposed = current.copy(
                    status = PolicyStatus.RETIRED,
                    pricing = current.pricing.copy(approvalLimit = BigDecimal("1000.00")),
                ),
            )

            val approved = service.approveProposal("carol", proposal.id, "Limit reviewed")

            approved.status shouldBeEqualTo ProposalStatus.APPROVED
            approved.reviewer shouldBeEqualTo "carol"
            val stored = service.findCurrentPolicy(110L)
            stored.shouldNotBeNull()
            stored.status shouldBeEqualTo PolicyStatus.RETIRED
            stored.pricing.approvalLimit shouldBeEqualTo BigDecimal("1000.00")
            service.getHistory(110L).map { it.type } shouldBeEqualTo listOf(SnapshotType.UPDATE, SnapshotType.INITIAL)
        }

    @Test
    fun `reject proposal records decision without changing current row or audit history`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 120L)
            service.publishInitial("alice", current)
            val proposal = service.submitProposal(
                requester = "bob",
                proposed = current.copy(pricing = current.pricing.copy(amount = BigDecimal("199.00"))),
            )

            val rejected = service.rejectProposal("carol", proposal.id, "Price increase is too large")

            rejected.status shouldBeEqualTo ProposalStatus.REJECTED
            rejected.reviewer shouldBeEqualTo "carol"
            rejected.reason shouldBeEqualTo "Price increase is too large"
            service.findCurrentPolicy(120L) shouldBeEqualTo current
            val history = service.getHistory(120L)
            history shouldHaveSize 1
            history.single().type shouldBeEqualTo SnapshotType.INITIAL
        }

    @Test
    fun `audit lookup returns approved snapshots only after mixed decisions`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 130L)
            service.publishInitial("alice", current)
            val rejected = service.submitProposal(
                requester = "bob",
                proposed = current.copy(pricing = current.pricing.copy(amount = BigDecimal("399.00"))),
            )
            service.rejectProposal("carol", rejected.id, "Needs a pricing review")
            val approved = service.submitProposal(
                requester = "dave",
                proposed = current.copy(title = "Premium Support Policy"),
            )

            service.approveProposal("erin", approved.id, "Approved title cleanup")

            val history = service.getHistory(130L)
            history shouldHaveSize 2
            history.map { it.type } shouldBeEqualTo listOf(SnapshotType.UPDATE, SnapshotType.INITIAL)
            history.map { it.version } shouldBeEqualTo listOf(2L, 1L)
            service.findProposal(rejected.id).shouldNotBeNull().status shouldBeEqualTo ProposalStatus.REJECTED
        }

    @Test
    fun `approved proposal transition is single use and does not append duplicate snapshots`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 135L)
            service.publishInitial("alice", current)
            val proposal = service.submitProposal(
                requester = "bob",
                proposed = current.copy(title = "Single Use Policy"),
            )

            service.approveProposal("carol", proposal.id, "Approved once")

            assertFailsWith<IllegalArgumentException> {
                service.approveProposal("dave", proposal.id, "Duplicate approval")
            }
            service.getHistory(135L).map { it.type } shouldBeEqualTo listOf(SnapshotType.UPDATE, SnapshotType.INITIAL)
        }

    @Test
    fun `bounded history returns newest approved snapshots and preserves JVM overloads`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 138L)
            service.publishInitial("alice", current)
            val proposal = service.submitProposal("bob", current.copy(title = "Bounded Policy"))
            service.approveProposal("carol", proposal.id, "Approved")

            val latest = service.getHistory(138L, 1)
            latest shouldHaveSize 1
            latest.single().type shouldBeEqualTo SnapshotType.UPDATE
            latest.single().version shouldBeEqualTo 2L
            service.getHistory(138L, 100) shouldHaveSize 2
            service.getHistory(999_999L).shouldBeEmpty()

            val oneArgument = service.javaClass.getMethod("getHistory", Long::class.javaPrimitiveType)
            val twoArguments = service.javaClass.getMethod(
                "getHistory",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            (oneArgument.invoke(service, 138L) as List<*>) shouldHaveSize 2
            (twoArguments.invoke(service, 138L, 1) as List<*>) shouldHaveSize 1
        }

    @Test
    fun `history rejects invalid limits before querying JaVers`() =
        withApprovalService(TestDB.H2) { service ->
            listOf(0, -1, 101).forEach { invalidLimit ->
                assertFailsWith<IllegalArgumentException> {
                    service.getHistory(140L, invalidLimit)
                }
            }
        }

    @Test
    fun `publish rejects invalid policy before JaVers commit`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 140L)
            val invalid = current.copy(pricing = current.pricing.copy(amount = BigDecimal("-1.00")))

            assertFailsWith<IllegalArgumentException> {
                service.publishInitial("alice", invalid)
            }

            service.getHistory(140L).shouldBeEmpty()
        }

    @Test
    fun `submit proposal rejects invalid currency before storing proposal`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 150L)
            service.publishInitial("alice", current)
            val invalidProposal = current.copy(pricing = current.pricing.copy(currency = "USDT"))

            assertFailsWith<IllegalArgumentException> {
                service.submitProposal("bob", invalidProposal)
            }

            service.getHistory(150L) shouldHaveSize 1
        }

    @Test
    fun `submit proposal rejects over scale money before diff formatting`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 160L)
            service.publishInitial("alice", current)
            val invalidProposal = current.copy(pricing = current.pricing.copy(amount = BigDecimal("99.999")))

            assertFailsWith<IllegalArgumentException> {
                service.submitProposal("bob", invalidProposal)
            }

            service.getHistory(160L) shouldHaveSize 1
        }

    @Test
    fun `lookup methods reject invalid identifiers`() =
        withApprovalService(TestDB.H2) { service ->
            assertFailsWith<IllegalArgumentException> {
                service.findCurrentPolicy(0L)
            }
            assertFailsWith<IllegalArgumentException> {
                service.findProposal(-1L)
            }
            assertFailsWith<IllegalArgumentException> {
                service.getHistory(0L)
            }
        }

    @Test
    fun `stored proposal snapshots are JaVers JSON for special characters`() =
        withApprovalService(TestDB.H2) { service ->
            val current = activePolicy(id = 170L).copy(
                title = "Standard \"Support\"\nPolicy",
                owner = "platform\\team\tcore",
            )
            service.publishInitial("alice", current)
            val proposal = service.submitProposal(
                requester = "bob",
                proposed = current.copy(title = "Enterprise \"Support\"\nPolicy"),
            )

            val proposedSnapshot = transaction {
                PolicyProposalTable.selectAll()
                    .where { PolicyProposalTable.id eq proposal.id }
                    .single()[PolicyProposalTable.proposedSnapshot]
            }
            val parsed = JaversBuilder.javers().build().jsonConverter.fromJson(
                JaversCodecs.String.decode(proposedSnapshot),
                ProductPolicy::class.java,
            )

            parsed.title shouldBeEqualTo "Enterprise \"Support\"\nPolicy"
            parsed.owner shouldBeEqualTo "platform\\team\tcore"
        }

    private fun activePolicy(id: Long): ProductPolicy =
        ProductPolicy(
            id = id,
            title = "Standard Support Policy",
            status = PolicyStatus.ACTIVE,
            pricing = PricingPolicy(
                currency = "USD",
                amount = BigDecimal("99.00"),
                approvalLimit = BigDecimal("500.00"),
            ),
            owner = "platform-team",
        )
}
