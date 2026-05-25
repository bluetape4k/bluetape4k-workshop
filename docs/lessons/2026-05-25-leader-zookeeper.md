# leader-zookeeper 워크숍 — 교훈 및 결정 기록

**날짜**: 2026-05-25  
**이슈**: #10 bluetape4k-leader 예제 제작 (PR-B: leader-zookeeper 신규 모듈)  
**브랜치**: `feat/leader-zookeeper`  
**모듈**: `:leader-leader-zookeeper`

---

## 1. R16 — ZooKeeper는 TTL이 없다

### 근본 원인

Redis 기반 `LettuceLeaderElector`는 `SET NX EX`로 TTL을 설정하여 `leaseTime`/`autoExtend`가 의미있다.
ZooKeeper 기반 elector는 **세션 기반 에페메랄 znode**를 사용하기 때문에 TTL이 존재하지 않는다.

### 결정

- `LeaderZookeeperProperties`에 `leaseTime`/`autoExtend` 필드를 **의도적으로 포함하지 않음**
- `LeaderElectionOptions.autoExtend = true` 설정 시 라이브러리 내부에서 WARN 로그 출력 후 무시됨
- T7 테스트(`R16AutoExtendIgnoredTest`)로 이 계약을 명시적으로 검증

### 향후 적용

ZooKeeper 기반 elector 사용 시 Redis 패턴의 `leaseTime`/`autoExtend` 설정을 그대로 복사하지 말 것.
세션 만료 기반 페일오버를 위해 `sessionTimeoutMs` 를 적절히 튜닝할 것.

---

## 2. SuspendedJobTester — rounds × blockCount = totalUnits

### 근본 원인

`SuspendedJobTester.workers(N).rounds(R)` 의 실제 총 실행 횟수는
`totalUnits = rounds × blockCount` 이다 (workers 수가 아님!).

- `workers(8).rounds(1).add { ... }` → totalUnits = **1** (8이 아님!)
- 8개 worker가 모두 시작하지만, 첫 번째 worker만 workUnit 0을 가져가고 나머지는 즉시 break

### 증상

- T3 `SuspendSingleLeaderTest` 2번째 테스트 (`peakConcurrent never exceeds 1`):
  - `executed.get() = 1 shouldBeGreaterThan 3` → **AssertionFailedError**
- T5 `SuspendGroupLeaderTest`:
  - 1개 worker만 진입 → `enteredLatch(2)` 미달 → `releaseLatch.await(3s)` 타임아웃 → **IllegalStateException**

### 수정

| 테스트 | 변경 전 | 변경 후 | 이유 |
|--------|--------|--------|------|
| T3 (SuspendSingleLeaderTest, 2nd) | `.rounds(1)` | `.rounds(8)` | totalUnits=8, 8개 worker 각 1번 실행 |
| T5 (SuspendGroupLeaderTest) | `.rounds(1)` | `.rounds(MAX_LEADERS)` = `.rounds(2)` | totalUnits=2, 2개 worker 동시 진입 |

### 규칙

`SuspendedJobTester`의 동작 공식:
```
totalUnits = rounds × blockCount
```
동시에 실행할 작업 수 = workers 수와 rounds를 모두 고려하여 `totalUnits >= 원하는_동시_실행_수` 가 되도록 설정.

---

## 3. @Test 메서드의 Unit 반환 타입

### 근본 원인

`fun test() = runBlocking { ... expr ... }` 형식에서 마지막 `expr` 이 `Unit` 이 아닌 값을 반환하면
(예: `shouldBeEqualTo` 가 receiver를 반환하는 경우) 테스트 메서드 자체가 비-Unit 타입이 된다.

JUnit 5는 `@Test` 메서드가 값을 반환하는 경우 **WARNING 로그와 함께 테스트를 실행하지 않는다**.

```
[WARNING] @Test method '...' must not return a value. It will not be executed.
```

이 경고는 BUILD SUCCESSFUL로 마스킹되어 테스트가 통과한 것처럼 보일 수 있다.

### 수정

표현식 바디(expression body)에 `: Unit` 반환 타입 어노테이션 추가:
```kotlin
// Before (silently excluded!)
@Test
fun `test`() = runBlocking { ... shouldBeEqualTo expected }

// After (runs correctly)
@Test
fun `test`(): Unit = runBlocking { ... shouldBeEqualTo expected }
```

### 규칙

`fun test() = runBlocking { ... }` 패턴에서 마지막 표현식이 assertion (특히 receiver를 반환하는
`shouldBeEqualTo`, `shouldBeGreaterThan` 등)인 경우 반드시 `: Unit` 추가.

---

## 4. T8 세션 소실 테스트 — 컨테이너 재시작 대신 클라이언트 세션 닫기

### 근본 원인

`ZooKeeperServer(reuse = true)` (기본값)로 생성된 컨테이너를 `stop()`/`start()` 하면,
**포트가 재매핑**되어 기존 Curator 클라이언트가 구 포트를 계속 사용한다.

### 결정

컨테이너를 재시작하는 대신 **클라이언트 측 세션을 직접 닫는** 방식 채택:
```kotlin
clientA.zookeeperClient.zooKeeper.close()
// Curator가 세션 소실을 감지하고 새 세션으로 재연결
```

이 방식은 ZooKeeper 클러스터에서 프로세스 크래시를 시뮬레이션하는 결정론적이고 안정적인 방법이다.

### 향후 적용

ZooKeeper/Curator 기반 테스트에서 세션 소실을 시뮬레이션할 때는
컨테이너 재시작 대신 `zookeeperClient.zooKeeper.close()` 를 사용할 것.

