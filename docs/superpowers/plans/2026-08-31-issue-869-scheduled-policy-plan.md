# YAML scheduled policy를 tenant scheduler 예제에 적용 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `leader/tenant-scheduler`에 bluetape4k `2.0.0-SNAPSHOT`의 Spring Boot YAML scheduled policy 경로를 기존 logical-tick reducer와 분리된 `scheduled-policy` profile 예제로 추가한다. 기본 profile의 결정론적 동작은 유지하고, 실제 main sourceSet Spring runtime proxy, exact selector, fail-fast binding, Spring task lifecycle, AOP opt-out 경계를 테스트와 양국어 README로 증명한다.

**Architecture:** root `bluetape4k-dependencies` BOM이 versionless `bluetape4k-leader-spring-boot`와 `bluetape4k-leader-micrometer`를 공급한다. `leader-micrometer`는 `leader-spring-boot`의 `@ConditionalOnClass` observation recorder를 실제 runtime classpath에 제공하므로 YAML의 `aop.metrics.tags.lock-name.mode=REDACT`가 실행 경로에 적용된다. `@Profile("scheduled-policy")` configuration이 plain `@Scheduled` fixture, `@EnableScheduling`, `@EnableAspectJAutoProxy(proxyTargetClass = true)`를 활성화하고 `spring.aop.auto=false`로 Boot 중복 proxy creator를 막는다. upstream `LeaderScheduledPolicyAutoConfiguration`이 YAML registry/BPP/factory/aspect를 소유하며, Spring이 task 등록·trigger·Observation·context close를 소유한다. consumer 모듈은 registry, BPP, scheduler, executor, backend를 복제하지 않는다. upstream 시험판의 external CTW singleton 경로는 `NoAspectBoundException`으로 실행되지 않아 채택하지 않는다.

**Tech Stack:** Kotlin 2.4.0, Java 25, catalog 기준 Spring Boot 4.1.0, Gradle version catalog, bluetape4k `2.0.0-SNAPSHOT`, Spring AOP runtime proxy, JUnit 5, Spring Boot Test, Bluetape assertion helpers.

## 구현 단계 보정 (2026-09-01)

초기 계획에서 external AspectJ CTW/Freefair weaving을 실행 경계로 제안했으나,
upstream `LeaderElectionAspect.aspectOf()`가 존재하지 않는 no-arg constructor를
호출하는 `NoSuchMethodError`와 `NoAspectBoundException`을 source/artifact smoke에서
재현했다. 최종 구현은 CTW를 제거하고 `@EnableAspectJAutoProxy(proxyTargetClass = true)`
및 `spring.aop.auto=false`를 사용한 Spring runtime proxy로 고정한다. 계획 본문의 CTW
표현과 해당 acceptance는 historical discovery로 보존하며, 이 보정과 충돌하는 경우
최종 runtime-proxy 구현·테스트·README를 우선한다.

---

## 실행 전 고정 조건

- [x] 작업 디렉터리는 `/Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/feat/issue-869-scheduled-policy`이고 branch는 `feat/issue-869-scheduled-policy`인지 확인했다.
- [x] 사양서 커밋 `c7338ae4263adef1f599fbebcee75a3edfd69c50`를 기준으로 작업했고, 이미 존재하는 다른 worktree의 dirty 파일은 건드리지 않았다.
- [x] `docs/superpowers/specs/2026-08-31-issue-869-scheduled-policy-design.md`와 `docs/review/2026-08-31-issue-869-scheduled-policy-spec-review.md`의 최신 통합 결과가 `P0=0`, `P1=0`인지 읽고 시작했다.
- [x] 이 계획과 계획 review가 커밋되고 사용자가 계획을 승인하기 전에는 Kotlin, Gradle, YAML, README 구현 파일을 수정하지 않았다. 계획 승인 후에만 구현을 시작했다.
- [x] 모든 외부 문서·README·lesson·issue/PR 문장은 한국어로 작성하고 코드, 명령, API 이름, 식별자, URL, machine token은 원문을 보존했다.

초기 검증 명령은 다음 순서로 실행한다.

```bash
git status --short --branch
git log -1 --oneline
./gradlew :leader-tenant-scheduler:test --no-daemon --console=plain
git diff --check
```

초기 baseline이 실패하면 구현을 시작하지 않고 실패한 task와 원인을 계획 review artifact에 기록한다. baseline 성공은 기존 19개 reducer/README 테스트가 새 변경 없이 통과했다는 기준선이다.

## 작업 1 — Gradle catalog와 consumer classpath 고정

**파일:**

- `gradle/libs.versions.toml`
- `leader/tenant-scheduler/build.gradle.kts`

### 1A. version catalog 변경

