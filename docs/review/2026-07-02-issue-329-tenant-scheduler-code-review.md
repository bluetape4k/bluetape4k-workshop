# Issue #329 Code Review

**날짜**: 2026-07-02
**범위**: `leader/tenant-scheduler`와 README, diagram, smoke, Examples workflow 등록.
**리뷰 관점**: implementation, diagram repair, validation 이후의 7-tier review.

## 발견사항

P0 발견사항: 0
P1 발견사항: 0
P2 발견사항: 0
P3 발견사항: 0

해결된 review 발견사항:

- P1 untracked implementation/assets: PR 생성 전에 새 module, diagram, docs, workflow, smoke change를 staging/commit하여 해결했다.
- P2 privacy validation command가 필요한 negative test fixture까지 scan했다. README/main/resources/runtime artifact를 scan하되 `src/test/kotlin` negative fixture를 제외하도록 spec command를 수정했다.
- P2 stale handoff isolation에 combined scenario가 부족했다. `stale handoff does not disturb unrelated tenant lease`를 추가했다.

## 7-Tier Review

| Tier | 결과 | 근거 |
|------|--------|----------|
| Performance | PASS | lab은 순수 logical-tick reducer이며 Redis, ZooKeeper, Kubernetes, Docker, network client, background scheduler를 시작하지 않는다. stress test는 `eventHistoryLimit`로 report를 bounded하게 유지한다. |
| Stability | PASS | `TenantSchedulePolicy`는 empty/duplicate tenant와 invalid numeric bound를 거부한다. `TenantScheduleTick`은 duplicate candidate, due tenant, action failure, seed lease를 거부한다. `TenantSchedulerLab`도 lock name이 policy job과 맞지 않는 seed lease를 거부한다. |
| Security/privacy | PASS | Tenant, job, node alias는 canonicalize되며 whitespace, control character, email-like value, account-id-shaped identifier를 raw unsafe input echo 없이 거부한다. Metric tag는 cardinality가 안전하지 않을 때 `tenant=bounded`로 degrade된다. |
| Operator | PASS | README는 module이 infrastructure-free이며 production system은 reducer를 `TenantScopedLeaderElectors`와 선택된 backend로 대체해야 한다고 명시한다. Smoke validation과 Examples workflow는 module을 포함한다. |
| Developer/API | PASS | Public value type은 named wrapper와 `Serializable`을 사용하고, scheduler contract에서 same-type raw parameter API를 피하며, 공통 numeric/string validation에 bluetape4k validation helper를 사용한다. 테스트는 JUnit 5와 bluetape4k assertion을 사용한다. |
| User/learner | PASS | README와 README.ko는 language switch, architecture/sequence diagram, executable snippet, scenario table, test map, run command, production boundary를 포함한다. |
| Current-session integration | PASS | Spec, plan, module registration, root README row, smoke script, Examples workflow, diagram, review evidence가 issue #329 및 milestone 1.3.1 scope와 일치한다. |

## 검증 근거

- RED identifier/planner/scheduler check는 production class 추가 전 실패했다.
- GREEN unit slice는 identifier validation, lock-name planning, metric-tag policy, scheduler scenario, README snippet execution에 대해 통과했다.
- Module test: `./gradlew --no-daemon :leader-tenant-scheduler:test --no-build-cache --rerun-tasks --console=plain`은 19 tests로 통과했다.
- Compile: `./gradlew --no-daemon :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all --console=plain` 통과. 표시된 warning은 기존 root Gradle Kotlin DSL deprecation이며 touched module source warning이 아니다.
- Projects: `./gradlew --no-daemon projects --console=plain`이 통과했고 `:leader-tenant-scheduler`를 나열했다.
- Smoke: `./scripts/smoke-validate.sh all-smoke`는 `BUILD SUCCESSFUL` 및 288 actionable tasks로 통과했다.
- Stale check: `./scripts/smoke-validate.sh stale-check`는 99/99 modules, stale ref 없음, broken README image link 없음을 보고했다.
- Diagram QA: `node scripts/validate-readme-diagram-qa.mjs docs/images/readme-diagrams/leader-tenant-scheduler-readme-architecture-01.svg docs/images/readme-diagrams/leader-tenant-scheduler-readme-sequence-01.svg`는 `targets=2`, `weak_reference_rows=0`, architecture `q_bends=12`, sequence `markers_checked=8`, `labels=8`, `numbers=8`, `alt_fill_failures=0`, `sequence style reference audit: PASS`로 통과했다.
- Sequence label repair: rendered-PNG eye review는 처음에 label/line spacing 실패를 보고했다. SVG는 이제 labels 1-8 각각의 label pill bottom과 해당 call line 사이에 32px gap을 갖고, follow-up independent vision review가 PASS를 반환했다.
- Eye inspection: 최종 coordinate 변경 후 두 rendered PNG를 full-size로 열었다. Architecture에는 broken icon, text overlap, connector intrusion, missing legend가 없다. Sequence는 label separation, matching arrowhead color, transparent alt body, readable activation/lifeline structure를 갖는다.
- Workflow: `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml` 통과.
- Whitespace: `git diff --check` 통과.
- Code-pattern scan: 새 module의 production code에는 `runBlocking`, `runCatching`, `GlobalScope`, broad coroutine cancellation trap, `synchronized`, direct Testcontainers usage가 없다.

## 잔여 위험

module은 tenant-scoped scheduling semantic을 모델링하지만 contention 상황의 real distributed lock backend를 증명하지 않는다. 이는 기본 smoke-safe workshop lab을 위한 의도적 범위 제한이며, backend-heavy practice는 Redis, ZooKeeper, Kubernetes Lease module에 남아 있다.
