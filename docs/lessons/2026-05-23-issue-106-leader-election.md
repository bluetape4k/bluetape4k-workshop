# Leader Election Workshop 모듈 구현 (Issue #106)

날짜: 2026-05-23  
브랜치: `feat/issue-106-leader-election`  
PR: TBD

---

## 배경

다중 인스턴스 환경에서 스케줄 작업(캐시 워밍업, 오래된 워크플로우 정리)을 단일 실행자만 처리하도록 보장하는 예제 모듈 구현. `bluetape4k-leader` (v0.1.0, Redis-backed) 라이브러리 사용.

---

## 핵심 발견 사항

### 1. bluetape4k-leader 패키지 경로 (중요)

`bluetape4k-leader-redis-lettuce` JAR의 실제 패키지 경로:
- **올바름**: `io.bluetape4k.leader.lettuce.LettuceLeaderElector`
- **틀림**: `io.bluetape4k.leader.redis.lettuce.LettuceLeaderElector`

`bluetape4k-leader-core` JAR:
- **올바름**: `io.bluetape4k.leader.LeaderElectionOptions` (core jar에 포함)
- **틀림**: `io.bluetape4k.leader.redis.LeaderElectionOptions`

**교훈**: 새 외부 라이브러리 사용 시 반드시 sources JAR를 직접 검사하여 패키지 경로를 확인한다. 문서가 최신이 아닐 수 있다.

### 2. KLogging + 로깅 확장 함수 임포트

`companion object : KLogging()`을 사용할 때 `log.info { }` 람다 형식은
`import io.bluetape4k.logging.*` wildcard import가 필요하다.  
`import io.bluetape4k.logging.KLogging`만으로는 부족하다.

```kotlin
// 필요한 임포트 조합
import io.bluetape4k.logging.*        // 람다 확장 함수 (log.info { }, log.warn(e) { })
// KLogging은 wildcard에 포함되므로 별도 import 불필요
```

### 3. java.time.Duration → kotlin.time.Duration 변환 필수

`@ConfigurationProperties`는 `java.time.Duration`을 바인딩한다.  
`LeaderElectionOptions`는 `kotlin.time.Duration`을 요구한다.  
변환 없이 직접 할당하면 컴파일 오류 또는 런타임 오류 발생.

```kotlin
// 필수 변환
LeaderElectionOptions(
    waitTime = props.waitTime.toKotlinDuration(),   // java→kotlin 변환
    leaseTime = props.leaseTime.toKotlinDuration(),
)
```

### 4. @DynamicPropertySource vs @TestPropertySource

`@SpringBootTest`에서 Testcontainers Redis URL을 주입할 때:
- **작동함**: `@DynamicPropertySource` — 컨테이너 시작 후 포트를 동적으로 주입
- **작동 안함**: `@TestPropertySource(properties = ["leader.redis.url=redis://localhost:${redis.port}"])` — 컨테이너 시작 전에 평가되어 포트 불확정

```kotlin
companion object : KLogging() {
    val redis = RedisServer.Launcher.redis

    @JvmStatic
    @DynamicPropertySource
    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("leader.redis.url") { redis.url }
    }
}
```

### 5. smoke 태그 제외 — Gradle 설정 필수

`junit-platform.properties`의 `junit.jupiter.execution.exclude.tags=smoke`만으로는
Gradle `:test` 태스크에서 제외되지 않는다.  
`build.gradle.kts`에도 별도 설정이 필요하다:

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}
```

### 6. 동시성 테스트 — Thread.sleep으로 락 유지

`ConcurrentLeaderElectionTest`에서 winner가 락을 획득한 뒤 즉시 해제하면,
`waitTime`이 만료되기 전에 다른 worker가 락을 재획득할 수 있다.

```kotlin
elector.runIfLeader(lockName) {
    executions.incrementAndGet()
    Thread.sleep(500)  // waitTime(100ms)보다 길게 유지해야 단일 실행 보장
}
```

**패턴**: waitTime < sleep < leaseTime 관계 유지 필수.

### 7. MockK generic 타입 추론 실패

```kotlin
// 컴파일 오류 — generic T 추론 불가
every { elector.runIfLeader(lockName, any()) } returns Unit
```

`LeaderElector.runIfLeader`가 제네릭 함수(`fun <T> runIfLeader(...)`)이면
MockK가 타입 파라미터를 추론하지 못한다.  
**해결**: 실제 `LettuceLeaderElector` 인스턴스를 사용하는 통합 테스트로 전환.

### 8. 스프링 Boot 의존성 카탈로그 패턴

`libs.spring.boot.starter` 키가 카탈로그에 없는 경우:
```kotlin
// 형제 모듈 패턴 사용
implementation(libs.spring.boot.autoconfigure.lib)
implementation(libs.spring.boot.starter.actuator)
annotationProcessor(libs.spring.boot.autoconfigure.processor)
annotationProcessor(libs.spring.boot.configuration.processor)
```

---

## 리뷰 반영 사항 (Step 6-R)

| 우선순위 | 발견 | 수정 |
|----------|------|------|
| P1 | `assert(service != null)` — 항상 true인 dead assertion | `service.shouldNotBeNull()` |
| P2 | `LeaderElectionSingleRunnerTest`에 사용하지 않는 `faker` companion | companion object 전체 제거 |
| P2 | `jobs.shouldHaveSize(2)` — job 추가 시 깨지는 brittle assertion | `(jobs.size >= 2).shouldBeTrue()` |
| P3 | 6개 파일에 `import io.bluetape4k.logging.KLogging` + wildcard 중복 | 중복 명시적 import 제거 |

---

## 테스트 결과

```
:leader-leader-election:test
13 tests, 0 failures, 0 ignored
smoke 태그(@Tag("smoke")) 제외: LeaseExpiryTest, RedisFailureTest
```

---

## 향후 참고 사항

- `bluetape4k-leader` API: `runIfLeader(lockName) { action }` → null이면 스킵, non-null이면 실행
- `finally { lock.unlock() }` — 락은 action 완료 즉시 해제됨 (leaseTime 무관)
- 작업 격리: 각 job에 고유 `lockName` 부여 → 작업 간 독립 실행 보장
- 새 job 추가: `LeaderGuardedJob` 구현 + `@Component` 선언으로 자동 등록