- [x] 별도 AspectJ CTW plugin alias는 추가하지 않는다. 현재 upstream 시험판의 external singleton 경로가 `NoAspectBoundException`으로 실행되지 않으므로 이 consumer는 Spring runtime proxy만 사용한다.
- [x] `bluetape4k-leader-spring-boot`와 `bluetape4k-leader-micrometer` library alias는 versionless로 유지한다. 각 alias에 버전을 넣지 않는다.
- [x] 별도 bluetape BOM을 import하거나 individual artifact version을 catalog에 추가하지 않았다. root `build.gradle.kts`의 `bluetape4k-dependencies` BOM만 version authority로 남겼다.

### 1B. module build script 변경

- [x] `leader/tenant-scheduler/build.gradle.kts`의 `plugins`에는 별도 CTW plugin을 추가하지 않는다.
- [x] `dependencies`에 다음 versionless implementation을 추가한다.

  ```kotlin
  implementation(libs.bluetape4k.leader.spring.boot)
  ```

- [x] leader-aspect Observation과 `REDACT` tag 정책을 실제 runtime에서 확인할 수 있도록 다음 versionless implementation도 추가한다. 이 alias는 이미 catalog에 있고 root BOM이 버전을 결정한다.

  ```kotlin
  implementation(libs.bluetape4k.leader.micrometer)
  ```

- [x] CTW 전용 `adviceDidNotMatch` suppression이나 plugin configuration은 추가하지 않는다. runtime proxy wiring은 profile configuration에서 명시한다.

### 1C. classpath와 compile 확인

- [x] `./gradlew :leader-tenant-scheduler:dependencies --configuration runtimeClasspath --no-daemon --console=plain` 출력에서 `bluetape4k-dependencies:2.0.0-SNAPSHOT`, version-resolved `bluetape4k-leader-spring-boot`, `bluetape4k-leader-micrometer`를 확인했고, Bluetape artifact에 explicit `1.4.0`·`1.7.0` pin이나 individual BOM import가 없음을 확인했다.
- [x] `./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --no-build-cache --no-daemon --console=plain`으로 catalog accessor와 upstream API classpath를 확인했다.
- [x] `./gradlew :leader-tenant-scheduler:buildEnvironment --no-daemon --console=plain`으로 root plugin/BOM provenance를 기록했다. 별도 AspectJ plugin이나 checksum 파일은 추가하지 않았다.
- [x] dependency failure가 없어 직접 pin이나 재실행 repair는 필요하지 않았다.

## 작업 2 — TDD red: profile contract와 lifecycle 검증 골격 작성

**파일:**

- `leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyContextTest.kt`
- `leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyLifecycleTest.kt`

구현 클래스보다 테스트 계약을 먼저 추가한다. 최초 실행은 production configuration과 fixture가 아직 없으므로 컴파일 또는 assertion 단계에서 실패해야 하며, 실패 출력과 기대 계약을 `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`에 구현 완료 후 기록한다.

### 2A. 공통 test wiring

- [x] 패키지는 `io.bluetape4k.workshop.leader.tenantscheduler.scheduled`로 고정했다.
- [x] 실제 `application-scheduled-policy.yml`을 읽는 context 테스트는 `@SpringBootTest(classes = [TenantSchedulerLabApp::class])`, `@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class])`, `@ActiveProfiles("scheduled-policy")` 조합을 사용하고, profile property를 inline YAML로 재작성하지 않는다.
- [x] malformed, empty, duplicate, unmatched, overload, invalid duration은 `ApplicationContextRunner`와 inline property로 검증하고, 정상 경로는 저장소 YAML에서 읽는다.
- [x] 기존 테스트의 `TestMutexService`, `@TestInstance(PER_CLASS)`, Bluetape assertion convention을 재사용했고 `Thread.sleep`은 사용하지 않았다.

### 2B. context acceptance를 먼저 고정

- [x] default profile context에서 `LeaderScheduledPolicyRegistry`, policy BPP, `tenantScheduledPolicyFixture`의 신규 scheduled task가 없음을 검증했다. 기존 retention task 같은 unrelated Spring task는 범위에서 제외하고, 기존 19개 test가 그대로 실행되는 것도 확인했다.
- [x] `scheduled-policy` profile에서 다음을 검증하는 테스트를 작성했다.
  - 실제 YAML이 `bluetape4k.leader.scheduling.enabled=true`로 binding된다.
  - selector가 정확히 `tenantScheduledPolicyFixture#reconcile`이다.
  - policy registry와 BPP가 존재한다.
  - `ScheduledTaskHolder.scheduledTasks`의 task 수가 정확히 1이다.
  - `tenantScheduledPolicyFixture` bean이 존재한다.
  - `internalAutoProxyCreator`가 존재하고 `AopUtils.getTargetClass(fixture)`가 main-source fixture를 가리키는지 확인한다.
  - `name`이 `tenant-scheduler:reconcile`이고 `bean`이 `localLeaderElectionFactory`이며, `failure-mode=SKIP`, `allow-method-invocation=false`, lock-name metric mode가 `REDACT`이다.
