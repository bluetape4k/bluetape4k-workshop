# Issue #558 Kafka multi-broker failover 구현 계획 검토

## 검토 범위

- 검토일: 2026-08-26
- 대상: `docs/superpowers/plans/2026-08-26-issue-558-kafka-multi-broker-failover-plan.md`
- 기준: 승인된 #558 설계/spec, `AGENTS.md`, `$bluetape-workflow`, `$bluetape-kotlin-patterns`와 필수 testing/Spring Boot/module-setup/checklist reference
- 방식: performance, stability, security, operator/ops, developer/API, user/caller의 독립 read-only lane과 leader 통합 검토
- 경계: 계획과 검토 artifact만 다루며 구현 source, issue, PR, workflow, README, image는 아직 수정하지 않음

## 독립 관점 결과

| 관점 | 초기 결과 | 계획 반영 | 최종 상태 |
|---|---|---|---|
| Performance | P0=0, P1=7, P2=4 | shared 420초 module deadline, batch await, tracked startup, run-scoped artifact, sanitizer gate, cross-process lock, nightly 중복 제거 | P0=0/P1=0 |
| Stability | P0=0, P1=11, P2=1; r2에서 P1=1, P2=2 | topic 생성 순서, `MAX_RETRY_ATTEMPTS=5`, outer `finally` cleanup/suppressed 보존, cancellation quiescence와 replacement rollback 명시 | P0=0/P1=0 |
| Security | P0=0, P1=5, P2=4 | digest-qualified image, RepoDigests 단일 일치, loopback bind/DNS 검증, protocol-scoped listener allowlist, strict codec와 canary scanner | P0=0/P1=0 |
| Operator/Ops | P0=0, P1=6, P2=5 | 18-field/run-scoped evidence, terminal failure row, sanitizer-before-upload, push/pull filter, `if-no-files-found: error`, validation matrix와 smoke/stale non-zero | P0=0/P1=0 |
| Developer/API | 초기 P0=0, P1=2, P2=5, P3=2; 최종 bounded fallback P0=0/P1=0 | `localhost` loopback DNS, 내부 `BROKER` 범위 분리, immutable factory, `annotationProcessor`, TDD 순서, 정확한 경로, 공통 `CLUSTER_ID`, 기존 launcher 비재사용 근거 | P0=0/P1=0 |
| User/Caller | P0=0, P1=2, P2=4, P3=1 | validation matrix, 구조/의미 README parity, preflight/runbook/evidence 해석/unsupported 범위, focused command와 fresh fixture 안내 | P0=0/P1=0 |

초기 P1/P2/P3는 각 lane의 evidence를 기준으로 계획에 반영했다. API 재검토 native lane은 bounded 시간 내 응답하지 않아 중단하고, 기존 API 결과와 현재 계획을 leader가 read-only로 재검증하는 fallback을 사용했다. 구현 진입을 막는 미해결 P0/P1은 없다.

## 통합 검토 근거

1. **계약 추적성:** 계획의 source ledger(13–26행)는 설계의 topology, event/client, 두 failover scenario, timeout/evidence, DoD를 Task 1–11과 fresh 검증 명령에 매핑한다.
2. **Kotlin 준수:** 28–38행은 `$bluetape-kotlin-patterns`와 testing/Spring Boot/module-setup/checklist를 명시적으로 연결하고, `require*`/`check`, immutable value object, explicit serializer, bounded blocking, TDD, sequential Testcontainers, concrete N/A를 고정한다.
3. **실행 경계:** 15행은 계획 별도 승인 전 구현을 금지한다. 44–59행은 `src/main`과 `src/test`의 Testcontainers/Admin/evidence 경계를 분리하고, 72–84행은 최소 골격 → RED → 구현 → GREEN 순서를 고정한다.
4. **토폴로지/수명주기:** 123–141행은 단일 `ModuleDeadline`, `NEW → STARTING → RUNNING → STOPPING → CLOSED`, tracked `deepStart`, post-validation rollback, 공통 `CLUSTER_ID`, digest/loopback/listener 계약, `MAX_RETRY_ATTEMPTS=5`, outer cleanup을 명시한다. 193–221행은 user topic → assignment → internal offset 순서, 실제 leader/coordinator identity, exact logical ID를 고정한다.
5. **증거/운영:** 152–162행과 291–299행은 18개 field, 고정 phase, atomic run artifact, terminal PASS/FAIL, performance counter, canary scanner와 CI upload gate를 같은 경로로 연결한다. 223–254행은 양 locale README, validation matrix, Examples push/pull, nightly, smoke/stale-check를 동기화한다.
6. **계획 외부 상태:** 계획에는 구현 runtime, Docker/Colima, Testcontainers, CI, Kover/detekt 결과가 아직 없으며 이를 PASS로 주장하지 않는다. 승인 후 구현 단계에서 fresh evidence를 수집한다.

## 정적 검증

- `git diff --check`: PASS
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/superpowers/plans/2026-08-26-issue-558-kafka-multi-broker-failover-plan.md`: PASS (`findings=0`)
- evidence schema 검토: required field 18개, unique 18개; phase 10개, unique 10개
- placeholder 검토: 실행 명령의 run ID/container ID 생성과 빈 ID fail-fast를 명시했으며 미확정 꺾쇠 placeholder 없음
- 계획은 아직 untracked이며 source/README/workflow/image 변경은 없음

## 판정

**계획 검토 PASS — 구현 준비 가능.** 최종 P0=0/P1=0이며, 승인된 계획 커밋 후 구현 단계로 이동할 수 있다. 다만 현재 stop condition은 사용자의 별도 implementation-plan 승인이다. 그 승인이 있기 전에는 코드, README, workflow, validation matrix, diagram, issue/PR를 수정하지 않는다.

## DoD Status

- Plan review: PASS
- Independent six-lens P0/P1: 0/0
- Kotlin pattern traceability: PASS
- Plan static checks: PASS
- Implementation/runtime/CI: PENDING (계획 승인 전 미실행)
- PR/merge/issue close: PENDING (별도 권한/승인 필요)
