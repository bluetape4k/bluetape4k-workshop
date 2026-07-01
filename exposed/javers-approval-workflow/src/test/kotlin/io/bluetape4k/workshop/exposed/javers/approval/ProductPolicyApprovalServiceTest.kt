package io.bluetape4k.workshop.exposed.javers.approval

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyProposalTable
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyStatus
import io.bluetape4k.workshop.exposed.javers.approval.model.PricingPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicyTable
import io.bluetape4k.workshop.exposed.javers.approval.model.ProposalStatus
import io.bluetape4k.workshop.exposed.javers.approval.service.ProductPolicyApprovalService
import org.javers.core.JaversBuilder
import org.javers.core.metamodel.`object`.SnapshotType
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
            service.getHistory(110L).map { it.type } shouldBeEqualTo listOf(SnapshotType.INITIAL, SnapshotType.UPDATE)
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
            history.map { it.type } shouldBeEqualTo listOf(SnapshotType.INITIAL, SnapshotType.UPDATE)
            service.findProposal(rejected.id).shouldNotBeNull().status shouldBeEqualTo ProposalStatus.REJECTED
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