- [x] `bluetape4k.leader.observability.tracing.include-lock-name=false`, `include-leader-id=false`, `include-exception-details=false` 기본값을 context에서 확인하고, test override에서 `REDACT` sentinel만 기록하며 raw lock/customer identifier나 throwable detail을 기록하지 않는다는 negative assertion을 두었다.
- [x] `Environment.propertySources`에서 `application-scheduled-policy.yml` config data source를 진단하고, `ClassPathResource` 원문과 동일한 selector/name binding을 확인해 정상 profile binding이 packaged resource에서 왔음을 증명했다.
- [x] main sourceSet fixture bean의 `reconcile()`를 context에서 직접 두 번 호출하는 Spring proxy smoke를 작성한다. packaged YAML의 `min-lease-time=5s` floor를 의도적으로 그대로 실행하므로 두 호출을 합쳐 `assertTimeout(Duration.ofSeconds(15))` 상한을 사용하고, 별도 trigger/edge-case runner만 `min-lease-time=0s`로 둔다. invocation count가 2인지, 각 호출의 local factory 선택과 leader-aspect observation이 유지되는지, `ScheduledTaskHolder` task 수가 계속 1인지 확인한다. upstream metadata/factory cache의 hit/miss 또는 reflection scan 횟수는 consumer에 관찰 가능한 hook이 없으므로 이 테스트의 acceptance에서 제외하고 `N/A (upstream cache contract)`로 review artifact에 남긴다.
- [x] `ObservationAutoConfiguration`, `LeaderAopFactoryAutoConfiguration`, `LeaderMicrometerAutoConfiguration`, `LeaderObservationAutoConfiguration`, `LeaderScheduledPolicyAutoConfiguration`, `LeaderAopAutoConfiguration`을 runner에 등록하고, 비NOOP recorder로 두 direct invocation의 `leader.aop.acquire`/`leader.aop.execution`, outcome, `redacted-lock` tag를 확인했다. `localLeaderElectionFactory` 선택은 별도 assertion으로 두었다.
- [x] 직접 호출은 `ScheduledMethodRunnable` wrapper를 우회하므로 scheduler-level Observation을 주장하지 않고, Spring scheduler task 등록·trigger·close는 lifecycle 테스트로 분리했다.

### 2C. binding/fail-fast acceptance를 고정

- [x] 다음 입력마다 context startup failure와 관련 property 또는 selector가 포함된 메시지를 검증했다.
  - `enabled=true` + 빈 `policies`
  - 기본 profile에서 외부 `bluetape4k.leader.scheduling.enabled=true`만 주입한 경우도 빈 policy startup failure로 fail-closed인지 확인한다. 외부 override가 profile 경계를 우회한다는 사실은 README와 rollback 절차에 함께 기록한다.
  - `tenantScheduledPolicyFixture.reconcile`, whitespace, `#` 중복, 빈 bean/method 이름
  - 매칭되지 않는 selector
  - duplicate selector
  - overloaded method selector
  - 해석할 수 없는 duration
  - plain `@Scheduled` policy의 음수 `wait-time`, 0 또는 음수 `lease-time`, `min-lease-time > lease-time`, 빈 `name`
