# exposed-javers-approval-workflow ecosystem code review

Module: `:exposed-javers-approval-workflow`
Branch: `refactor/exposed-javers-approval-workflow-ecosystem-patterns`
Date: 2026-07-05

## Scope

7-Tier review and remediation for the approval workflow example module, focused
on bluetape4k ecosystem usage, Kotlin style, JaVers JSON handling, validation
boundaries, Exposed update safety, and regression coverage.

## 7-Tier Result

| Tier | Status | Evidence |
|---|---|---|
| API and domain boundaries | PASS | Proposal and policy ids now use `requirePositiveNumber`; invalid policies are rejected before persistence or JaVers commits. |
| Correctness and state transitions | PASS | Approval/rejection now use a conditional pending-state update before JaVers commit and current-policy upsert. |
| Persistence and Exposed usage | PASS | Uses Exposed v1 top-level `eq`/`and` imports and conditional `update` instead of stale in-memory state checks. |
| bluetape4k ecosystem usage | PASS | Uses `JaversCodecs.String` and `javers.jsonConverter` instead of hand-rolled JSON escaping; validation uses bluetape4k support extensions. |
| Kotlin style and safety | PASS | No `!!`, `runCatching` around suspend calls, deprecated Exposed imports, or boolean-style assertion anti-patterns in touched code. |
| Tests and regression coverage | PASS | Added coverage for single-use approval, invalid policies, invalid lookup ids, money scale validation, and special-character snapshot JSON. |
| Documentation and maintainability | PASS | Review artifact records the module decision, remaining P3 follow-up, and verification evidence. |

## Findings

- P0: 0
- P1: 0
- P2: 0
- P3: 1

P3 follow-up: the duplicate-approval regression test is sequential rather than
a true concurrent race test. The conditional database update resolves the code
race found in review; add a `MultithreadingTester` contention test later if the
module needs explicit concurrent proof.

## Verification

- `repo-test-summary -- ./gradlew :exposed-javers-approval-workflow:compileKotlin :exposed-javers-approval-workflow:compileTestKotlin :exposed-javers-approval-workflow:cleanTest :exposed-javers-approval-workflow:test --no-build-cache --warning-mode all --console=plain --max-workers=1`
  - PASS, 10 tests.
- `MAX_WORKERS=1 repo-test-summary -- ./scripts/smoke-validate.sh data-access`
  - PASS.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`
  - PASS, 101 active modules, no stale references, no broken image links.
- `git diff --check`
  - PASS.
- Static scan for forbidden assertion style, deprecated Exposed imports,
  `runCatching`, and `!!`
  - PASS, no hits in touched source/test paths.
- Native code-reviewer re-review
  - APPROVE, P0/P1/P2 = 0.

IntelliJ diagnostics were not available in this Codex surface; Gradle
compile/test was used as the fallback diagnostics evidence.
