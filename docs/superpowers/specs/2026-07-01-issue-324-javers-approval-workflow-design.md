# Issue #324 - JaVers Approval Workflow Design

## Context

Issue #324 adds a learner-facing workshop example for a production-style JaVers
workflow. The existing `exposed/javers-audit` module teaches append-only audit
history, while `exposed/javers-persistence-audit` teaches Redis-backed JaVers
persistence. This example must stay between those two: it uses in-memory
JaVers for review-time diffing and approved audit snapshots, and Exposed/H2 for
the current row plus review decisions.

## Goal

Create `exposed/javers-approval-workflow` as `:exposed-javers-approval-workflow`
to demonstrate:

- submitting a proposed aggregate revision before persistence;
- generating a JaVers diff between current and proposed states;
- approving or rejecting a proposal with a durable decision record;
- committing the proposed aggregate only after approval;
- querying approved JaVers history separately from rejected proposals.

## Non-Goals

- Do not add Redis, Kafka, or another JaVers repository backend. That belongs to
  issue #290.
- Do not build a web UI or Spring application.
- Do not make the module a generic approval framework. Keep it small enough for
  workshop readers to understand in one pass.

## Domain Model

Use a `ProductPolicy` aggregate because it is concrete enough for product,
contract, and policy review scenarios without adding unnecessary tables.

- `ProductPolicy`: JaVers aggregate root with `@TypeName("ProductPolicy")` and
  `@Id id`.
- `PricingPolicy`: nested value object with `currency`, `amount`, and
  `approvalLimit`.
- `PolicyStatus`: enum for `DRAFT`, `ACTIVE`, and `RETIRED`.
- `PolicyProposal`: stored review request with current/proposed aggregate
  snapshots, changed-field summaries, status, reviewer, and reason.
- `ProposalStatus`: enum for `PENDING`, `APPROVED`, and `REJECTED`.

The Exposed table stores the current approved `ProductPolicy` row. A second
Exposed table stores proposal decisions so rejected changes remain visible even
though they never become JaVers aggregate snapshots.

## Workflow

1. `publishInitial(author, policy)` commits the first approved policy to JaVers
   and upserts the current policy row.
2. `submitProposal(requester, proposedPolicy)` loads the current row, compares
   current vs proposed with `javers.compare`, stores a pending proposal, and
   returns changed-field summaries.
3. `approveProposal(reviewer, proposalId, reason)` validates the proposal is
   pending, commits the proposed aggregate to JaVers, upserts the current row,
   and marks the proposal approved.
4. `rejectProposal(reviewer, proposalId, reason)` validates the proposal is
   pending and records the rejection without committing the proposed aggregate.
5. `getHistory(policyId)` returns approved JaVers snapshots only.

## Documentation And Diagrams

Both `README.md` and `README.ko.md` must explain the difference from append-only
audit history:

- append-only audit commits every saved aggregate state;
- approval workflow computes a pre-commit diff first;
- rejected proposals are decision records, not approved JaVers snapshots;
- approved proposals update both current state and JaVers history.

Diagrams:

- Architecture diagram: reviewer, approval service, Exposed current/decision
  tables, and in-memory JaVers snapshots.
- Sequence diagram: submit proposal, diff review, approve/reject `alt` branch,
  and audit lookup.

The diagrams must pass the current `bluetape4k-diagram` checklist, render to
SVG+PNG, and include full-size PNG eye-inspection evidence.

## Registration

- `settings.gradle.kts` auto-includes the module through `includeModules`.
- Root `README.md` and `README.ko.md` get a data-access row.
- `.github/workflows/Examples.yml` gets path filters and H2/default smoke
  coverage.
- `scripts/smoke-validate.sh` gets the module in smoke/data-access checks and
  increments stale-check project count.

## Verification

- `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1 --rerun-tasks`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh stale-check`
- `node scripts/validate-readme-diagram-qa.mjs`
- README language/parity validators when available in `scripts/`
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`
