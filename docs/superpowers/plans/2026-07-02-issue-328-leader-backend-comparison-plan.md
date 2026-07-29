# 리더 백엔드 비교 연구실 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 기존 실행 가능한 백엔드 모듈을 교체하지 않고 Redis, ZooKeeper 및 Kubernetes 임대 리더 선택 백엔드를 비교하는 결정론적 워크숍 모듈인 `leader/backend-comparison-lab`을 빌드합니다.

**아키텍처:** 이 모듈은 변경할 수 없는 백엔드 프로필과 소규모의 결정적 장애 조치 랩 서비스를 사용합니다. 프로덕션 코드는 학습자가 볼 수 있는 백엔드 동작을 설명합니다. 테스트는 Redis, ZooKeeper 또는 Kubernetes을 시작하지 않고 카탈로그 및 시나리오 보고서를 증명합니다. README 다이어그램은 아키텍처와 순서 설명을 전달하는 반면 기존 백엔드 모듈은 실제 통합 실행 경로로 유지됩니다.

**기술 스택:** Kotlin 2.4, Java 21, Spring Boot 4, bluetape4k leader/core/logging/assertions/junit5, Micrometer 기존 K8s 모듈의 용어, 생성된 SVG+PNG README 다이어그램, GitHub 액션 연기 검증.

---

## 파일 구조

- `leader/backend-comparison-lab/build.gradle.kts` 생성
  - Spring Boot 4 애플리케이션 모듈.
  - 버전 없는 종속성만 해당됩니다.
  - 생산 종속성은 결정론적 비교 연구실로 제한됩니다.
    백엔드별 Redis/ZooKeeper/Kubernetes 모듈은 연결된 방식으로 유지됩니다.
    사용되지 않는 전이적 런타임 종속성이 아닌 대상입니다.
  - 기본 `test` 작업은 결정적이며 향후 백엔드가 많은 태그가 추가되면 제외됩니다.
- `leader/backend-comparison-lab/README.md` 생성
  - 영어 학습자 가이드, 백엔드 매트릭스, 시나리오 흐름, test/run 명령, 실제 백엔드 링크.
- `leader/backend-comparison-lab/README.ko.md` 생성
  - 한국어 소스에 해당하는 학습자 가이드입니다.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/BackendComparisonLabApp.kt` 생성
  - 최소 Spring Boot 진입점.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/BackendProfile.kt` 생성
  - 직렬화 가능 불변 백엔드 프로필 및 지원 열거형.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/BackendCapability.kt` 생성
  - 직렬화 가능 불변 백엔드 기능 값 유형입니다.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/LeaderScenario.kt` 생성
  - 직렬화 가능한 시나리오 request/value 유형.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/LeaderScenarioReport.kt` 생성
  - 직렬화 가능한 report/event 값 유형.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderBackendCatalog.kt` 생성
  - Redis, ZooKeeper 및 Kubernetes 임대를 위한 소스 기반 카탈로그입니다.
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderFailoverLab.kt` 생성
  - 꾸준한 리더, 경합 건너뛰기, 작업 실패 및 백엔드 손실 핸드오프 시나리오를 위한 결정적 보고서 생성기입니다.
- `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/` 아래에 테스트 파일을 만듭니다.
- `src/test/resources/junit-platform.properties` 및 `logback-test.xml`를 생성합니다.
- 다이어그램 만들기:
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.png`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.png`
- 루트 `README.md` 및 `README.ko.md`을 수정합니다.
- `scripts/smoke-validate.sh`을 수정하세요.
- `.github/workflows/Examples.yml`을 수정하세요.
- 워크플로우 스캔 후 연기 경로 적용이 필요한 경우에만 `.github/workflows/nightly.yml`을 수정하십시오.
- `docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`를 생성합니다.
- `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md`를 생성합니다.

## 작업 1: 뼈대 빌드 및 카탈로그 테스트 실패

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

**파일:**
- 생성: `leader/backend-comparison-lab/build.gradle.kts`
- 생성: `leader/backend-comparison-lab/src/test/resources/junit-platform.properties`
- 생성: `leader/backend-comparison-lab/src/test/resources/logback-test.xml`
- 생성: `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderBackendCatalogTest.kt`

- [ ] **1단계: 모듈 빌드 뼈대 추가**

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.backendcomparison.BackendComparisonLabAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("backend-heavy")
    }
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.logging)

    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
```

