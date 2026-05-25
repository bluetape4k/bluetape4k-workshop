# Leader ZooKeeper 워크숍

ZooKeeper 기반의 **분산 리더 선출(Distributed Leader Election)** 워크숍 예제입니다.
`bluetape4k-leader-zookeeper` 라이브러리(Apache Curator 5.9)를 사용하여,
멀티 인스턴스(multi-pod) 환경에서 스케줄링된 작업을 안전하게 실행하는 방법을 보여줍니다.

> English README: [README.md](README.md)

## 개요

멀티 인스턴스(멀티 Pod) 배포 환경에서 스케줄된 백그라운드 작업은 **정확히 하나의 인스턴스**
(Single-Leader) 또는 **최대 N개 인스턴스**(Group-Leader)가 동시에 실행되어야 합니다.
이 모듈은 `bluetape4k-leader-zookeeper`(Apache Curator 5.9)를 사용하여
ZooKeeper 에페메랄 노드 기반으로 정확한 동시 실행을 보장하는 방법을 시연합니다.

## 아키텍처

여러 앱 인스턴스가 [Apache Curator](https://curator.apache.org/)의
`InterProcessMutex`(단일 리더) / `InterProcessSemaphoreV2`(그룹 리더)로 ZooKeeper 잠금을 경쟁합니다.
**선출된 리더**만 각 스케줄 작업을 실행합니다.

```
인스턴스-1  ──┐
인스턴스-2  ──┼──►  CuratorFramework  ──►  ZooKeeper 앙상블
인스턴스-3  ──┘           (에페메랄 znode)
                │
                └──  InterProcessMutex / InterProcessSemaphoreV2
                       (단일 리더 / 그룹 리더)
```

## ⚠️ R16 — ZooKeeper는 TTL이 없습니다 (Redis와의 핵심 차이점)

ZooKeeper는 **세션 기반 에페메랄 znode**를 사용하여 리더 선출을 구현합니다:

- 리더십은 ZooKeeper **세션이 만료**되거나 명시적으로 해제될 때까지 유지됩니다.
- **리스 TTL이 없습니다.** `LeaderElectionOptions.autoExtend = true` 를 설정하면
  `WARN` 로그가 출력되고 자동으로 무시됩니다.
- 프로세스 크래시나 네트워크 파티션 발생 시, ZooKeeper 세션이 타임아웃되면
  (`sessionTimeoutMs`, 기본 60초) 에페메랄 znode가 자동 삭제되어 재선출이 시작됩니다.

**실용적 가이드:**
- `leaseTime` / `autoExtend` 는 `LeaderZookeeperProperties` 에 의도적으로 없습니다.
- 허용 가능한 페일오버 지연 시간을 고려해 `sessionTimeoutMs` 를 조정하세요.
- `waitTime` 으로 잠금 획득 대기 시간을 제한하세요.

## 사용된 Bluetape4k 기능

| 기능 | Artifact | 용도 |
|---------|----------|-------|
| `bluetape4k-leader-zookeeper` | `bluetape4k.leader.zookeeper` | `ZooKeeperLeaderElector`, `ZooKeeperSuspendLeaderElector`, `ZooKeeperLeaderGroupElector`, `ZooKeeperSuspendLeaderGroupElector` |
| `bluetape4k-logging` | `bluetape4k.logging` | `KLogging` / `KLoggingChannel` + 람다 로깅 |
| `bluetape4k-junit5` | `bluetape4k.junit5` | `MultithreadingTester`, `SuspendedJobTester` 동시성 테스트 |
| `bluetape4k-testcontainers` | `bluetape4k.testcontainers` | `ZooKeeperServer.Launcher.zookeeper` 싱글턴 패턴 |
| `bluetape4k-assertions` | `bluetape4k.assertions` | `shouldBeEqualTo`, `shouldBeTrue`, `assertFailsWith` |

## 핵심 패턴

### 1. 블로킹 단일 리더 선출

```kotlin
@Scheduled(fixedDelayString = "\${leader.zookeeper.job-fixed-delay}")
fun scheduledJob() {
    val result = leaderElector.runIfLeader("my-job") {
        // 선출된 리더 인스턴스에서만 실행
        doWork()
    }
    // result == null  →  이 인스턴스는 리더가 아님 (건너뜀)
    // result != null  →  이 인스턴스가 선출되어 실행됨
}
```

### 2. 서스펜드 단일 리더 선출 (코루틴)

```kotlin
@Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-job-fixed-delay}")
fun scheduledSuspendJob() {
    runBlocking {
        suspendLeaderElector.runIfLeader("my-suspend-job") {
            delay(100)   // ZK 획득은 Dispatchers.IO에서 실행
            doWork()
        }
    }
}
```

### 3. 그룹 리더 선출 (최대 N개 동시 홀더)

```kotlin
@Scheduled(fixedDelayString = "\${leader.zookeeper.group-job-fixed-delay}")
fun scheduledGroupJob() {
    // 최대 `groupMaxLeaders` 개 인스턴스가 동시에 실행
    groupElector.runIfLeader("group-job") {
        doGroupWork()
    }
}
```

### 4. Bean 설정

```kotlin
@Bean
fun zookeeperLeaderElector(curator: CuratorFramework, props: LeaderZookeeperProperties) =
    ZooKeeperLeaderElector(
        client = curator,
        basePath = "${props.basePath}/single",
        options = LeaderElectionOptions(waitTime = props.waitTime.toKotlinDuration()),
    )

@Bean
fun zookeeperGroupElector(curator: CuratorFramework, props: LeaderZookeeperProperties) =
    ZooKeeperLeaderGroupElector(
        client = curator,
        basePath = "${props.basePath}/group",
        options = LeaderGroupElectionOptions(
            maxLeaders = props.groupMaxLeaders,
            waitTime = props.waitTime.toKotlinDuration(),
        ),
    )
```

### 5. CuratorFramework 설정

```kotlin
@Bean
fun curatorFramework(props: LeaderZookeeperProperties): CuratorFramework {
    val client = CuratorFrameworkFactory.newClient(
        props.zookeeper.connectString,
        props.zookeeper.sessionTimeoutMs,
        props.zookeeper.connectionTimeoutMs,
        ExponentialBackoffRetry(1000, 3),
    )
    // start() 이전에 ConnectionStateListener 등록
    client.connectionStateListenable.addListener(ConnectionStateListener { _, newState ->
        log.info { "ZooKeeper 연결 상태 변경: $newState" }
    })
    client.start()
    check(client.blockUntilConnected(props.zookeeper.blockUntilConnectedSeconds, TimeUnit.SECONDS)) {
        "ZooKeeper에 연결할 수 없습니다. (${props.zookeeper.blockUntilConnectedSeconds}초 초과)"
    }
    return client
}
```

## 설정

```yaml
leader:
  zookeeper:
    zookeeper:
      connect-string: localhost:2181          # ZooKeeper 연결 문자열
      session-timeout-ms: 60000              # 크래시 시 페일오버 대기 시간 (ms)
      connection-timeout-ms: 15000           # 초기 연결 타임아웃 (ms)
      block-until-connected-seconds: 10      # 시작 시 연결 대기 최대 시간 (초)
    base-path: /workshop/leader-zookeeper    # 모든 선출 znode의 기본 경로
    wait-time: 2s                            # 잠금 획득 최대 대기 시간
    group-max-leaders: 2                     # 그룹 리더 동시 최대 홀더 수
    job-fixed-delay: PT10S                   # 블로킹 단일 리더 작업 주기
    suspend-job-fixed-delay: PT12S           # 서스펜드 단일 리더 작업 주기
    group-job-fixed-delay: PT15S             # 블로킹 그룹 리더 작업 주기
    suspend-group-job-fixed-delay: PT18S     # 서스펜드 그룹 리더 작업 주기
```

> **참고:** `leaseTime` 과 `autoExtend` 는 의도적으로 없습니다 — [R16 참고](#️-r16--zookeeper는-ttl이-없습니다-redis와의-핵심-차이점).

## 실행

### 애플리케이션 시작

```bash
# ZooKeeper가 localhost:2181에서 실행 중이어야 합니다
./gradlew :leader-leader-zookeeper:bootRun
```

### 테스트 실행

```bash
# 모든 테스트 실행 (기본적으로 smoke 태그 제외)
./gradlew :leader-leader-zookeeper:test

# 명시적 태그 필터 설정
./gradlew :leader-leader-zookeeper:test -Djunit.jupiter.execution.exclude.tags=
```

## 테스트 커버리지

| 테스트 | 클래스 | 설명 |
|------|-------|-------------|
| T0 | `LeaderZookeeperContextTest` | Spring Boot 컨텍스트 로드; 4개 elector 빈 검증 |
| T1 | `BlockingSingleLeaderTest` | 블로킹 단일 리더: `runIfLeader`, `runAsyncIfLeader`, 예외 격리 |
| T2 | `ConcurrentBlockingLeaderTest` | 8개 스레드 경쟁; `waitTime=500ms` 내 3회 이상 실행 검증 |
| T3 | `SuspendSingleLeaderTest` | 서스펜드 단일 리더: 결과 반환 + 8개 코루틴 직렬 실행 검증 |
| T4 | `GroupLeaderTest` | `maxLeaders=2`로 정확히 2개 블로킹 홀더 동시 허용 (`MultithreadingTester`) |
| T5 | `SuspendGroupLeaderTest` | `maxLeaders=2`로 정확히 2개 코루틴 홀더 동시 허용 (`SuspendedJobTester`) |
| T6 | `ExtensionFunctionTest` | 확장 API: `runBlockingIfLeader`, `runAsyncIfLeader`, `runSuspendIfLeader`, `runGroupIfLeader` |
| T7 | `R16AutoExtendIgnoredTest` | `autoExtend=true` 시 WARN 로그 출력 후 무시 (R16 계약 검증) |
| T8 | `SessionLossFailoverTest` | `zookeeperClient.zooKeeper.close()`로 세션 소실 시뮬레이션; 재연결 후 재선출 성공 |
| T9 | `LeaderZookeeperPropertiesValidationTest` | 빈 `basePath`, `groupMaxLeaders=0`, 빈 `connectString` 검증 실패 테스트 |

## 프로덕션 고려사항

| 항목 | 가이드 |
|---------|----------|
| **ACL** | 프로덕션에서 `CuratorFrameworkFactory.builder().aclProvider(...)` 설정 필요 |
| **TLS / SASL** | `ZooKeeperTls.setZKTLSConfig(...)` 및 `javax.net.ssl` 속성 설정 필요 |
| **앙상블** | 고가용성을 위해 `host1:2181,host2:2181,host3:2181` 형태로 설정 |
| **세션 타임아웃** | 불필요한 재선출 방지를 위해 `sessionTimeoutMs`를 작업 주기의 3~5배로 설정 |
| **스레드 안전성** | `CuratorFramework` 및 모든 elector 인스턴스는 스레드 세이프하며 공유 가능 |
| **Spring Boot 버전** | Spring Boot 4.x 및 Java 21 이상 호환 |
