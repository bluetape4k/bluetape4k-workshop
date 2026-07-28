# Workshop Ecosystem Code Patterns 사양 리뷰

날짜: 2026-07-04
범위: `docs/superpowers/specs/2026-07-04-workshop-ecosystem-code-patterns-design.md`
리뷰 게이트: Step 2-R, 7-Tier spec review

## 검토한 근거

- `./gradlew projects --console=plain`: `BUILD SUCCESSFUL in 11s`.
- `./gradlew build --max-workers=1 --console=plain`: `BUILD SUCCESSFUL in 2m 44s`.
- 탐색 시점의 GitHub 상태: 열린 issue 없음, 열린 PR 없음.
- 이전 완료 작업 확인: PR #379, PR #389, PR #393; issue #390, #391, #392, #380.
- 이전 workshop ecosystem-pattern 및 coverage/validation matrix 작업에 대한 GNO 근거를 확인했다.
- 초기 pattern scan: 등록된 Gradle project 100개 중 62개가 하나 이상의 후보 pattern을 갖고 있었다.

## 리뷰 Lane

| Lane | 초기 판정 | 발견 사항 | 해결 |
|---|---|---|---|
| Performance | PASS | P2/P3: hot-path 우선순위, blocking/sleep 분류, benchmark/perf-demo 규칙, wave-boundary 정의 | cache/gatling 후보, blocking 분류표, perf-demo 검증을 추가하고 wave-boundary 규칙을 보정했다. |
| Stability | BLOCK | P1: no-op 안정성 근거 공백, Testcontainers cross-worktree 병렬 위험. P2/P3: cancellation/lifecycle, per-wave refresh, orphan residue, no-op schema | no-op 7-Tier P0/P1 요구사항, 직렬 Testcontainers queue, 안정성 영향 edit 규칙, per-wave refresh, residue inspection, matrix schema를 추가했다. |
| Security | BLOCK | P1: 민감/public error contract의 non-echoing 보장과 token/key leakage scan 누락. P2: unsafe deserialization/default typing 기준 누락 | security acceptance criteria, leak scan, deserialization boundary severity를 추가했다. |
| Operator/Ops | BLOCK | P1: one-module-one-branch runbook 없음, live CI/check 근거 누락. P2: no-op matrix path/schema, observability evidence, rollback/supersede rules | branch/PR runbook, live metadata/check gate, matrix path/schema, observability 기준, rollback/supersede rule을 추가했다. |
| Developer/API | PASS | P2: teaching-intent blocking/demo code와 snippet에 명시적 예외가 필요함. P3: no-op matrix schema | blocking/teaching-intent 분류표와 matrix schema를 추가했다. |
| User/Caller | BLOCK | P1: README/KDoc 언어 정책 누락. P2: PR 본문이 learner-facing teaching value를 요구하지 않음 | README/KDoc 정책, grep-check 규칙, `What this teaches` PR 본문 요구사항을 추가했다. |

## 재실행 결과

사양 수정 후 영향 lane을 재실행했다.

| Lane | 재실행 판정 | 근거 |
|---|---|---|
| Stability | PASS | no-op stability review, Testcontainers 직렬 규칙, 분류표, per-wave refresh, residue inspection, matrix schema 사양 섹션으로 기존 P1/P2/P3를 해결했다. |
| Security | PASS | non-echoing contract, 민감 값 scan, unsafe deserialization에 대한 security acceptance criteria로 기존 P1/P2를 해결했다. |
| Operator/Ops | PASS | branch/PR runbook, live PR/CI gate, no-op matrix path/schema, observability criteria, rollback/supersede rule로 기존 P1/P2를 해결했다. |
| User/Caller | PASS | README/KDoc 정책과 PR `What this teaches` 요구사항으로 기존 P1/P2를 해결했다. |

## 통합 판정

P0: 0
P1: 0
P2/P3: 사양에 반영했거나 implementation-plan 점검으로 이관했다.

Step 2-R 상태: PASS.