- [x] explicit `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 있는 보조 fixture에 같은 selector property를 제공하고 annotation 경로가 property policy보다 우선하며 selector가 `markObserved`되는지 검증했다.
- [x] explicit annotation이 우선하는 경우 미사용 property의 duration/name semantic validation을 plain policy 계약과 분리하고 annotation 자체의 upstream validator 범위만 확인했다.
- [x] overload selector registry signature를 별도 unit assertion으로 두어 Spring scheduled method 인자 검증과 selector ambiguity를 분리했다.
- [x] auto-configuration import 파일의 factory → policy → metrics/observation → AOP 순서를 확인하고, runner에서 enabled/opt-out 조건의 conditional bean 상태를 검증했다.

### 2D. AOP 조건과 보안 경계 acceptance를 고정

- [x] `spring.aop.auto=false`가 profile configuration의 명시적인 `@EnableAspectJAutoProxy`를 끄지 않고 proxy bean과 leader aspect 경로를 유지하는지 검증했다.
- [x] `bluetape4k.leader.aop.enabled=false` override context에서 factory/registry/aspect 조건부 bean이 사라지고 profile의 Spring scheduled task만 남는지 검증했으며, 무잠금 단일 프로세스 학습용 opt-out을 README와 맞췄다.
- [x] opt-out context에서 fixture bean/task 1개, leader infrastructure 부재, direct body 호출, leader observation 부재를 정확히 고정했다.
- [x] fixture invocation과 observation 기록에 raw tenant/customer identifier가 없고 static `tenant-scheduler:reconcile`만 사용됨을 negative assertion으로 고정했다.
- [x] static `name`만 사용하고 동적 SpEL/placeholder 실행은 upstream 계약에 위임하며, consumer에서는 `allow-method-invocation=false`와 static-name/no-raw-data 경계를 검증했다.
- [x] backend failure mode는 외부 backend를 추가하지 않고 upstream 계약에 위임했으며, YAML `SKIP`, README의 `FAIL_OPEN_RUN` 경고, local-only limitation을 문서와 assertion으로 추적했다.

### 2E. lifecycle test 골격

- [x] lifecycle fixture는 `@Scheduled(initialDelay = 60_000, fixedDelay = 50)`으로 등록하고 custom executor/thread를 만들지 않으며, 별도 immediate fixture로 실제 `ScheduledMethodRunnable` trigger를 검증했다.
- [x] context open 후 `ScheduledTaskHolder.scheduledTasks`가 1개이고 close 후 0개인지 확인했다.
- [x] in-flight fixture의 bounded release/finish latch와 `context.close()` timeout을 검증했으며 `cancel(false)`가 interrupt를 보장한다고 가정하지 않았다.
- [x] 모든 await는 bounded `CountDownLatch` timeout을 사용하고 close/state 정리는 `finally`에서 수행했다.
- [x] JUnit non-preemptive timeout과 `min-lease-time=0s` edge-case runner를 사용하고, CI job의 25분 상한/`--no-daemon --console=plain` 로그 계약을 유지했다.

red 실행 명령:

```bash
./gradlew :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyContextTest*' --tests '*TenantScheduledPolicyLifecycleTest*' --no-daemon --console=plain
```

실패가 전혀 발생하지 않으면 테스트가 신규 contract를 실제로 검증하는지 확인하고, production 구현을 미리 참조해 red 단계를 건너뛰지 않았는지 review artifact에 기록한다.

## 작업 3 — main sourceSet scheduled-policy configuration과 fixture 구현

**파일:**

- `leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyConfiguration.kt`
- `leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyFixture.kt`

### 3A. profile configuration

- [x] 다음 계약을 그대로 구현했다.

  ```kotlin
  @Configuration(proxyBeanMethods = false)
  @Profile("scheduled-policy")
  @EnableScheduling
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  class TenantScheduledPolicyConfiguration {
      @Bean
      fun tenantScheduledPolicyFixture(): TenantScheduledPolicyFixture =
          TenantScheduledPolicyFixture()
  }
  ```

- [x] configuration은 scheduler engine, executor, backend, registry, BPP를 직접 생성하지 않는다.
- [x] bean method가 반환하는 fixture의 이름이 YAML selector의 `tenantScheduledPolicyFixture`와 일치하는지 context test로 고정했다.

### 3B. main-source fixture

- [x] CGLIB 기반 Spring runtime proxy 대상이 되도록 `open class`와 `open fun reconcile()`을 사용한다.
- [x] scheduled method는 다음 annotation을 사용한다.

  ```kotlin
  @Scheduled(fixedDelay = 5_000, initialDelay = 60_000)
  open fun reconcile() {
      invocations.incrementAndGet()
  }
  ```

- [x] invocation count는 `AtomicInteger`로 저장하고 읽기 메서드만 제공한다. fixture는 별도 thread, executor, sleep, network, database를 만들지 않는다.
- [x] KDoc은 한국어로 작성하고 첫 자동 실행 지연, min lease floor, local factory의 distributed ownership 제한을 명시했다.

## 작업 4 — profile YAML과 실제 resource loading 고정

**파일:** `leader/tenant-scheduler/src/main/resources/application-scheduled-policy.yml`

- [x] 다음 YAML을 추가했다. key 이름과 duration 표기는 upstream `LeaderScheduledPolicyProperties` contract에 맞춘다.

  ```yaml
  spring:
    aop:
      auto: false

  bluetape4k:
    leader:
      scheduling:
        enabled: true
        policies:
          - selector: "tenantScheduledPolicyFixture#reconcile"
            name: "tenant-scheduler:reconcile"
            wait-time: 0s
            lease-time: 30s
            min-lease-time: 5s
            bean: "localLeaderElectionFactory"
            auto-extend: false
            stream-bounded: false
            failure-mode: SKIP
      aop:
        strict: true
        spel:
          allow-method-invocation: false
        metrics:
          tags:
            lock-name:
              mode: REDACT
              redacted-value: redacted-lock
      observability:
        tracing:
          enabled: true
          include-lock-name: false
          include-leader-id: false
          include-exception-details: false
  ```

- [x] 기본 `src/main/resources/application.yml`에는 `scheduling.enabled` 또는 policy list를 추가하지 않았다.
- [x] `@ActiveProfiles("scheduled-policy")` context가 `ConfigDataApplicationContextInitializer`를 통해 이 파일을 읽고, 정상 profile test의 inline property가 selector·name·duration을 대체하지 않는지 확인했다.
- [x] profile resource가 test/runtime classpath에 패키징되는 것을 `@SpringBootTest` startup/close와 `ClassPathResource` assertion으로 증명했다.

## 작업 5 — green 검증과 기존 회귀 보호

**파일:** 작업 2의 두 test와 필요 시 동일 패키지의 명시 annotation/overload 보조 fixture test 파일

- [x] 작업 2의 red assertion을 production configuration, fixture, YAML과 연결해 green으로 만들었다.
- [x] `@SpringBootTest`와 `ConfigDataApplicationContextInitializer`의 profile startup이 `TenantSchedulerLabAppKt` main class와 component scan을 통해 실제 main fixture와 packaged YAML을 찾는지 확인했다.
- [x] direct Spring proxy smoke는 packaged resource의 `min-lease-time=5s`를 그대로 검증하고, 두 호출 합계에 `assertTimeout(Duration.ofSeconds(15))` 상한을 둔다. scheduler trigger와 edge-case runner만 `min-lease-time=0s`를 사용한다. 상한에 걸리면 lock/proxy/classpath 원인을 조사하며 timeout을 무제한으로 늘리지 않는다.
- [x] leader-aspect observation은 context bean 직접 호출로 확인할 수 있는 upstream observation contract만 사용하고 scheduler wrapper observation 주장은 분리했다.
- [x] lifecycle의 immediate-trigger fixture는 Spring scheduler wrapper callback만 확인하고, production policy의 leader-aspect/cache 증거는 main-source direct-call smoke가 담당하도록 분리했다.
- [x] lifecycle test에서 context close 전후 task 수와 pending cancellation을 증명했으며 custom executor/thread를 만들지 않음을 확인했다.
- [x] 기본 profile을 대상으로 기존 reducer와 `TenantSchedulerReadmeSnippetTest`를 포함한 module 전체 39개 테스트를 변경 없이 실행했다.

검증 명령:

```bash
./gradlew :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyContextTest*' --no-daemon --console=plain
./gradlew :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyLifecycleTest*' --no-daemon --console=plain
./gradlew :leader-tenant-scheduler:test --no-daemon --console=plain
```

실패 시 assertion을 약화하거나 timeout을 제거하지 않고, Spring profile/resource loading, proxy creator ordering, upstream condition, lease-floor 대기 원인을 출력과 함께 수정한다.

## 작업 6 — module/root README, coverage, stale guard 갱신

**파일:**

- `leader/tenant-scheduler/README.md`
- `leader/tenant-scheduler/README.ko.md`
- `README.md`
- `README.ko.md`
- `docs/coverage-matrix.md`
- `scripts/smoke-validate.sh`
- `.github/workflows/Examples.yml`

### 6A. module README pair

- [x] 영어와 한국어 README는 같은 heading 순서, code fence 수, 명령, YAML key, selector, 숫자를 유지하고 각 locale로 자연스럽게 작성했다.
- [x] 두 README의 `Dependencies` 예제에 복사 가능한 versionless consumer wiring인 `implementation(libs.bluetape4k.leader.spring.boot)`와 `implementation(libs.bluetape4k.leader.micrometer)`를 두고 개별 artifact version/BOM을 넣지 않았다.
- [x] 두 파일에 다음 실행 경로를 추가했다.

  ```bash
  ./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy'
  ```

- [x] profile YAML의 exact selector `tenantScheduledPolicyFixture#reconcile`, `localLeaderElectionFactory`, `SKIP`, duration 정책, static bounded non-PII `name`을 설명했다.
- [x] 이 consumer가 `@EnableAspectJAutoProxy(proxyTargetClass = true)`로 upstream aspect를 runtime proxy하고 `spring.aop.auto=false`로 Boot 중복 proxy creator를 막는다는 점을 설명한다. `bluetape4k.leader.aop.enabled=false`가 leader factory 조건에 미치는 차이도 표로 고정한다. external CTW singleton 경로는 시험판 artifact 결함으로 사용하지 않는다는 경계를 기록한다.
- [x] `fixedDelay=5s`와 local `min-lease-time=5s` 조합의 약 10초 이상 완료 간격과 단일 프로세스 local example 제한을 설명했다.
- [x] `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 property policy보다 우선하고, empty/malformed/duplicate/unmatched/overload/invalid duration이 startup에서 실패한다는 규칙을 기록했다.
- [x] Spring task/trigger/Observation/close 소유권, fixture의 executor/thread 부재, pending close와 in-flight body interrupt 경계를 설명했다.
- [x] local factory가 외부 backend/Docker 없이 wiring을 학습하는 단일 프로세스 예제이며 distributed ownership 증명이 아님을 적었다.
- [x] 외부 `bluetape4k.leader.scheduling.enabled=true` override가 profile 경계를 우회할 수 있고 rollback 전에 제거해야 함을 경고했다.
- [x] Observation recorder의 `leader.aop.acquire`/`leader.aop.execution`, outcome, `redacted-lock`, tracing 기본값과 trusted override의 raw name/detail 차단 경계를 설명했다.
- [x] `FAIL_OPEN_RUN`을 멱등 작업 전용 trusted override로, 예제 기본값을 `SKIP`으로 기록했다.
- [x] `Started TenantSchedulerLabAppKt`, 최대 60초 initial delay, min lease 추가 지연, `Ctrl-C`, deterministic test와 observation 확인 경로를 안내했다.
- [x] deterministic 검증 명령과 기대 결과를 추가했다.

  ```bash
  ./gradlew :leader-tenant-scheduler:test --tests "*TenantScheduledPolicy*"
  ```

  기대 결과는 신규 context/lifecycle test와 기존 module test 전체가 통과하는 것이다.
- [x] rollback 문구는 외부 `bluetape4k.leader.scheduling.enabled=true` override 제거 → `scheduled-policy` profile 비활성화 → profile YAML·configuration·fixture와 dependency 변경 rollback → 재기동 → `tenantScheduledPolicyFixture`, 해당 `ScheduledTaskHolder` task, registry/BPP가 없는지 확인하는 순서로 작성했다.

### 6B. root README와 coverage

- [x] `README.md`와 `README.ko.md`의 tenant scheduler 표/설명을 versionless `leader-spring-boot`·`leader-micrometer`와 `scheduled-policy` profile 예제로 갱신했다.
- [x] root README pair에 module test 명령과 profile 실행 경로를 추가하고 기존 명령 의미는 유지했다.
- [x] `docs/coverage-matrix.md`에 `bluetape4k-leader-spring-boot` scheduled policy, Spring proxy, lifecycle, Issue #869 coverage row를 추가했다.

### 6C. narrow stale guard

- [x] `scripts/smoke-validate.sh stale-check`에 다음 조건만 추가했다.
  - `leader/tenant-scheduler/src/main/resources/application-scheduled-policy.yml` 파일이 존재한다.
  - YAML에 exact selector `tenantScheduledPolicyFixture#reconcile`가 있다.
  - `TenantScheduledPolicyConfiguration.kt`가 `@Profile("scheduled-policy")`, `@EnableScheduling`, bean 이름을 포함한다.
  - `leader/tenant-scheduler/README.md`와 `README.ko.md`에 `--spring.profiles.active=scheduled-policy`가 있다.
  - 두 README의 dependency snippet에 `bluetape4k.leader.spring.boot`, `bluetape4k.leader.micrometer`가 있다.