- [ ] **2단계: 테스트 리소스 추가**

`junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=false
junit.jupiter.testinstance.lifecycle.default=per_class
```

`logback-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **3단계: 프로덕션 코드 이전에 실패한 카탈로그 테스트 작성**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderBackendCatalogTest {

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `catalog contains Redis ZooKeeper and Kubernetes Lease profiles`() {
        val profiles = catalog.all()

        profiles.map { it.id } shouldBeEqualTo listOf("redis-lettuce", "zookeeper-curator", "kubernetes-lease")
        profiles.map { it.status } shouldContain BackendStatus.STABLE
        profiles.shouldNotBeEmpty()
    }

    @Test
    fun `Redis profile documents TTL based failover and event observation`() {
        val redis = catalog.findById("redis-lettuce")

        redis.failoverTrigger shouldBeEqualTo "Lease TTL expiry or explicit release"
        redis.metricsAndEvents shouldContain "LeaderElectionEvent Flow"
        redis.practiceModulePath shouldBeEqualTo "leader/leader-election"
    }

    @Test
    fun `ZooKeeper profile documents session based failover and group leadership`() {
        val zookeeper = catalog.findById("zookeeper-curator")

        zookeeper.failoverTrigger shouldBeEqualTo "ZooKeeper session loss"
        zookeeper.capabilities.map { it.label } shouldContain "Group leadership"
        zookeeper.practiceModulePath shouldBeEqualTo "leader/leader-zookeeper"
    }

    @Test
    fun `Kubernetes profile documents opt-in Lease and Micrometer path`() {
        val kubernetes = catalog.findById("kubernetes-lease")

        kubernetes.status shouldBeEqualTo BackendStatus.PREVIEW_OPT_IN
        kubernetes.metricsAndEvents shouldContain "leader-micrometer meters"
        kubernetes.practiceModulePath shouldBeEqualTo "leader/k8s-lease-micrometer"
    }
}
```

- [ ] **4단계: RED 확인 실행**

달리다:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderBackendCatalogTest' --no-build-cache --rerun-tasks
```

예상: `LeaderBackendCatalog`, `BackendStatus` 및 프로필 유형이 없기 때문에 FAIL입니다.

## 작업 2: 백엔드 카탈로그 구현

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `BackendComparisonLabApp.kt`
- 생성: `domain/BackendCapability.kt`
- 생성: `domain/BackendProfile.kt`
- 생성: `service/LeaderBackendCatalog.kt`

- [ ] **1단계: 앱 진입점 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BackendComparisonLabApp

fun main(args: Array<String>) {
    runApplication<BackendComparisonLabApp>(*args)
}
```

- [ ] **2단계: 백엔드 기능 모델 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

