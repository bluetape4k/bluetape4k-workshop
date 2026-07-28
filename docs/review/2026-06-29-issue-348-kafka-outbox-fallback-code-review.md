# Issue 348 Kafka Outbox Fallback Code Review

- Date: 2026-06-29
- Scope: `messaging-kafka-outbox-fallback`, README assets, smoke workflow registration, diagram validators
- Reviewer: Codex

## 발견 사항

### P1 수정됨: relay claim update 조건이 충분하지 않았다

`EventPublicationRepository.claimNextBatch()`는 먼저 eligible row를 선택한 뒤 row id만으로
update했다. competing relay transaction은 첫 transaction이 commit되기 전에 같은 row를 선택할
수 있었고, update predicate가 status, `nextAttemptAt`, claim expiry를 다시 확인하지 않았기
때문에 여전히 update할 수 있었다.

수정:

- `claimNextBatch()` now performs the update with `id AND eligibleForClaim(now)`.
- `markPublished()` and `markRelayFailure()` now require the current `claimedBy` owner.
- `EventPublicationRelay` no longer counts a Kafka send as published if the row can no longer be marked by the current worker.

증거:

- `./gradlew :messaging-kafka-outbox-fallback:test --tests '*claim*' --rerun-tasks --no-build-cache --max-workers=1 --console=plain` passed with `warnings=0`.
- `./gradlew :messaging-kafka-outbox-fallback:test --rerun-tasks --no-build-cache --max-workers=1 --console=plain` passed with `tests=16 failures=0 errors=0 skipped=0 warnings=0`.

## 잔여 finding

- P0: 0
- P1: 0

## 리뷰 참고

- hot transaction은 `orders`만 쓴다. fallback row는 Kafka direct publish failure 이후에만 생성된다.
- direct publish failure는 summary를 persist하거나 expose하기 전에 secret을 sanitize한다.
- fallback relay는 claim 기반이고, `FAILED`로 retry하며, 설정된 retry cap에서 `DEAD_LETTER`로 이동한다.
- reconciler는 publication이 없는 stale order에서 deterministic event id를 의도적으로 재구성하고
  duplicate risk를 문서화한다.
- demo admin relay/reconcile endpoint는 기본 비활성화되어 있으며, 명시적으로 활성화하지 않으면
  404를 반환한다.
- `GET /api/publications`는 raw payload 또는 raw exception text가 없는 DTO를 반환한다.
- 이 worktree의 새 untracked module에 대해 CodeReviewGraph가 유용한 structural coverage를
  제공하지 못했으므로, 이 review는 local diff inspection과 targeted test를 사용했다.

## 검증 증거

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

## 남은 위험

- full repository test는 실행하지 않았다. 변경된 CI lane과 targeted messaging smoke가 영향받은
  workshop module을 포괄한다.
- push 이후 GitHub Actions가 PR branch를 검증해야 한다.
