# Issue 391 Blocking Boundary Audit

## 배경

Issue #391은 milestone 1.3.1 code-pattern audit의 후속이다. repository에는 여전히 많은
`Thread.sleep(...)` 호출과 소수의 production `runBlocking { ... }` bridge가 있었다.

## 결정

- test가 observable asynchronous condition을 기다릴 때는 sleep을 교체한다.
- blocking, lock lease expiry, rate limiting, cache latency, virtual-thread behavior를
  의도적으로 보여주는 example의 sleep은 유지한다.
- production `runBlocking`은 blocking callback이 suspend leader work를 호출해야 하는 Spring
  scheduler boundary에서만 유지한다.
- 남은 broad cluster는 기계적 교체 뒤에 숨기지 말고 문서화한다.

## 결과

- `Thread.sleep(...)` direct calls moved from `113` to `106`.
- `runBlocking(...)` / `runBlocking { ... }` direct calls stayed at `20`; `src/main` stayed at `16`.
- Affected tests now use Awaitility or coroutine launch semantics rather than fixed sleeps.

## 검증

- Baseline full build passed before edits.
- Affected compile passed after replacing sleeps and adding KDoc.
- Affected tests passed with `--max-workers=1`.
- Post-work full build passed with `./gradlew build --max-workers=1 --warning-mode all --console=plain`.
- `git diff --check` passed.

## 향후 guard

sleep 또는 `runBlocking`을 변경하기 전에 다음처럼 분류한다.

- async observation 대기: Awaitility 또는 bluetape4k coroutine await helper를 사용한다.
- scheduler bridge: boundary에서만 유지하고 cancellation behavior를 문서화한다.
- teaching/demo latency: README/KDoc/test name이 lesson을 명확히 만든다면 유지한다.
- lease/TTL/no-growth window: immediate assertion 대신 condition polling 또는 Awaitility
  `during`을 선호한다.