---

## 5. ConnectionStateListener는 start() 이전에 등록

### 근본 원인

`CuratorFramework.start()` 이후에 `ConnectionStateListener`를 등록하면
초기 `CONNECTED` 상태 변경 이벤트를 놓칠 수 있다.

### 결정

리스너를 `start()` 이전에 등록:
```kotlin
client.connectionStateListenable.addListener(listener)
client.start()
check(client.blockUntilConnected(timeoutSec, TimeUnit.SECONDS)) { ... }
```

---

## 6. T7 ListAppender — additivity="true" 필요

### 근본 원인

T7 테스트는 `io.bluetape4k.leader.zookeeper` 로거에 `ListAppender`를 추가한다.
`logback-test.xml`에서 이 로거의 `additivity="false"` 로 설정된 경우,
`ListAppender`를 다른 로거 계층(예: 루트 로거)에 추가해도 이벤트가 전달되지 않는다.

### 수정

`logback-test.xml`에서 `io.bluetape4k.leader.zookeeper` 로거의 `additivity="true"` 설정을 유지:
```xml
<logger name="io.bluetape4k.leader.zookeeper" level="DEBUG" additivity="true"/>
```

그리고 `@AfterEach` 에서 `detachAppender(appender)` 호출로 누수 방지:
```kotlin
@AfterEach fun teardownLogCapture() {
    (LoggerFactory.getLogger("io.bluetape4k.leader.zookeeper") as Logger).detachAppender(appender)
    appender.stop()
}
```

---

## 7. ZooKeeperLeaderGroupElector 생성자 인수 순서

### 근본 원인

컴파일러는 `(client, basePath, options)` 바이트코드 순서로 생성자를 생성하지만,
라이브러리의 실제 파라미터명은 `options` (not `groupOptions`)이다.

### 결정

**명명된 인수(named arguments)를 항상 사용**:
```kotlin
// ✅ 명명된 인수 (올바름)
ZooKeeperLeaderGroupElector(
    client = curator,
    options = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 500.milliseconds),
    basePath = "/test/group"
)

// ❌ 위치 인수 (순서 오류 위험)
ZooKeeperLeaderGroupElector(curator, "/test/group", options)  // 컴파일 오류 가능
```

---

## 8. Step 6-R 코드 리뷰 발견 사항

### 8-1. `kotlin.test.assertFailsWith` 는 CLAUDE.md에서 금지

- **문제**: `LeaderZookeeperPropertiesValidationTest`가 `import kotlin.test.assertFailsWith` 를 사용
- **수정**: `import io.bluetape4k.assertions.assertFailsWith` 로 대체
- **규칙**: CLAUDE.md 명시 금지 목록: "Do not use JUnit assertThrows, invoking { } shouldThrow, or kotlin.test.assertFailsWith in new tests"

### 8-2. `runTest {}` 표현식 바디도 `: Unit` 추가 권장

`runTest` 자체가 `Unit`을 반환하므로 기술적으로는 불필요하지만,
`runBlocking + assertion` 패턴과의 일관성을 위해 `: Unit` 명시 권장.

규칙:
- `fun test() = runBlocking { ... }` → **반드시** `: Unit`
- `fun test() = runTest { ... }` → `runTest`가 `Unit` 반환이므로 선택적, 일관성을 위해 추가 권장

### 8-3. 서비스 레이어 행동 테스트 부재

컨텍스트 테스트(T0)는 Spring 빈 와이어링만 확인. `runLeaderWork()`, `inspectState()` 등 실제
서비스 메서드의 반환값/부수효과 테스트가 없었음.

- **수정**: `LeaderServiceBehaviorTest` 추가 (4개 테스트: BlockingLeaderService 2개, GroupLeaderService 2개)
- **패턴**: AbstractLeaderZookeeperTest의 공유 `curator` 를 사용하여 Spring 컨텍스트 없이 서비스 직접 생성

### 8-4. 숫자 타임아웃 필드 검증 누락

`ZooKeeperConfig` 의 `sessionTimeoutMs`, `connectionTimeoutMs`, `blockUntilConnectedSeconds` 가
음수 또는 0이어도 통과됨.

- **수정**: `ZooKeeperConfig.init {}` 에 `requirePositiveNumber` 추가

---

## 9. 최종 검증 결과

- **빌드**: `./gradlew :leader-leader-zookeeper:compileKotlin` → BUILD SUCCESSFUL
- **테스트**: `./gradlew :leader-leader-zookeeper:test --rerun-tasks` → **22 tests, 0 failures**

| 테스트 | 결과 |
|--------|------|
| T0 LeaderZookeeperContextTest | ✅ 1 pass |
| T1 BlockingSingleLeaderTest | ✅ 3 pass |
| T2 ConcurrentBlockingLeaderTest | ✅ 1 pass |
| T3 SuspendSingleLeaderTest | ✅ 2 pass |
| T4 GroupLeaderTest | ✅ 1 pass |
| T5 SuspendGroupLeaderTest | ✅ 1 pass |
| T6 ExtensionFunctionTest | ✅ 4 pass |
| T7 R16AutoExtendIgnoredTest | ✅ 1 pass |
| T8 SessionLossFailoverTest | ✅ 1 pass |
| T9 LeaderZookeeperPropertiesValidationTest | ✅ 3 pass |
| LeaderServiceBehaviorTest | ✅ 4 pass (신규) |
| **합계** | **22 pass / 0 fail** |
