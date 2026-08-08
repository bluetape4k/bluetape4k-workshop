# 예제 공통 Testcontainers 지원 성능·안정성 스캔

- 대상 diff: shared RedisTestSupport, shared Gradle 경계, Redis 예제 호출부/의존성, README 두 locale
- 기준: bluetape-full-feature Step 4-P performance-stability-scan.md
- 확인일: 2026-08-09

## 점검 결과

| Priority | File:Line | Lens | Finding | Required fix/evidence |
|---|---|---|---|---|
| P2 | shared/src/main/.../RedisTestSupport.kt:17-32 | performance | helper는 Spring context 구성 시 세 supplier만 등록하며 production hot path, 반복 컨테이너 startup, 무제한 buffering/round trip이 없다. | benchmark는 비해당으로 기록한다. shared/Redis 영향 테스트와 dependency graph를 fresh 실행한다. |
| P2 | shared/src/main/.../RedisTestSupport.kt:22 | stability | RedisServer.Launcher.redis의 지연 시작과 ShutdownQueue 소유권을 기존 local helper에서 그대로 재사용한다. 새 close/retry/cancellation 경로는 없다. | Launcher/TestMutex 경계를 보존하고 Testcontainers 명령을 직렬 실행한다. fresh shared 40 tests와 Redis 41 tests로 확인한다. |
| P2 | shared/src/test/.../RedisTestSupportTest.kt:11-29 | stability | recording registry는 supplier를 즉시 평가해 세 키와 값을 검증한다. 실제 컨테이너 생명주기는 영향 모듈에서 검증된다. | 단위 계약 테스트와 Redis 영향 테스트를 분리해 둘 다 실행한다. |
| P2 | spring-data/redis-examples/... | performance/stability | reactive stream 호출부의 동작·dispatcher·resource lifecycle은 변경하지 않고 import와 dependency만 변경한다. | Redis 영향 테스트 통과와 기존 TestMutex 직렬 정책으로 회귀를 확인한다. |

## 통합 판정

- P0=0, P1=0.
- 새 blocking/event-loop/IO 경로, retry/backoff, mutable shared state, resource leak를 도입하지 않았다.
- coroutine cancellation/virtual-thread/benchmark 검증은 변경된 동작이 없어 N/A이며, N/A 근거를 final DoD에 남긴다.
- 검증 명령: :shared:test, :spring-data-redis-examples:test를 순차 실행했고 각각 실패 0이다.
- Step 4-P DoD: PASS.
