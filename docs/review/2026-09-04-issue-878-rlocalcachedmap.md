# Issue #878 RLocalCachedMap 숫자 원자 갱신 검토

## 검토 범위

- 기존 `redis/redisson-examples`의 `RLocalCachedMap` CRUD 예제와 lifecycle
- `bluetape4k-dependencies:2.0.0`의 Redisson numeric map codec 계약
- Redis `HINCRBYFLOAT`, 독립 client local invalidation, coroutine cancellation

## 결정

예제는 일반 cache payload에 사용하는 압축 codec과 numeric increment map을
분리한다. Numeric map은 String key와 Int 또는 Double value를 matching
`CompositeCodec`으로 구성하며, `addAndGetAsync` 결과를 remote map에서 다시 읽어
원자 갱신을 확인한다. 두 독립 client는 warm-up 이후 concurrent increment를
수행하고 명시적 clear barrier와 bounded polling으로 local view 수렴을 확인한다.

`awaitRedis`는 성공/실패 future를 그대로 반환·전파하고 timeout 또는 호출자 취소
시 pending `RFuture`를 취소한다. 이 경계는 테스트가 종료된 뒤 Redis 작업이 남지
않도록 하며, 숫자가 아닌 기존 payload는 `RedisException`으로 거부된다.

## 검증 증거

- targeted tests: 14 passing, 1 pending
- full `:redis-redisson-examples:test`: 126 passing, 5 pending
- README parity/language, stale-check, workflow guard, and `git diff --check` 검증
- `2.1.0` 또는 `2.1.0-SNAPSHOT` 참조 없음

## 범위 밖

Redis Cluster 장애 주입, cross-region invalidation SLA, durable schema migration은
다루지 않는다. 해당 운영 경계는 실제 topology와 측정 기준을 별도 이슈로 정의한 뒤
검토한다.
