# bucker4j-bluetape4k-webflux 생태계 리뷰

날짜: 2026-07-05
모듈: `:bucker4j-bluetape4k-webflux`
브랜치: `refactor/bucker4j-bluetape4k-webflux-ecosystem-patterns`

## 범위

- support helper 사용을 위해 direct `bluetape4k-core` dependency를 선언했다.
- resolved rate-limit key를 bluetape4k `requireNotBlank`로 검증했다.
- fallback 전에 coroutine cancellation을 다시 던지면서 Bucket4j Redis rate-limit 동작을 보존했다.

## 7-Tier 리뷰

| Tier | 관점 | 판정 | 근거 |
|---|---|---|---|
| 1 | Security/input | PASS | header/IP-derived rate-limit key는 token consumption 전에 명시적으로 보호된다. |
| 2 | Architecture | PASS | WebFilter ordering, Bucket4j proxy provider, Redis configuration, controller endpoint는 변경 없다. |
| 3 | Reactive/coroutines | PASS | coroutine cancellation은 broad fallback handling 전에 async filter에서 다시 던진다. |
| 4 | Code quality | PASS | regex target은 request마다 다시 만들지 않고 재사용한다. 수정된 Kotlin spacing과 public KDoc을 정규화했다. |
| 5 | Tests | PASS | `./gradlew :bucker4j-bluetape4k-webflux:test --console=plain --max-workers=1`가 통과했다. |
| 6 | Operations | PASS | Redis Testcontainers launcher와 rate-limit token setting은 변경 없다. |
| 7 | 근거/docs | PASS | 첫 compile에서 KDoc `/**` token bug가 드러났고, doc-string 복구 후 `git diff --check`와 module test가 통과했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- Deferred: disabled IP-address rate-limit test는 설계상 coroutine/reactive path 간 충돌이 있으므로 변경하지 않았다.
