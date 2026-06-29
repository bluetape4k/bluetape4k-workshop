# Issue 348 Kafka Outbox Fallback Code Review

- Date: 2026-06-29
- Scope: `messaging-kafka-outbox-fallback`, README assets, smoke workflow registration, diagram validators
- Reviewer: Codex

## Findings

### P1 Fixed: relay claim update was not conditional enough

`EventPublicationRepository.claimNextBatch()` first selected eligible rows and then updated by row id only. A competing relay transaction could select the same row before the first transaction committed and still update it because the update predicate did not re-check status, `nextAttemptAt`, or claim expiry.

Fix:

- `claimNextBatch()` now performs the update with `id AND eligibleForClaim(now)`.
- `markPublished()` and `markRelayFailure()` now require the current `claimedBy` owner.
- `EventPublicationRelay` no longer counts a Kafka send as published if the row can no longer be marked by the current worker.

Evidence:

- `./gradlew :messaging-kafka-outbox-fallback:test --tests '*claim*' --rerun-tasks --no-build-cache --max-workers=1 --console=plain` passed with `warnings=0`.
- `./gradlew :messaging-kafka-outbox-fallback:test --rerun-tasks --no-build-cache --max-workers=1 --console=plain` passed with `tests=16 failures=0 errors=0 skipped=0 warnings=0`.

## Residual Findings

- P0: 0
- P1: 0

## Review Notes

- The hot transaction writes only `orders`; fallback rows are created only after Kafka direct publish failure.
- Direct publish failure sanitizes secrets before persisting or exposing summaries.
- Fallback relay is claim-based, retries to `FAILED`, and moves to `DEAD_LETTER` at the configured retry cap.
- Reconciler intentionally reconstructs deterministic event ids from stale orders without publications and documents the duplicate risk.
- Demo admin relay/reconcile endpoints are disabled by default and return 404 unless explicitly enabled.
- `GET /api/publications` returns DTOs without raw payload or raw exception text.
- CodeReviewGraph could not provide useful structural coverage for the new untracked module in this worktree, so this review used local diff inspection plus targeted tests.

## Validation Evidence

- `MAX_WORKERS=1 ./scripts/smoke-validate.sh messaging`: `BUILD SUCCESSFUL`.
- `./scripts/smoke-validate.sh stale-check`: `Active modules: 86 (expected: 86)`, no stale refs, no broken image links.
- `git diff --check`: no output.
- `actionlint .github/workflows/Examples.yml`: no output.
- `node scripts/validate-readme-parity.mjs`: `failures=0`.
- `node scripts/validate-readme-language.mjs`: no offenders.
- `node scripts/validate-readme-architecture-diagrams.mjs`: `checked=99`, `failures=0`.
- `node scripts/validate-sequence-diagrams.mjs`: `checked=74`, `failures=0`.
- `diagram-geometry-audit.py`: architecture/state `geometry_failures=0`.
- `diagram-mixed-corner-audit.py`: `PASS files=2 paths=15 q_bends=10 failures=0`.
- `diagram-endpoint-audit.py`: `PASS files=2`.
- `xmllint --noout` for all three SVG files: no output.

## Remaining Risk

- Full repository tests were not run. The changed CI lane and targeted messaging smoke cover the affected workshop modules.
- GitHub Actions still need to validate the PR branch after push.
