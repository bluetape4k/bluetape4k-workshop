# Redis Redisson Examples 생태계 리뷰

날짜: 2026-07-05
모듈: `:redis-redisson-examples`
브랜치: `refactor/redis-redisson-examples-ecosystem-patterns`

## 범위

- Redisson object, collection, lock, read/write-through example 동작은 변경하지 않았다.
- Preserve and foreground 기존 bluetape4k Redisson helper usage.
- 수정된 stream 및 local-cache path의 좁은 example-quality drift를 제거했다.

## 7-Tier 리뷰

| Tier | 관점 | 판정 | 근거 |
|---|---|---|---|
| 1 | Correctness | PASS | stream consumer assertion은 같은 message payload와 ack count를 계속 검증한다. |
| 2 | API / UX | PASS | example class name, test name, Redisson object API는 변경 없다. |
| 3 | Architecture | PASS | shared Redisson setup은 계속 `RedissonCodecs.LZ4ForyComposite`와 Redis testcontainer launcher를 사용한다. |
| 4 | Concurrency | PASS | coroutine stream consumer shape는 변경 없고 새 blocking path를 추가하지 않았다. |
| 5 | Resilience | PASS | stream message lookup은 이제 payload comparison 전에 `shouldNotBeNull`을 사용한다. |
| 6 | Tests | PASS | `./gradlew :redis-redisson-examples:test --console=plain --max-workers=1` passed. |
| 7 | Maintainability | PASS | 수정된 example은 idiomatic object spacing, ordered import, English explanatory comment를 사용한다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- Deferred: lock/rate-limiter example의 broader timing sleep은 demo workload timing이며 이 PR slice 범위 밖이다.

## DoD 상태

- `git diff --check`: PASS
- targeted test: `:redis-redisson-examples:test`: PASS
- 생태계 helper: 기존 `RedissonCodecs`, `localCachedMap`, `streamAddArgsOf`, and coroutine `await` usage 보존됨.