- [x] stale guard는 실패 시 관련 계약 누락을 한 줄로 보여 주며 기존 project count/stale ref/required module/leader diagnostics/image link guard를 유지한다.
- [x] `.github/workflows/Examples.yml`의 기존 module registration은 중복하지 않고 `README and stale contract guards` job을 추가했다. 이 job은 `timeout-minutes: 10`, checkout 후 Node/Bash parity·stale guard를 실행하며 `changes` output 조건과 `needs: changes`로 실패를 전파한다. Gradle/Java는 이 job에서 사용하지 않으므로 별도 setup을 두지 않았다.
- [x] PR changed-path가 ecosystem reuse train의 정확히 하나의 track에 매핑되도록 `issue-869-leader-scheduled-policy` follow-up scope를 `docs/ecosystem-reuse-train.json`에 등록했다. exact base/head와 이번 PR의 allowed paths를 고정하고 `check-ecosystem-reuse.py --pr-scope`를 로컬에서 통과시켰다.
- [x] architecture/sequence diagram은 reducer와 Spring integration을 혼합하지 않으므로 수정하지 않았고, diagram은 `N/A`로 review에 기록한다.

검증 명령:

```bash
node scripts/validate-readme-parity.mjs leader/tenant-scheduler
bash scripts/smoke-validate.sh stale-check
```