data class BackendCapability(
    val label: String,
    val detail: String,
) : Serializable {
    init {
        label.requireNotBlank("label")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **3단계: 백엔드 프로필 모델 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

enum class BackendStatus {
    STABLE,
    PREVIEW_OPT_IN,
}

data class BackendProfile(
    val id: String,
    val displayName: String,
    val status: BackendStatus,
    val primitive: String,
    val failoverTrigger: String,
    val tuningKnob: String,
    val metricsAndEvents: List<String>,
    val bestFor: String,
    val avoidWhen: String,
    val practiceModulePath: String,
    val capabilities: List<BackendCapability>,
) : Serializable {
    init {
        id.requireNotBlank("id")
        displayName.requireNotBlank("displayName")
        primitive.requireNotBlank("primitive")
        failoverTrigger.requireNotBlank("failoverTrigger")
        tuningKnob.requireNotBlank("tuningKnob")
        bestFor.requireNotBlank("bestFor")
        avoidWhen.requireNotBlank("avoidWhen")
        practiceModulePath.requireNotBlank("practiceModulePath")
        metricsAndEvents.requireNotEmpty("metricsAndEvents")
        capabilities.requireNotEmpty("capabilities")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **4단계: 소스 기반 카탈로그 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendCapability
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendProfile
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.springframework.stereotype.Service

@Service
class LeaderBackendCatalog {

    private val profiles: List<BackendProfile> = listOf(
        BackendProfile(
            id = "redis-lettuce",
            displayName = "Redis + Lettuce",
            status = BackendStatus.STABLE,
            primitive = "Redis key with lease TTL",
            failoverTrigger = "Lease TTL expiry or explicit release",
            tuningKnob = "waitTime, leaseTime, autoExtend",
            metricsAndEvents = listOf("LeaderElectionEvent Flow", "listener callbacks", "skip/elected/revoked events"),
            bestFor = "Services that already operate Redis and need fast, simple scheduled-job leadership.",
            avoidWhen = "Redis outage should not affect scheduler ownership or a session-bound lock is required.",
            practiceModulePath = "leader/leader-election",
            capabilities = listOf(
                BackendCapability("Blocking and coroutine APIs", "LettuceLeaderElector plus LettuceSuspendLeaderElector."),
                BackendCapability("TTL recovery", "Leadership can recover after lease expiry when an owner disappears."),
                BackendCapability("Event observation", "ListeningLeaderElector exposes callbacks and Flow events."),
            ),
        ),
        BackendProfile(
            id = "zookeeper-curator",
            displayName = "ZooKeeper + Curator",
            status = BackendStatus.STABLE,
            primitive = "Ephemeral znode / Curator mutex",
            failoverTrigger = "ZooKeeper session loss",
            tuningKnob = "sessionTimeoutMs, connectionTimeoutMs, groupMaxLeaders",
            metricsAndEvents = listOf("single-leader result", "group-leader slot result", "Curator connection state"),
            bestFor = "Services already depending on ZooKeeper or needing session-bound ownership.",
            avoidWhen = "A Redis-style lock TTL or auto-extension behavior is expected.",
            practiceModulePath = "leader/leader-zookeeper",
            capabilities = listOf(
                BackendCapability("Single leadership", "One candidate owns the lock path and executes."),
                BackendCapability("Group leadership", "Up to groupMaxLeaders candidates may enter."),
                BackendCapability("Session recovery", "Ownership follows ZooKeeper session lifecycle, not a TTL field."),
            ),
        ),
        BackendProfile(
            id = "kubernetes-lease",
            displayName = "Kubernetes Lease",
            status = BackendStatus.PREVIEW_OPT_IN,
            primitive = "coordination.k8s.io/v1 Lease object",
            failoverTrigger = "Lease expiry and resource-version update",
            tuningKnob = "namespace, identity, leaseTime, retryDelay, autoExtend",
            metricsAndEvents = listOf("leader-micrometer meters", "workshop.k8s.lease.* meters", "Prometheus scrape"),
            bestFor = "Kubernetes-native workloads that can grant Lease RBAC and expose Micrometer metrics.",
            avoidWhen = "Local tests must exercise a real backend without a cluster or service-account setup.",
            practiceModulePath = "leader/k8s-lease-micrometer",
            capabilities = listOf(
                BackendCapability("Opt-in real backend", "Default workshop path stays disabled without Kubernetes credentials."),
                BackendCapability("Micrometer visibility", "Application meters and leader-micrometer decorator meters are documented."),
                BackendCapability("Kubernetes ownership", "Lease records namespace and identity for operational inspection."),
            ),
        ),
    )

    fun all(): List<BackendProfile> = profiles

    fun findById(id: String): BackendProfile {
        id.requireNotBlank("id")
        return profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown leader backend id: $id")
    }
}
```

- [ ] **5단계: GREEN 확인 실행**

달리다:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderBackendCatalogTest' --no-build-cache --rerun-tasks
```

예상: PASS.

## 작업 3: 실패한 장애 조치 시나리오 테스트 추가

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

**파일:**
- 생성: `domain/LeaderScenario.kt`
- 생성: `domain/LeaderScenarioReport.kt`
- 생성: `service/LeaderFailoverLab.kt`
- 생성: `src/test/kotlin/.../LeaderFailoverLabTest.kt`

- [ ] **1단계: 실패하는 시나리오 테스트 작성**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderFailoverLabTest {

    private val lab = LeaderFailoverLab(LeaderBackendCatalog())

    @Test
    fun `steady leader scenario executes one node and skips follower`() {
        val report = lab.run(LeaderScenario.steadyLeader("redis-lettuce"))

        report.backendId shouldBeEqualTo "redis-lettuce"
        report.events.map { it.outcome } shouldBeEqualTo listOf("executed", "skipped")
        report.summary shouldBeEqualTo "node-a executed report-sync; node-b skipped because Redis lock is held."
    }

    @Test
    fun `action failure scenario records release then next eligible run`() {
        val report = lab.run(LeaderScenario.actionFailure("zookeeper-curator"))

        report.events.map { it.outcome } shouldContain "failed"
        report.events.map { it.outcome } shouldContain "executed-after-recovery"
        report.handoffTrigger shouldBeEqualTo "ZooKeeper session loss"
    }

    @Test
    fun `backend loss handoff uses backend specific trigger`() {
        val report = lab.run(LeaderScenario.backendLossHandoff("kubernetes-lease"))

        report.handoffTrigger shouldBeEqualTo "Lease expiry and resource-version update"
        report.metricsToInspect shouldContain "leader-micrometer meters"
        report.summary shouldBeEqualTo "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
    }
}
```

- [ ] **2단계: RED 확인 실행**

달리다:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderFailoverLabTest' --no-build-cache --rerun-tasks
```

예상: scenario/report/lab 유형이 존재하지 않기 때문에 FAIL입니다.

## 작업 4: 결정적 장애 조치 랩 구현

**복잡성:** 높음

**적용:** `$bluetape4k-code-patterns`

**파일:**
- 생성: `domain/LeaderScenario.kt`
- 생성: `domain/LeaderScenarioReport.kt`
- 생성: `service/LeaderFailoverLab.kt`

- [ ] **1단계: 시나리오 값 개체 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

enum class LeaderScenarioKind {
    STEADY_LEADER,
    CONTENTION_SKIP,
    ACTION_FAILURE,
    BACKEND_LOSS_HANDOFF,
}

data class LeaderScenario(
    val backendId: String,
    val kind: LeaderScenarioKind,
    val jobName: String = "report-sync",
) : Serializable {
    init {
        backendId.requireNotBlank("backendId")
        jobName.requireNotBlank("jobName")
    }

    companion object {
        fun steadyLeader(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.STEADY_LEADER)

        fun contentionSkip(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.CONTENTION_SKIP)

        fun actionFailure(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.ACTION_FAILURE)

        fun backendLossHandoff(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.BACKEND_LOSS_HANDOFF)

        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **2단계: 보고서 모델 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

data class LeaderScenarioEvent(
    val actor: String,
    val outcome: String,
    val detail: String,
) : Serializable {
    init {
        actor.requireNotBlank("actor")
        outcome.requireNotBlank("outcome")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class LeaderScenarioReport(
    val backendId: String,
    val scenario: LeaderScenarioKind,
    val handoffTrigger: String,
    val events: List<LeaderScenarioEvent>,
    val metricsToInspect: List<String>,
    val summary: String,
) : Serializable {
    init {
        backendId.requireNotBlank("backendId")
        handoffTrigger.requireNotBlank("handoffTrigger")
        events.requireNotEmpty("events")
        metricsToInspect.requireNotEmpty("metricsToInspect")
        summary.requireNotBlank("summary")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **3단계: 랩 서비스 구현**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioEvent
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioKind
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioReport
import org.springframework.stereotype.Service

@Service
class LeaderFailoverLab(
    private val catalog: LeaderBackendCatalog,
) {

    fun run(scenario: LeaderScenario): LeaderScenarioReport {
        val profile = catalog.findById(scenario.backendId)
        val metrics = profile.metricsAndEvents
        return when (scenario.kind) {
            LeaderScenarioKind.STEADY_LEADER ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "${scenario.jobName} ran inside runIfLeader."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = metrics,
                    summary = "node-a executed ${scenario.jobName}; node-b skipped because ${skipSummary(profile.id)}.",
                )

            LeaderScenarioKind.CONTENTION_SKIP ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "First contender owns the guarded tick."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                        LeaderScenarioEvent("node-c", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = metrics,
                    summary = "one contender executes; remaining contenders receive the skip signal.",
                )

            LeaderScenarioKind.ACTION_FAILURE ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "failed", "${scenario.jobName} failed inside the elected block."),
                        LeaderScenarioEvent("node-a", "released", releaseDetail(profile.id)),
                        LeaderScenarioEvent("node-b", "executed-after-recovery", "Next eligible guarded tick can run."),
                    ),
                    metricsToInspect = metrics,
                    summary = "node-a failure is visible; the next eligible run can recover through ${profile.failoverTrigger}.",
                )

            LeaderScenarioKind.BACKEND_LOSS_HANDOFF ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent(primaryActor(profile.id), "lost-leadership", profile.failoverTrigger),
                        LeaderScenarioEvent(secondaryActor(profile.id), "executed-after-handoff", "Next guarded tick observes available leadership."),
                    ),
                    metricsToInspect = metrics,
                    summary = handoffSummary(profile.id),
                )
        }
    }

    private fun skipDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held until release or TTL expiry."
            "zookeeper-curator" -> "ZooKeeper mutex path is owned by another session."
            "kubernetes-lease" -> "Lease holder identity has not expired yet."
            else -> "Leadership is already owned."
        }

    private fun skipSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held"
            "zookeeper-curator" -> "ZooKeeper session owns the znode"
            "kubernetes-lease" -> "the Lease holder is still current"
            else -> "the backend reports an active leader"
        }

    private fun releaseDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Lettuce elector releases the lock in the action boundary."
            "zookeeper-curator" -> "Curator session or mutex ownership is cleared before the next run."
            "kubernetes-lease" -> "Lease ownership remains observable until expiry or update."
            else -> "Backend releases or expires ownership."
        }

    private fun primaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-a" else "node-a"

    private fun secondaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-b" else "node-b"

    private fun handoffSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "node-a disappears; node-b executes after the Redis lease expires."
            "zookeeper-curator" -> "node-a loses its ZooKeeper session; node-b executes after the ephemeral znode is gone."
            "kubernetes-lease" -> "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
            else -> "a new candidate executes after the backend exposes leadership loss."
        }
}
```

이 서비스에서 실제 백엔드 클라이언트를 시작하지 마세요. 결정론적이다
실제 모듈의 소스 검증 의미론을 기반으로 하는 비교 모델입니다.

- [ ] **4단계: GREEN 확인 실행**

달리다:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderFailoverLabTest' --no-build-cache --rerun-tasks
```

