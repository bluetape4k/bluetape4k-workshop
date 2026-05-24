# 2026-05-24 Messaging/Redis Advanced — BT Feature Documentation (Issue #81)

## 작업 개요

Issue #81 — 메시징/Redis 고급 모듈 README에 bluetape4k(BT) 기능 표, Before/After 코드 비교,
Mermaid 다이어그램, 운영 주의사항을 추가했다.

대상 모듈 (Issue #83 "basic" 커밋에서 누락된 2개 모듈):
- `redis/cluster-demo` — `RedisClusterServer.Launcher`, `Launcher.LettuceLib`, `Fakers`, Mermaid 클러스터 토폴로지 다이어그램, 운영 주의사항
- `redis/distributed-lock` — `RedisServer.Launcher`, `redissonClient {}` DSL, `getLockId()`, `SuspendedJobTester`, `MultithreadingTester`, NonCancellable unlock Before/After

참고: `messaging/kafka`, `messaging/kafka-reply`, `redis/redisson-examples` 3개 모듈은
커밋 `124f07c4` (Issue #83) 에서 이미 완료.

---

## 주요 발견

### 1. RedisClusterServer.Launcher — 6노드 클러스터 원스텝 구동

`bluetape4k-testcontainers`의 `RedisClusterServer.Launcher.redisCluster`는 3마스터+3슬레이브
Redis Cluster를 단 한 줄로 자동 구동·종료하는 싱글톤이다.
`GenericContainer` 6개를 수동 관리하고 `@DynamicPropertySource`로 노드 목록을 조합하던
기존 방식을 완전히 대체한다.

추가로 `Launcher.LettuceLib.clientResources(redisCluster)` 를 `@Bean`으로 등록하면
Lettuce `ClientResources`가 컨테이너 포트에 맞게 자동 구성된다.

### 2. Lettuce adaptive refresh — 필수 설정

Redis Cluster 운영 시 노드 장애/재시작이 발생하면 클라이언트가 구버전 토폴로지를 계속
참조하여 연결이 실패할 수 있다.
`application.yml`에 `lettuce.cluster.refresh.adaptive: true` 와
`dynamic-refresh-sources: true` 를 함께 설정해야 자동 갱신이 활성화된다.
이 설정이 없으면 컨테이너 재시작 시 테스트도 간헐적으로 실패한다.

### 3. Mac AirPlay 포트 충돌

Redis Cluster는 기본적으로 7000–7005 포트를 사용한다.
macOS Monterey 이후 AirPlay 수신기가 기본적으로 7000 포트를 점유하므로,
로컬 개발 환경에서 포트 충돌이 발생할 수 있다.
시스템 환경설정 → AirPlay 수신기 비활성화 후 재실행하면 해결된다.

### 4. getLockId() — 코루틴 안전 FencedLock ID 2단계 취득 패턴

`bluetape4k-redis` (`io.bluetape4k.redis.redisson.coroutines`) 패키지의
`RedissonClient.getLockId(lockName)` 확장 함수는 Snowflake ID를 기반으로 코루틴 환경에서
`RFencedLock`의 고유 ID를 안전하게 획득한다.

Redisson 4.x에는 `tryLockAndGetTokenAsync(lockId)` 오버로드가 없으므로,
2단계 패턴이 필수다:

1. `redisson.getLockId(lockName)` — mlockId 획득 (Snowflake ID)
2. `fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()` — 코루틴 안전 lock 시도
3. `fLock.tokenAsync.await()` — 펜싱 토큰 획득 (별도 단계)
4. `withContext(NonCancellable) { fLock.unlockAsync(lockId).await() }` — finally 블록에서 반드시 NonCancellable로 감쌈

### 5. NonCancellable unlock — 코루틴 취소 안전성

`SuspendingFencedInventoryService`의 `finally` 블록에서 `unlockAsync(lockId).await()`를
`withContext(NonCancellable)` 로 감싸지 않으면, 호출 코루틴이 취소될 때
`CancellationException`이 `await()` 를 중단시켜 lock이 lease 만료까지 누출된다.

이 패턴은 kotlin 공식 가이드의 "Suspending in finally" 주의사항과 동일한 근거다.
suspend 함수가 있는 `finally` 블록에서는 반드시 `NonCancellable`을 사용해야 한다.

### 6. `getLockId()` 아티팩트 위치 정정

`getLockId()` 는 `bluetape4k-redisson` 이 아니라 `bluetape4k-redis`
(`io.bluetape4k.redis.redisson.coroutines.getLockId`) 패키지에 있다.
`redis/redisson-examples` README (Issue #83) 에는 `bluetape4k-redis`로 올바르게 기재되어 있으나,
`redis/distributed-lock` 의 기존 Dependencies 섹션은 `bluetape4k-redisson`으로 잘못 기재되어 있었다.
이번 작업에서 `bluetape4k-redis (coroutines package)` 로 수정했다.

### 7. SuspendedJobTester workers×rounds 의미론

`SuspendedJobTester().workers(N).rounds(R).add { block }`:
- `N`개의 코루틴 워커가 각각 `R`번 블록을 실행
- 총 시도 횟수 = `N × R`
- 재고 100, 차감 10 테스트에서 `workers(20).rounds(1)` → 20회 시도 → 정확히 10회 성공

## 커밋 범위

변경된 파일:
- `redis/cluster-demo/README.md` — BT 기능 표, Before/After, Mermaid 토폴로지 다이어그램, 운영 주의사항 추가
- `redis/distributed-lock/README.md` — Used bluetape4k Features 표, Before/After (RedisServer.Launcher, NonCancellable unlock, SuspendedJobTester), Dependencies 섹션 보완
- `docs/lessons/2026-05-24-issue-81-messaging-advanced.md` — 이 파일