## 작업 7 — 전체 검증, 문서 품질, lesson

**파일:**

- `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`
- 작업 1~6의 모든 변경 파일

- [x] 다음 검증을 새 프로세스에서 실행하고 출력/exit code를 기록했다.

  ```bash
  ./gradlew :leader-tenant-scheduler:compileKotlin --no-daemon --console=plain
  ./gradlew :leader-tenant-scheduler:test --no-daemon --console=plain
  ./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:test --no-build-cache --no-daemon --console=plain
  ./gradlew projects --no-daemon --console=plain
  node scripts/validate-readme-parity.mjs leader/tenant-scheduler
  bash scripts/smoke-validate.sh stale-check
  git diff --check
  ```

- [x] root compile smoke는 모듈 변경 범위와 별도 root production source 부재를 확인해 `N/A (module-scoped Kotlin/Gradle changes; module compile/test passed)`로 기록한다. container-backed full suite는 이 issue 범위가 아니며 skipped test를 pass로 보고하지 않았다.
- [x] `gradle/libs.versions.toml`에 explicit bluetape version pin 또는 individual BOM이 생기지 않았음을 `rg`로 확인했다.
- [x] buildEnvironment와 dependencies의 Gradle/Bluetape component provenance 및 `2.0.0-SNAPSHOT` 해석 결과를 lesson에 남겼고, 별도 AspectJ plugin/checksum metadata를 추가하지 않은 이유도 기록했다.
- [x] 신규·의미 변경 한국어 문서 7개에 terminology audit을 실행해 `findings=0`을 확인했다. root README/coverage의 기존 용례는 변경하지 않은 범위로 별도 기록했다.
- [x] placeholder/temporary marker 검사에서 미완료 표식이 없음을 확인했다.
- [x] hosted CI 첫 실패의 legacy assertion import를 `shouldBeNull()`로 치환하고 assertion governance 및 39개 테스트를 재실행했다. 두 번째 실패의 missing ecosystem scope는 manifest follow-up scope와 local exact checker로 처분했다.
- [x] `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`를 한국어로 작성했고 Context, Decision, Outcome, Verification, Miss/Surprise, Future guard를 포함했다.
- [x] lesson은 구현·검증 증거 후 추가했고, 실제 명령 결과와 surprise 중심으로 작성했다.

