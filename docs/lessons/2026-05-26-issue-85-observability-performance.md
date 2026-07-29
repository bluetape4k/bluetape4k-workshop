# Issue #85 — Observability/Performance Advanced 완료

## 배경

PR #178에서 여러 observability, Gatling, virtual-thread README를 개선한 뒤에도
Issue #85는 아직 열려 있었다. 남은 gap은 `observability/observability-advanced`에
있었다. 해당 README는 Bluetape4k-first table/before-after/load-command requirement를
완전히 만족하지 못했고, local `observed()` helper는 coroutine dispatcher boundary를
가로질러 Micrometer scope를 열지 않은 채 observation을 start/stop하고 있었다.

## 결정

known `withObservationContextSuspending` happy-path stop issue를 우회하기 위해 local
`observed()` wrapper는 유지한다. 대신 `ThreadContextElement`를 사용해
coroutine-scope-aware하게 만든다. 이렇게 하면 structured cancellation behavior를
보존하면서도 `withContext(Dispatchers.IO)` 이후 child observation이 현재 parent
observation을 볼 수 있다.

## 결과

- `observed()`는 이제 span name을 검증하고, coroutine context를 통해 Micrometer
  scope를 열고 닫으며, `CancellationException`을 다시 던지고, 실제 error만 기록하며,
  observation을 항상 stop한다.
- `UserServiceTest`는 cache miss, cache hit, create path에서 parent-child span
  relationship을 증명한다.
- `README.md`와 `README.ko.md`는 이제 Used Bluetape4k features table,
  raw-vs-Bluetape4k before/after, smoke command, retained load module, stop condition을
  포함한다.

## 검증

- `./gradlew :observability-advanced:test` — 테스트 8개 통과.
- `git diff --check` — clean.
- 이 Codex App session에서 IntelliJ diagnostics를 시도했지만 timeout되었다. targeted
  Gradle compile/test를 fallback으로 사용했다.
- Claude Code CLI review-style prompt는 반복적으로 빈 artifact를 만들었지만, focused
  blocker prompt는 다음 artifact를 만들었다.
  - `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`
  - `.omx/artifacts/claude-issue-85-code-blockers-20260526060522.md`

## 향후 guard

coroutine 기반 Micrometer helper는 span lifecycle과 parent context propagation을 모두
테스트한다. 작업이 `withContext(...)` dispatcher boundary를 넘는 경우, "span exists"
assertion이 green인 것만으로는 충분하지 않다.
