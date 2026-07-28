# Issue 391 Blocking Boundary Review

## 범위

- 이슈: #391 `Audit blocking sleeps and coroutine bridge boundaries`
- 작업 유형: Type B fast-track async/reactive refactor and audit.
- Diff 범위: 4 Kotlin files와 review/lesson artifacts.
- CodeReviewGraph: 이 worktree에서는 사용할 수 없었으므로 direct scan, diff review, targeted compile, targeted tests, full build를 사용했다.

## Scan Evidence

- Baseline `Thread.sleep(...)` direct calls: `113` Kotlin matches.
- refactor 후: `106`.
- Baseline `runBlocking(...)` / `runBlocking { ... }` direct calls: `20` Kotlin matches, including `16` under `src/main`.
- refactor 후: `20`, including `16` under `src/main`.
- sleep-based timing을 다음 위치에서 교체했다.
  - MongoDB tailable cursor reactive test,
  - leader event listener flow/service tests,
  - Spring coroutine scope output-capture test.

## 분류

| Category | 결정 |
|---|---|
| Async/test timing workaround | test가 eventual observation을 기다릴 때 Awaitility 또는 coroutine-aware subscription/start semantic으로 교체한다. |
| Scheduler coroutine bridge | Spring `@Scheduled` boundary에서만 `runBlocking`을 유지한다. 이를 blocking-to-suspend bridge로 문서화하고 `CancellationException` propagation을 보존한다. |
| Teaching examples | module이 blocking latency, TTL, lock lease, benchmark behavior를 시연할 때 virtual-thread/blocking/resilience/cache example의 explicit sleep은 유지한다. |
| Absence/stability checks | no-growth window에는 Awaitility `during`을 선호한다. 현재 순간만 증명하는 immediate assertion은 피한다. |
| Legacy or broad modules | focused module-specific pass 없이 Redisson, Okio, virtual-thread, cache demonstration을 기계적으로 변경하지 않는다. |

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| Security | PASS | security boundary는 변경되지 않았다. 모든 edit은 test wait 또는 scheduler bridge 문서화다. |
| Stability | PASS | sleep-based positive wait는 이제 실제 condition을 poll한다. no-growth verification은 Awaitility `during`을 사용한다. |
| Performance | PASS | fixed sleep 교체로 test의 불필요한 wait가 줄었다. production code behavior는 KDoc clarification 외에 변경되지 않았다. |
| Operator/Ops | PASS | Testcontainers-backed affected module은 `--max-workers=1`로 serial 실행했다. container launcher나 CI workflow는 변경되지 않았다. |
| Developer/API | PASS | `runBlocking`은 scheduler entry point로 제한되고, 일반 production pattern이 아니라 bridge로 문서화된다. |
| User/Caller | PASS | Public example behavior와 learner-facing runtime behavior는 변경되지 않았다. |
| Evidence | PASS | Affected compile/test가 통과했고 post-work full build가 통과했다. |

## 검증 근거

- clean `develop`에서 작업 전 local build: `./gradlew build --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL in 1m 35s`.
- Affected compile: `:spring-data-mongodb-coroutines:compileTestKotlin :leader-leader-election:compileTestKotlin :kotlin-coroutines:compileTestKotlin` -> `BUILD SUCCESSFUL in 18s`.
- Affected tests: `:spring-data-mongodb-coroutines:test :leader-leader-election:test :kotlin-coroutines:test` -> `BUILD SUCCESSFUL in 27s`.
- scheduler KDoc 후 affected compile: `:spring-data-mongodb-coroutines:compileTestKotlin :leader-leader-election:compileTestKotlin :leader-k8s-lease-micrometer:compileKotlin :kotlin-coroutines:compileTestKotlin` -> `BUILD SUCCESSFUL in 3s`.
- Post-work full local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 51s`.
- Whitespace check: `git diff --check` -> PASS.

## 발견사항

- P0/P1: 0.
- P2: 남은 sleep-heavy cluster는 의도적으로 넓은 teaching/demo 영역이다. Redisson examples, Okio pipe/cursor timing, virtual-thread blocking demonstrations, cache TTL/latency examples, lock lease simulations가 여기에 속한다.
- P3: future work는 module별로 나눠야 한다. 각 cluster는 correctness criteria와 replacement helper가 서로 다르기 때문이다.