## 작업 8 — 계획/구현 커밋과 handoff

- [x] 이 계획과 plan review artifact는 사용자 계획 승인 전 별도 커밋으로 고정했다. 계획 커밋 `7a70e85f4`의 제목은 `[2.0.0] Issue #869 구현 계획을 고정한다`이고 Lore trailers를 포함한다.
- [x] 구현 커밋은 기능 단위로 작게 나누고 각 commit message에 Korean intent line과 다음 trailers를 포함한다. 구현 커밋은 `dfff002cf43f1322eda04806be52303299cde220`이다.

  ```text
  Constraint: consumer module은 root bluetape4k-dependencies BOM만 사용한다
  Rejected: external CTW singleton 경로는 upstream 시험판에서 NoAspectBoundException이 발생해 제외하고 custom scheduler도 Spring lifecycle 경계를 깨므로 제외했다
  Confidence: high
  Scope-risk: moderate
  Directive: profile 기본값과 exact selector를 변경할 때 README/stale guard/test를 함께 갱신한다
  Tested: <실행한 명령과 핵심 결과>
  Not-tested: <실행하지 못한 항목과 사유 또는 none>
  ```

- [x] 구현 완료 후 `docs/lessons/...`와 tracked review artifact를 포함해 feature branch가 clean인지 확인했다. `dfff002cf43f1322eda04806be52303299cde220` 이후 worktree는 clean이다.
- [x] PR #911을 base `develop`, head `feat/issue-869-scheduled-policy`, title `[2.0.0] Issue #869 ...`, Korean body, `Closes #869`, DoD Status, 테스트/CI 증거로 생성하고 live metadata를 재확인했다. 현재 head는 assertion fix와 manifest scope fix를 포함한다.
- [ ] PR 생성 후 live CI/checks와 review thread를 확인한다. merge는 exact live head, CI green, review 상태를 다시 읽고 새 `승인`이 들어온 뒤에만 수행한다. 이 계획 단계에서는 merge/auto-merge/tag/release를 실행하지 않는다.

## 의존성 순서와 재실행 규칙

