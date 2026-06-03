# Leader Election Workshop

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Leader Election Workshop** 모듈을 실행 가능한 리더 선출 조정 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 개요

다중 인스턴스(멀티 파드) 배포 환경에서는 캐시 워밍, 아웃박스 발행, 오래된 워크플로 정리 같은
스케줄 백그라운드 잡이 **정확히 하나의 인스턴스**에서만 실행되어야 합니다.
이 모듈은 `bluetape4k-leader`의 `LettuceLeaderElector`를 사용해 모든 실행 인스턴스에서
단일 실행을 보장하는 방법을 보여줍니다.

## 아키텍처

![Leader Election Workshop Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/leader-leader-election-readme-architecture-01.png)

여러 앱 인스턴스가 Redis `SET NX EX`로 분산 락을 경쟁합니다.  
**선출된 리더** 인스턴스만 각 `LeaderGuardedJob`을 실행합니다.

## 사용된 Bluetape4k 기능

| 기능 | 아티팩트 | 용도 |
|------|----------|------|
| `bluetape4k-leader-core` | `bluetape4k.leader.core` | `LeaderElector` 인터페이스, `LeaderElectionOptions` |
| `bluetape4k-leader-redis-lettuce` | `bluetape4k.leader.redis.lettuce` | `LettuceLeaderElector` — Redis 기반 구현체 |
| `bluetape4k-logging` | `bluetape4k.logging` | `KLogging` companion + 람다 로깅 확장 |
| `bluetape4k-junit5` | `bluetape4k.junit5` | `MultithreadingTester` — 동시 선출 테스트 |
| `bluetape4k-testcontainers` | `bluetape4k.testcontainers` | `RedisServer.Launcher.redis` 싱글턴 패턴 |
| `bluetape4k-assertions` | `bluetape4k.assertions` | `shouldBeEqualTo`, `shouldNotBeNull`, `shouldHaveSize` |

## 주요 패턴

### 1. Leader-Guarded Job 인터페이스

```kotlin
interface LeaderGuardedJob {
    val lockName: String   // 잡별 고유 Redis 키
    fun execute()          // 선출된 리더에서만 호출됨
}
```

### 2. LettuceLeaderElector를 이용한 리더 선출

```kotlin
val result: T? = leaderElector.runIfLeader(job.lockName) {
    job.execute()   // 이 인스턴스가 락을 획득한 경우에만 실행
}
// result == null → 리더가 아님 (스킵)
// result != null → 리더로 선출되어 실행 완료
```

### 3. Duration 타입 변환 (중요)

`@ConfigurationProperties`는 `java.time.Duration`으로 바인딩됩니다.  
`LeaderElectionOptions`는 `kotlin.time.Duration`을 요구합니다.  
**반드시 변환해야 합니다**:

```kotlin
LeaderElectionOptions(
    waitTime  = props.waitTime.toKotlinDuration(),   // ← 필수
    leaseTime = props.leaseTime.toKotlinDuration(),  // ← 필수
)
```

### 4. 스케줄러에서 잡 격리

각 잡은 독립적인 `try/catch`로 감쌉니다. 하나의 잡이 실패해도 나머지 잡 실행을 막지 않습니다:

```kotlin
jobs.forEach { job ->
    try {
        val result = leaderElector.runIfLeader(job.lockName) { job.execute() }
        if (result != null) log.info { "[LEADER] ${job.lockName} executed" }
        else log.debug { "[SKIPPED] ${job.lockName}" }
    } catch (e: Exception) {
        log.error(e) { "[ERROR] ${job.lockName}: ${e.message}" }
    }
}
```

## 클래스 다이어그램

![클래스 다이어그램](../../docs/images/readme-diagrams/leader-class-diagram.png)

## 시퀀스 다이어그램

![리더 선출 시퀀스](../../docs/images/readme-diagrams/leader-election-sequence.png)

## 실행 방법

### 애플리케이션 시작

```bash
# localhost:6379에 Redis가 실행 중이어야 합니다
./gradlew :leader-leader-election:bootRun
```

### 테스트 실행

```bash
# 전체 테스트 실행 (smoke 테스트는 기본 제외)
./gradlew :leader-leader-election:test

# smoke 테스트 포함 실행 (타이밍 민감, 수동/야간 실행용)
./gradlew :leader-leader-election:test -Djunit.jupiter.execution.exclude.tags=
```

## 설정

```yaml
leader:
  redis:
    url: redis://localhost:6379
  wait-time: 2s         # 락 획득 대기 시간
  lease-time: 30s       # 락 TTL (Redis 키 만료 시간)
  job-fixed-delay: PT10S  # 잡 실행 간격 (fixed delay)
```

## 테스트 커버리지

| 테스트 | 클래스 | 설명 |
|--------|--------|------|
| T0 | `LeaderElectionContextTest` | Spring Boot 컨텍스트가 모든 리더 빈과 함께 로드됨 |
| T1 | `LeaderElectionSingleRunnerTest` | 단일 인스턴스가 락을 획득하고 실행함 |
| T2 | `ConcurrentLeaderElectionTest` | 3개 동시 인스턴스 중 정확히 1개만 당선 (`MultithreadingTester`) |
| T3 | `LeaderElectionJobRecoveryTest` | 예외 후 락이 해제되고 재선출 성공 |
| T4 | `MultiJobIndependenceTest` | lockName이 다른 두 잡이 모두 실행됨 |
| T5 *(smoke)* | `LeaseExpiryTest` | 학습용: lease TTL 만료 동작 |
| T6 *(smoke)* | `RedisFailureTest` | 학습용: Redis 장애 시 예외 전파 |
| T7 | `LockReleaseTest` | `finally { unlock }`으로 즉시 재획득 가능 |
| P3-11 | `DuplicateLockNameTest` | 중복 lockName은 시작 시 `IllegalStateException` 발생 |
| P3-12 | `JobIsolationTest` | 실패한 잡이 후속 잡 실행을 막지 않음 |
| P3-13 | `PropertiesValidationTest` | `leaseTime < waitTime`이면 `IllegalArgumentException` 발생 |