예상: PASS.

## 작업 5: README 로케일 세트 및 다이어그램

**복잡성:** 높음

**적용:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**파일:**
- 생성: 모듈 `README.md`
- 생성: 모듈 `README.ko.md`
- 생성: `docs/images/readme-diagrams/` 아래에 두 개의 SVG+PNG 다이어그램 쌍
- 수정: 루트 `README.md`
- 수정: 루트 `README.ko.md`

- [ ] **1단계: 아키텍처 다이어그램 만들기**

현재 모범 사례 아키텍처 제품군을 참조 및 렌더링으로 사용합니다.

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.svg \
  -o docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.png -s 2
```

필수 확인사항:

- 전체 크기 PNG 육안 검사;
- XML 구문 분석;
- 카드 텍스트 정렬;
- solid/dashed 스타일이 다른 경우 커넥터 범례;
- 둥근 커넥터 및 터미널 세그먼트 검사;
- 카드 침입이 없고, 불필요한 교차가 없으며, 아이콘 링크가 끊어지지 않습니다.

- [ ] **2단계: 시퀀스 다이어그램 만들기**

모범 사례 시퀀스 참조를 사용하고 렌더링합니다.

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.svg \
  -o docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.png -s 2
```

필수 확인사항:

- 자체 통화 회선 위에 번호가 매겨진 가시적 통화 라벨;
- 레이블은 선을 덮지 않습니다.
- 화살촉 색상은 PNG의 호출 라인과 일치합니다.
- 투명한 `alt`/`else` 몸체;
- 음소거된 모범 사례 팔레트;
- 참가자 헤더, 수명선, 활성화 막대, 행 높이 및 분기 색상이 참조 스타일과 일치합니다.