1. 작업 1의 catalog/plugin/classpath가 통과해야 작업 2 red test를 실행한다.
2. 작업 2 red 계약을 기록한 뒤 작업 3 main fixture/configuration과 작업 4 YAML을 구현한다.
3. 작업 5 green 검증을 통과해야 작업 6 문서/guard를 갱신한다.
4. 작업 6 parity/stale guard가 통과해야 작업 7 전체 검증과 lesson을 작성한다.
5. 작업 7의 모든 required check가 통과하고 review artifact가 최신화된 뒤 구현 commit/PR을 만든다.
6. Gradle daemon/cache 오류가 발생하면 같은 명령을 `--no-daemon --console=plain`으로 재실행하고, 동일 실패가 계속되면 repo 변경과 환경 문제를 분리해 기록한다. assertion을 삭제하거나 timeout을 무제한으로 늘려 green을 만들지 않는다.
7. profile task가 startup에서 실패하면 YAML binding → selector registry → Spring proxy creator ordering → local factory/lease floor 순서로 진단한다. 외부 backend를 추가해 해결하지 않는다.

## 수용 기준 추적표

| 수용 기준 | 구현 task | 검증 증거 |
|---|---|---|
| BOM 기반 versionless `leader-spring-boot` classpath | 1 | dependencies/compileKotlin 출력 |
| Spring runtime proxy와 external CTW 경계 | 1, 3, 5 | compile/test output, `internalAutoProxyCreator` 존재, direct main-source invocation 및 CTW 실패 기록 |
| 기본 profile 비활성 | 2, 3, 5 | default context bean/task assertions, 기존 19개 test |
| exact selector와 task 1개 | 2, 4, 5 | YAML binding, registry lookup, `ScheduledTaskHolder` count |
| open main fixture와 local factory/leader-aspect observation | 3, 5 | `@SpringBootTest`, packaged `min-lease-time=5s`, two-call `assertTimeout(15s)`, observation/factory assertions |
| explicit annotation precedence | 2, 5 | 보조 fixture와 registry `markObserved`/binding assertions |
| fail-fast invalid configurations | 2, 5 | ApplicationContextRunner failure assertions |
| context close cancellation/no custom thread | 2, 5 | lifecycle holder count 1→0, static/context inspection |
| AOP opt-out distinction와 `FAIL_OPEN_RUN` 경계 | 2, 5, 6 | override contexts, README pair, negative assertion |
| static bounded non-PII/SpEL/REDACT | 2, 4, 5, 6 | property assertions, README pair, metric/name negative checks; dynamic SpEL/placeholder execution is upstream delegated |
| existing reducer/README tests | 5, 7 | module full test output |
| README locale parity와 실행 안내 | 6, 7 | `validate-readme-parity.mjs`, Korean audit |
| stale guard와 기존 workflow 등록 | 6, 7 | `smoke-validate.sh stale-check`, workflow path inspection |

## 계획 단계 DoD 및 승인 게이트

- [x] 계획 문서가 실제 path, exact code/YAML/property, command, expected result, rollback, failure recovery를 포함한다.
- [x] `SPW-01` 문제/독자/목표, `SPW-02` current-state/evidence, `SPW-03` implementation detail, `SPW-04` tests/verification, `SPW-05` DoD/rollback을 모두 계획에 매핑한다.
- [x] 한국어 문서 품질 검토 `KO-01`~`KO-07`와 six-perspective Step 3-R plan review가 기록된다.
- [x] Step 3-R conditional 항목 중 coroutine/suspend, 새 module settings 등록, Exposed import/receiver, JDK preview, external backend capability는 이 issue 범위에 해당하지 않음을 review artifact에 `N/A`로 남기고, 해당하는 auto-configuration ordering, resource ownership/close, blocking lease-floor, stale guard, rollback 항목은 작업 1~7의 구체적인 assertion/명령으로 덮는다.
- [x] six lanes와 main integration의 최신 결과가 `P0=0`, `P1=0`이다. P2/P3는 disposition과 구현 task가 추적된다.
- [x] 계획과 plan review가 Lore commit으로 고정되고, helper required-checks에는 `spec`과 `plan` 증거가 등록된다. `tests`, `docs`, `pr`와 main verification은 구현 후에만 pending에서 해소된다.
- [x] 이 문서와 review artifact를 사용자가 검토할 수 있도록 구현 commit SHA `dfff002cf43f1322eda04806be52303299cde220`와 파일 링크를 handoff에 제시한다.

계획 승인은 확인되었고 작업 1~7의 구현·검증 및 PR #911 생성까지 완료되었다. hosted CI의 두 실패(assertion governance, missing ecosystem scope)는 각각 커밋과 manifest follow-up scope로 처분했으며, 새 head의 hosted CI/live review 확인이 남아 있다. merge·auto-merge·tag·release는 이 작업에서 실행하지 않는다.
