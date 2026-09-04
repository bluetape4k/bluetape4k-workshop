# Issue #878: RLocalCachedMap 숫자 원자 갱신과 client 무효화

## Context

기존 `redis-redisson-examples`는 local cache의 기본 CRUD를 보여주지만,
`bluetape4k-dependencies:2.0.0`에서 제공하는 `RLocalCachedMap.addAndGetAsync`의
Redis 원자 갱신과 독립 client 사이의 local invalidation 경계를 고정하지 않았다.
공통 `RedissonClient`는 압축 `LZ4FastForyComposite` codec을 사용하므로, 이 codec의
직렬화 payload를 Redis `HINCRBYFLOAT`에 그대로 전달하면 숫자 갱신이 실패한다.

## Decision or Finding

- String key와 숫자 value를 `CompositeCodec`으로 분리하고, Int에는
  `CompositeCodec(String, Int, Int)`, Double에는
  `CompositeCodec(String, Double, Double)`을 사용한다.
- `addAndGetAsync`는 Redis hash field에 `HINCRBYFLOAT`를 실행하므로 같은 map에서
  Int와 Double codec을 섞지 않는다. 기본 압축 codec은 일반 cache 데이터에만 남긴다.
- 두 독립 `RedissonClient`가 같은 map을 관찰하도록 먼저 local value를 warm-up한
  뒤 concurrent increment를 수행한다. source client의 명시적인 local-cache clear를
  barrier로 사용하고 bounded polling으로 양쪽 local view와 remote map의 수렴을
  확인한다.
- 모든 비동기 대기는 `awaitRedis`로 제한한다. timeout이나 caller cancellation 때
  underlying `RFuture`를 취소하여 테스트 종료 뒤 pending Redis operation을 남기지
  않는다.

## Outcome

`LocalCachedMapExamples`에 Int/Double 빈 key 초기화와 remote round-trip 예제를
추가했고, `LocalCachedMapTest`는 두 client의 원자 증가, 숫자가 아닌 기존 payload의
거부, bounded invalidation을 검증한다. 공통 fixture의 test-owned client는 명시적으로
종료하여 global shutdown hook과 lifecycle을 분리했다.

## Verification

- targeted tests: `LocalCachedMapExamples`, `LocalCachedMapTest`, `AwaitRedisTest` — 14 passing, 1 pending
- full module: `:redis-redisson-examples:test` — 126 passing, 5 pending
- README English/Korean parity, stale-check guard, `git diff --check`, and workflow YAML validation passed

## Future Guidance

숫자 `RLocalCachedMap` 예제에서는 matching `CompositeCodec`과 bounded await를
항상 함께 유지한다. 실제 운영 환경의 invalidation 지연 또는 Redis cluster
topology를 이 consumer 예제에 선제적으로 복제하지 말고, 별도 integration scope에서
측정 가능한 SLA와 함께 추가한다.