- [ ] **3단계: README 파일 쓰기**

`README.md`에는 다음이 포함되어야 합니다.

- 언어 스위치;
- 목적 및 비대체 메모;
- 아키텍처 및 시퀀스 이미지;
- 백엔드 선택 매트릭스;
- failover/handoff 시나리오 테이블;
- metric/event 비교표;
- run/test 명령;
- `leader-election`, `leader-zookeeper` 및 `k8s-lease-micrometer`에 대한 링크입니다.

`README.ko.md`은 소스가 동일하고 자연스러운 한국어여야 합니다.

- [ ] **4단계: 루트 모듈 카탈로그 업데이트**

다른 근처의 루트 README 로케일 테이블에 `leader-backend-comparison-lab` 추가
리더의 예.

## 작업 6: CI, Smoke 및 유효성 검사 등록

**복잡성:** 중간

**적용:** `$bluetape4k-code-patterns`

**파일:**
- 수정: `scripts/smoke-validate.sh`
- 수정: `.github/workflows/Examples.yml`
- 수정: 필요한 경우 `.github/workflows/nightly.yml`

- [ ] **1단계: 연기 검증에 모듈 등록**

`all-smoke`에 `:leader-backend-comparison-lab:test`을 추가하고 업데이트가 예상됩니다.
`./gradlew projects --console=plain` 이후 `stale-check`의 프로젝트 수
카운트를 확인합니다.

