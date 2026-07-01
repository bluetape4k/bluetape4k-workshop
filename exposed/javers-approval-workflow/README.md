# exposed/javers-approval-workflow

[한국어](README.ko.md) | English

This module shows a pre-commit JaVers review workflow. Instead of committing
every changed aggregate immediately, it compares the current `ProductPolicy`
against a proposed revision, stores a review decision, and commits the proposed
state only after approval.

Use it when a domain change needs a reviewer to see exactly what will change
before the current Exposed row and JaVers audit history are updated.

![exposed/javers-approval-workflow architecture diagram](../../docs/images/readme-diagrams/exposed-javers-approval-workflow-readme-architecture-01.png)

## Runtime Flow

![exposed/javers-approval-workflow sequence diagram](../../docs/images/readme-diagrams/exposed-javers-approval-workflow-readme-sequence-01.png)

## What This Module Shows

| Operation | Source-backed behavior |
|---|---|
| `publishInitial(author, policy)` | Commits the first approved `ProductPolicy` snapshot to JaVers and upserts the Exposed current row |
| `submitProposal(requester, proposed)` | Loads the current row, compares current vs proposed with JaVers, and stores a pending proposal with changed-field summaries |
| `approveProposal(reviewer, proposalId, reason)` | Commits the proposed aggregate to JaVers, updates the current row, and marks the proposal approved |
| `rejectProposal(reviewer, proposalId, reason)` | Records the rejection reason without changing the current row or adding a JaVers snapshot |
| `getHistory(policyId)` | Returns approved JaVers snapshots only, oldest-first |

## Approval Workflow vs Append-Only Audit

| Audit style | When JaVers commits | What rejected changes become |
|---|---|---|
| Append-only audit (`exposed/javers-audit`) | Every save operation commits the aggregate state | Not modeled; the saved state is already audit history |
| Approval workflow (this module) | Only initial publish and approved proposals commit aggregate state | Durable proposal decisions in `PolicyProposalTable`, not JaVers snapshots |
| Redis-backed audit (`exposed/javers-persistence-audit`) | Commits are persisted to Redis-backed JaVers repository | Persistence concern, not approval-gate behavior |

## Domain Schema

`ProductPolicyTable` stores the current approved aggregate state. `PolicyProposalTable`
stores review decisions, proposed fields, and reader-facing diff summaries. The
JaVers repository remains in-memory because this example teaches approval order,
not external audit persistence.

| Table | Responsibility |
|---|---|
| `product_policies` | Current approved `ProductPolicy` row |
| `policy_proposals` | Pending/approved/rejected review decisions and proposed state |
| JaVers in-memory repository | Approved aggregate snapshots queried by `getHistory(policyId)` |

## Usage

```kotlin
val javers = JaversBuilder.javers().build()
val service = ProductPolicyApprovalService(javers)

val current = ProductPolicy(
    id = 100L,
    title = "Standard Support Policy",
    status = PolicyStatus.ACTIVE,
    pricing = PricingPolicy("USD", BigDecimal("99.00"), BigDecimal("500.00")),
    owner = "platform-team",
)

service.publishInitial("alice", current)

val proposed = current.copy(
    title = "Enterprise Support Policy",
    pricing = current.pricing.copy(amount = BigDecimal("129.00")),
)

val proposal = service.submitProposal("bob", proposed)
proposal.changedFields.map { it.path } // ["pricing.amount", "title"]

service.approveProposal("carol", proposal.id, "Pricing reviewed")
val approvedHistory = service.getHistory(100L)
```

## Tests

```bash
./gradlew :exposed-javers-approval-workflow:test
```

The test suite covers scalar and nested value-object diffs, approval updating
the current row and JaVers history, rejection leaving both unchanged, and audit
lookup returning approved snapshots only.