- [ ] **2단계: 예제 워크플로 경로 및 스모크 작업 등록**

추가하다:

```yaml
- 'leader/backend-comparison-lab/**'
```

요청 경로 필터를 푸시 및 풀하려면 `:leader-backend-comparison-lab:test`을 추가합니다.
smoke example Gradle 명령에 테스트 결과 아티팩트 경로를 추가합니다.

- [ ] **3단계: 워크플로 유효성 검사 실행**

달리다:

```bash
rg -n "\\\\'" .github/workflows
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
```

예상: 이스케이프된 워크플로 표현식 인용부호와 actionlint PASS가 없습니다.

## 작업 7: 전체 확인 및 검토

**복잡성:** 높음

**적용:** `$verification-before-completion`, `$bluetape4k-code-patterns`, `$bluetape4k-diagram`

**파일:**
- 생성: `docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`
- 생성: `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md`

- [ ] **1단계: 대상 모듈 확인 실행**

```bash
./gradlew :leader-backend-comparison-lab:compileKotlin :leader-backend-comparison-lab:compileTestKotlin --warning-mode all
./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks
```

- [ ] **2단계: 저장소 유효성 검사 실행**

```bash
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
git diff --check
```

- [ ] **3단계: 6-R단계 검토 실행**

성능, 안정성, 측면에서 `origin/develop...HEAD`의 차이점을 검토하세요.
보안, 운영자, developer/API, user/caller 및 현재 세션 통합.
레코드 P0/P1/P2/P3이(가) 포함됩니다.
`docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`.
P0/P1은 PR 앞에 0이어야 합니다.

- [ ] **4단계: 강의 작성 및 커밋**

다음을 사용하여 `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md` 만들기
소스 기반 결정, diagram/validation 증거 및 향후 지침.

## 작업 8: 커밋, PR, CI 및 DoD

**복잡성:** 중간

**적용:** `$verification-before-completion`, `$bluetape4k-workflow`

**파일:**
- `bluetape4k-workflow/templates/pr-body-step-dod.md` 다음의 PR 신체 임시 파일

- [ ] **1단계: Lore 예고편으로 커밋**

영어 커밋 메시지를 사용하고 `Tested:` / `Not-tested:` 예고편을 포함하세요.

- [ ] **2단계: 분기 푸시 및 PR 생성**

PR 요구사항:

- 영어 제목;
- `Closes #328`;
- 양수인 `debop`;
- 마일스톤 `1.3.1`;
- GitHub이 지원하는 경우 미러링된 이슈 라벨;
- 마지막 PR 본문 섹션은 `## DoD Status`입니다.

- [ ] **3단계: 실시간 확인 PR metadata/body**

```bash
gh pr view <number> --repo bluetape4k/bluetape4k-workshop --json body,assignees,labels,milestone
```

예상: 최종 Markdown `##` 제목은 `## DoD Status`입니다.

- [ ] **4단계: post-PR 검토 및 CI 게이트 실행**

실제 PR diff에 대해 7-R단계를 실행한 후 다음을 사용하여 CI을 확인합니다.

```bash
gh pr view <number> --repo bluetape4k/bluetape4k-workshop --json statusCheckRollup
```

예상: 모든 필수 검사 `SUCCESS` 또는 `SKIPPED`.
