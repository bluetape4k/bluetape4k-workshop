# YAML scheduled policy를 tenant scheduler 예제에 적용 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `leader/tenant-scheduler`에 bluetape4k `2.0.0-SNAPSHOT`의 Spring Boot YAML scheduled policy 경로를 기존 logical-tick reducer와 분리된 `scheduled-policy` profile 예제로 추가한다. 기본 profile의 결정론적 동작은 유지하고, 실제 main sourceSet bytecode weaving, exact selector, fail-fast binding, Spring task lifecycle, AOP opt-out 경계를 테스트와 양국어 README로 증명한다.

**Architecture:** root `bluetape4k-dependencies` BOM이 versionless `bluetape4k-leader-spring-boot`와 `bluetape4k-leader-micrometer`를 공급한다. `leader-micrometer`는 `leader-spring-boot`의 `@ConditionalOnClass` observation recorder를 실제 runtime classpath에 제공하므로 YAML의 `aop.metrics.tags.lock-name.mode=REDACT`가 실행 경로에 적용된다. `io.freefair.aspectj.post-compile-weaving`이 `leader/tenant-scheduler` main sourceSet에 CTW를 적용하고, `@Profile("scheduled-policy")` configuration이 plain `@Scheduled` fixture와 `@EnableScheduling`만 활성화한다. upstream `LeaderScheduledPolicyAutoConfiguration`이 YAML registry/BPP/factory/aspect를 소유하며, Spring이 task 등록·trigger·Observation·context close를 소유한다. consumer 모듈은 registry, BPP, scheduler, executor, backend를 복제하지 않는다.

**Tech Stack:** Kotlin 2.4.0, Java 25, catalog 기준 Spring Boot 4.1.0, Gradle version catalog, bluetape4k `2.0.0-SNAPSHOT`, `io.freefair.aspectj.post-compile-weaving` 9.5.0, JUnit 5, Spring Boot Test, Bluetape assertion helpers.

---

## 실행 전 고정 조건

- [ ] 작업 디렉터리는 `/Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/feat/issue-869-scheduled-policy`이고 branch는 `feat/issue-869-scheduled-policy`인지 확인한다.
- [ ] 사양서 커밋 `ab708c0f47a25b9e4f5b6882470ebc99c46a4b94`를 기준으로 작업하며, 이미 존재하는 다른 worktree의 dirty 파일은 건드리지 않는다.
- [ ] `docs/superpowers/specs/2026-08-31-issue-869-scheduled-policy-design.md`와 `docs/review/2026-08-31-issue-869-scheduled-policy-spec-review.md`의 최신 통합 결과가 `P0=0`, `P1=0`인지 읽고 시작한다.
- [ ] 이 계획과 계획 review가 커밋되고 사용자가 계획을 승인하기 전에는 Kotlin, Gradle, YAML, README 구현 파일을 수정하지 않는다.
- [ ] 모든 외부 문서·README·lesson·issue/PR 문장은 한국어로 작성하고 코드, 명령, API 이름, 식별자, URL, machine token은 원문을 보존한다.

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

- [ ] `[versions]`에 `aspectj-post-compile-weaving = "9.5.0"`을 추가한다.
- [ ] `[plugins]`에 다음 alias를 추가한다. 이미 동일 key가 있으면 새 alias를 만들지 않고 기존 정의가 upstream과 동일한지 확인한다.

  ```toml
  aspectj-post-compile-weaving = { id = "io.freefair.aspectj.post-compile-weaving", version.ref = "aspectj-post-compile-weaving" }
  ```

- [ ] `bluetape4k-leader-spring-boot` library alias는 현재처럼 versionless로 유지한다. `bluetape4k-leader-spring-boot = { module = "io.github.bluetape4k.leader:bluetape4k-leader-spring-boot" }`에 버전을 넣지 않는다.
- [ ] 별도 bluetape BOM을 import하거나 individual artifact version을 catalog에 추가하지 않는다. root `build.gradle.kts`의 `bluetape4k-dependencies` BOM만 version authority로 남긴다.

### 1B. module build script 변경

- [ ] `leader/tenant-scheduler/build.gradle.kts`의 `plugins`에 `alias(libs.plugins.aspectj.post.compile.weaving)`을 추가한다.
- [ ] `dependencies`에 다음 versionless implementation을 추가한다.

  ```kotlin
  implementation(libs.bluetape4k.leader.spring.boot)
  ```

- [ ] leader-aspect Observation과 `REDACT` tag 정책을 실제 runtime에서 확인할 수 있도록 다음 versionless implementation도 추가한다. 이 alias는 이미 catalog에 있고 root BOM이 버전을 결정한다.

  ```kotlin
  implementation(libs.bluetape4k.leader.micrometer)
  ```

- [ ] upstream compile log에서 `adviceDidNotMatch` 경고가 발생하면 같은 module build script에만 다음 typed configuration을 추가한다. 경고가 발생하지 않으면 suppression을 추가하지 않고 실제 출력 근거를 review artifact에 남긴다.

  ```kotlin
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
      extensions.configure<io.freefair.gradle.plugins.aspectj.AjcAction>("ajc") {
          options.compilerArgs.add("-Xlint:adviceDidNotMatch=ignore")
      }
  }
  ```

- [ ] `@EnableAspectJAutoProxy`나 `spring-aop` runtime proxy 설정을 추가하지 않는다.

### 1C. classpath와 compile 확인

- [ ] `./gradlew :leader-tenant-scheduler:dependencies --configuration runtimeClasspath --no-daemon --console=plain` 출력에 `bluetape4k-dependencies:2.0.0-SNAPSHOT`, version-resolved `bluetape4k-leader-spring-boot`, `bluetape4k-leader-micrometer`가 있고, explicit `1.4.0`, `1.7.0`, individual bluetape BOM import가 없는지 확인한다.
- [ ] `./gradlew :leader-tenant-scheduler:compileKotlin --no-daemon --console=plain`을 실행해 catalog accessor, plugin, upstream API classpath를 확인한다.
- [ ] `./gradlew :leader-tenant-scheduler:buildEnvironment --no-daemon --console=plain`과 `./gradlew :leader-tenant-scheduler:dependencyInsight --configuration runtimeClasspath --dependency io.freefair.aspectj.post-compile-weaving --no-daemon --console=plain`으로 plugin marker와 AspectJ transitive artifact의 좌표·버전을 기록한다. 저장소에 Gradle verification metadata가 없으므로 이 issue에서는 새 checksum 파일을 생성하지 않고, 해석된 repository/component provenance를 lesson과 review artifact에 남기며 checksum 검증은 후속 공급망 hardening 범위로 명시한다.
- [ ] 실패 시 dependency를 직접 pin하지 않고 catalog alias/BOM/plugin accessor 문제를 먼저 수정한 뒤 같은 두 명령을 재실행한다.

## 작업 2 — TDD red: profile contract와 lifecycle 검증 골격 작성

**파일:**

- `leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyContextTest.kt`
- `leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyLifecycleTest.kt`

구현 클래스보다 테스트 계약을 먼저 추가한다. 최초 실행은 production configuration과 fixture가 아직 없으므로 컴파일 또는 assertion 단계에서 실패해야 하며, 실패 출력과 기대 계약을 `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`에 구현 완료 후 기록한다.

### 2A. 공통 test wiring

- [ ] 패키지는 `io.bluetape4k.workshop.leader.tenantscheduler.scheduled`로 고정한다.
- [ ] 실제 `application-scheduled-policy.yml`을 읽는 context 테스트는 다음 annotation 조합을 사용한다. `@SpringBootTest(classes = [TenantSchedulerLabApp::class])`, `@ContextConfiguration(initializers = [ConfigDataApplicationContextInitializer::class])`, `@ActiveProfiles("scheduled-policy")`를 고정하고, profile property를 inline YAML로 재작성하지 않는다.
- [ ] malformed, empty, duplicate, unmatched, overload, invalid duration은 `ApplicationContextRunner`와 inline property로 빠르게 검증한다. inline 값은 failure assertion의 입력으로만 사용하고 정상 경로는 저장소 YAML에서 읽는다.
- [ ] 기존 테스트의 `TestMutexService`, `@TestInstance(PER_CLASS)`, Bluetape assertion convention을 재사용한다. `Thread.sleep`은 사용하지 않는다.

### 2B. context acceptance를 먼저 고정

- [ ] default profile context에서 `LeaderScheduledPolicyRegistry`, policy BPP, `ScheduledTaskHolder`의 신규 task가 없음을 검증한다. 기존 19개 test가 그대로 실행되는 것도 함께 확인한다.
- [ ] `scheduled-policy` profile에서 다음을 검증하는 테스트를 작성한다.
  - 실제 YAML이 `bluetape4k.leader.scheduling.enabled=true`로 binding된다.
  - selector가 정확히 `tenantScheduledPolicyFixture#reconcile`이다.
  - policy registry와 BPP가 존재한다.
  - `ScheduledTaskHolder.scheduledTasks`의 task 수가 정확히 1이다.
  - `tenantScheduledPolicyFixture` bean이 존재한다.
  - `internalAutoProxyCreator`가 존재하지 않으며 runtime proxy 유무를 CTW 성공 기준으로 사용하지 않는다.
  - `name`이 `tenant-scheduler:reconcile`이고 `bean`이 `localLeaderElectionFactory`이며, `failure-mode=SKIP`, `allow-method-invocation=false`, lock-name metric mode가 `REDACT`이다.
- [ ] `bluetape4k.leader.observability.tracing.include-lock-name=false`, `include-leader-id=false`, `include-exception-details=false` 기본값을 context에서 확인한다. test-only `include-lock-name=true` override에서도 `REDACT` sentinel만 기록하고 raw lock/customer identifier나 throwable detail을 기록하지 않는다는 negative assertion을 둔다.
- [ ] `Environment.propertySources`에서 `application-scheduled-policy.yml`을 가리키는 config data source를 진단하고, 그 source의 selector/name 값을 확인해 정상 profile binding이 inline property가 아닌 packaged resource에서 왔음을 증명한다. source 이름의 구현별 표기는 진단용으로만 사용하고, acceptance는 `ClassPathResource` 원문·`processResources` 산출물·inline selector 부재와 동일 값 binding을 함께 확인한다.
- [ ] main sourceSet fixture bean의 `reconcile()`를 context에서 직접 두 번 호출하는 CTW smoke를 작성한다. 각 호출은 `assertTimeout(Duration.ofSeconds(5))` 안에서 수행하고 test properties `bluetape4k.leader.scheduling.policies[0].min-lease-time=0s` 및 `bluetape4k.leader.observability.tracing.include-lock-name=true`를 주어 production YAML의 5초 lease floor가 검증을 막지 않게 하고 REDACT tag를 실제로 관찰한다. invocation count가 2인지, 각 호출의 local factory 선택과 leader-aspect observation이 유지되는지, `ScheduledTaskHolder` task 수가 계속 1인지 확인한다. upstream metadata/factory cache의 hit/miss 또는 reflection scan 횟수는 consumer에 관찰 가능한 hook이 없으므로 이 테스트의 acceptance에서 제외하고 `N/A (upstream cache contract)`로 review artifact에 남긴다.
- [ ] `ObservationAutoConfiguration`, `LeaderAopFactoryAutoConfiguration`, `LeaderMicrometerAutoConfiguration`, `LeaderObservationAutoConfiguration`, `LeaderScheduledPolicyAutoConfiguration`, `LeaderAopAutoConfiguration`을 `ApplicationContextRunner`에 upstream import 순서대로 등록하고, 비NOOP `ObservationRegistry`와 `ObservationHandler<Observation.Context>` recorder를 user configuration으로 제공한다. 두 direct invocation에서 `leader.aop.acquire`와 `leader.aop.execution` observation이 각각 발생하는지 기록으로 확인한다. acquire 기록은 `leader.operation=acquire`, `outcome=acquired`, execution 기록은 `leader.operation=execute`, `outcome=success`여야 하며, high-cardinality `lock.name` 값은 `redacted-lock`이고 `tenant-scheduler:reconcile` 원문은 없어야 한다. `localLeaderElectionFactory` bean 타입/선택은 observation과 별도 assertion으로 둔다.
- [ ] 직접 호출은 `ScheduledMethodRunnable` wrapper를 우회하므로 scheduler-level Observation을 주장하지 않는다. Spring scheduler task 등록·trigger·close는 lifecycle 테스트의 책임으로 남긴다.

### 2C. binding/fail-fast acceptance를 고정

- [ ] 다음 입력마다 context startup failure와 관련 property 또는 selector가 포함된 메시지를 검증한다.
  - `enabled=true` + 빈 `policies`
  - 기본 profile에서 외부 `bluetape4k.leader.scheduling.enabled=true`만 주입한 경우도 빈 policy startup failure로 fail-closed인지 확인한다. 외부 override가 profile 경계를 우회한다는 사실은 README와 rollback 절차에 함께 기록한다.
  - `tenantScheduledPolicyFixture.reconcile`, whitespace, `#` 중복, 빈 bean/method 이름
  - 매칭되지 않는 selector
  - duplicate selector
  - overloaded method selector
  - 해석할 수 없는 duration
  - plain `@Scheduled` policy의 음수 `wait-time`, 0 또는 음수 `lease-time`, `min-lease-time > lease-time`, 빈 `name`
- [ ] explicit `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 있는 보조 fixture에 같은 selector property를 제공하고 annotation 경로가 property policy보다 우선하는지 검증한다. selector는 `markObserved`되어 unmatched failure를 일으키지 않아야 한다.
- [ ] explicit annotation이 우선하는 경우 미사용 property의 duration/name semantic validation을 plain policy 계약과 섞지 않는다. annotation 자체의 upstream validator 결과만 확인한다.
- [ ] overload selector registry signature는 별도 unit assertion으로 두어 Spring의 scheduled method 인자 검증과 selector ambiguity를 분리한다.
- [ ] upstream auto-configuration ordering 검증은 `ApplicationContextRunner`를 설정용으로 사용하고, `AutoConfigurations.of(LeaderAopFactoryAutoConfiguration, LeaderScheduledPolicyAutoConfiguration, LeaderAopAutoConfiguration)`는 로딩 대상만 지정한다. 실제 순서는 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`의 factory → policy registry/BPP → AOP 위치와 upstream import-order test assertion으로 확인한다. `enabled=false`, missing policy property, `bluetape4k.leader.aop.enabled=false` 조건에서 각 conditional bean이 생기지 않는지도 같은 runner에서 확인한다.

### 2D. AOP 조건과 보안 경계 acceptance를 고정

- [ ] `spring.aop.auto=false` override context에서 runtime proxy bean은 없지만 main-source CTW invocation은 계속 leader aspect 경로를 통과하는지 검증한다.
- [ ] `bluetape4k.leader.aop.enabled=false` override context에서 factory/registry/aspect 조건부 bean이 사라지고 profile의 Spring scheduled task만 남을 수 있음을 검증한다. 이 경로가 무잠금 단일 프로세스 학습용 opt-out이라는 문구를 README acceptance와 맞춘다.
- [ ] 위 opt-out context의 기대 상태를 정확히 고정한다: `tenantScheduledPolicyFixture` bean과 `ScheduledTaskHolder` task 1개는 존재하고, `localLeaderElectionFactory`, `LeaderScheduledPolicyRegistry`, policy BPP, `LeaderElectionAspect` bean은 존재하지 않으며, direct fixture 호출은 leader observation 없이 본문만 실행된다. 이 상태를 “may remain”으로 남기지 않는다.
- [ ] fixture invocation과 observation/metric 기록에 raw tenant/customer identifier가 포함되지 않음을 negative assertion 또는 static configuration assertion으로 고정한다. static `name`은 `tenant-scheduler:reconcile` 하나만 허용한다.
- [ ] 이 consumer example은 static `name`만 사용하므로 동적 SpEL/placeholder 입력을 실행하지 않는다. `allow-method-invocation=false`와 `${SECRET}` 해석·메서드 호출 차단의 parser/evaluator 세부 동작은 upstream `SpelExpressionEvaluatorTest` 계약에 위임하고, consumer에서는 해당 property binding과 static-name/no-raw-data 경계만 검증한다. upstream logger가 trusted lock name을 출력할 수 있는 범위도 README에 명시한다.
- [ ] backend failure mode는 외부 backend를 consumer context에 추가하지 않고 upstream `LeaderElectionAspectFailureModeTest`/`FailOpenRunIntegrationTest` 계약에 위임한다. consumer 범위에서는 YAML 기본값 `SKIP`, README의 `FAIL_OPEN_RUN` trusted/must-be-idempotent 경고, local-only limitation을 assertion/문서로 추적하고, backend capability test는 `N/A (외부 backend 제외)`로 review artifact에 명시한다.

### 2E. lifecycle test 골격

- [ ] 별도 test sourceSet lifecycle fixture는 `@Scheduled(initialDelay = 60_000, fixedDelay = 50)`으로 등록하고, custom executor/thread를 만들지 않는다. 이 fixture는 CTW invocation 증거가 아니라 Spring registration/close 증거에만 사용한다. 같은 test class에 별도 `@Scheduled(initialDelay = 0, fixedDelay = 100)` fixture context를 두어 `CountDownLatch`가 5초 안에 한 번 내려가는 실제 `ScheduledMethodRunnable` trigger도 검증한다. 두 context를 분리해 pending-task count와 immediate-trigger count가 서로 영향을 주지 않게 한다.
- [ ] context가 열린 뒤 `ScheduledTaskHolder.scheduledTasks`가 정확히 1개인지 확인한다.
- [ ] context close 후 pending task set이 0인지 확인한다. in-flight method body가 `cancel(false)`로 interrupt 또는 즉시 중단된다고 가정하지 않는다.
- [ ] 별도 in-flight fixture는 entered latch를 내린 뒤 bounded `releaseLatch.await(2, TimeUnit.SECONDS)`로 기다리게 하고, `context.close()`를 `assertTimeout(Duration.ofSeconds(5))`으로 감싼다. close assertion은 release latch에 의존하지 않으며 fixture의 독립적인 2초 timeout으로 scheduler body가 자연스럽게 빠져나오게 한다. 테스트 메서드에도 `@Timeout(10)`을 적용해 close 자체의 상한을 이중으로 고정한다. `finally`에서는 어떤 close 재시도보다 먼저 release latch를 내리고, body 종료를 확인한 뒤 남은 context를 정리해 release가 close 뒤에만 실행되어 hang하는 순서를 금지한다. Spring의 `cancel(false)`가 interrupt/본문 즉시 중단을 보장하지 않는다는 결과를 기록하고, 무기한 대기를 허용하지 않는다.
- [ ] 모든 await는 `CountDownLatch`와 `Duration.ofSeconds(5)` 이상의 bounded timeout을 사용하고, close와 fixture 상태 정리를 `finally`에서 수행한다.
- [ ] JUnit `assertTimeout`은 non-preemptive임을 알고, zero `wait-time`/`min-lease-time` test override와 bounded fixture wait를 함께 사용한다. CI의 해당 Gradle test job은 `timeout-minutes: 25` 상한과 `--no-daemon --console=plain` 로그를 적용하고, 상한 초과 시 `--info --stacktrace` 재현 로그를 남겨 hang 원인을 조사한다. 예기치 않은 blocking을 숨기기 위해 timeout을 무제한으로 늘리거나 unmanaged test thread를 만들지 않는다.

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

- [ ] 다음 계약을 그대로 구현한다.

  ```kotlin
  @Configuration(proxyBeanMethods = false)
  @Profile("scheduled-policy")
  @EnableScheduling
  class TenantScheduledPolicyConfiguration {
      @Bean
      fun tenantScheduledPolicyFixture(): TenantScheduledPolicyFixture =
          TenantScheduledPolicyFixture()
  }
  ```

- [ ] configuration은 scheduler engine, executor, backend, registry, BPP를 직접 생성하지 않는다.
- [ ] bean method가 반환하는 fixture의 이름이 YAML selector의 `tenantScheduledPolicyFixture`와 일치하는지 bean definition test로 고정한다.

### 3B. main-source fixture

- [ ] strict method-shape validator와 CTW 대상이 되도록 `open class`와 `open fun reconcile()`을 사용한다. 이 `open`은 Spring runtime proxy를 켜기 위한 것이 아니라 upstream strict validation과 main-source weaving contract를 만족하기 위한 것이다.
- [ ] scheduled method는 다음 annotation을 사용한다.

  ```kotlin
  @Scheduled(fixedDelay = 5_000, initialDelay = 60_000)
  open fun reconcile() {
      invocations.incrementAndGet()
  }
  ```

- [ ] invocation count는 `AtomicInteger`로 저장하고 읽기 메서드만 제공한다. fixture는 별도 thread, executor, sleep, network, database를 만들지 않는다.
- [ ] KDoc은 한국어로 작성하고 “첫 자동 실행은 최대 60초 뒤”, “min lease floor가 동기 호출을 추가로 지연할 수 있음”, “local factory는 distributed ownership 증명이 아님”을 명시한다.

## 작업 4 — profile YAML과 실제 resource loading 고정

**파일:** `leader/tenant-scheduler/src/main/resources/application-scheduled-policy.yml`

- [ ] 다음 YAML을 그대로 추가한다. key 이름과 duration 표기는 upstream `LeaderScheduledPolicyProperties` contract에 맞춘다.

  ```yaml
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
      observability:
        tracing:
          enabled: true
          include-lock-name: false
          include-leader-id: false
          include-exception-details: false
  ```

- [ ] 기본 `src/main/resources/application.yml`에는 `scheduling.enabled` 또는 policy list를 추가하지 않는다.
- [ ] `@ActiveProfiles("scheduled-policy")` context가 `ConfigDataApplicationContextInitializer`를 통해 이 파일을 읽고, 정상 profile test의 inline property가 selector·name·duration을 대체하지 않는지 확인한다.
- [ ] profile resource가 test/runtime classpath에 패키징되는 것은 `@SpringBootTest` startup/close와 `processResources` 출력으로 증명한다.

## 작업 5 — green 검증과 기존 회귀 보호

**파일:** 작업 2의 두 test와 필요 시 동일 패키지의 명시 annotation/overload 보조 fixture test 파일

- [ ] 작업 2의 red assertion을 production configuration, fixture, YAML과 연결해 green으로 만든다.
- [ ] `@SpringBootTest`와 `ConfigDataApplicationContextInitializer`의 profile startup이 `TenantSchedulerLabAppKt` main class와 component scan을 통해 실제 main fixture와 packaged YAML을 찾는지 확인한다.
- [ ] direct CTW smoke에서 `min-lease-time=0s` override가 production resource 자체를 변경하지 않는지 확인하고, `assertTimeout(Duration.ofSeconds(5))`에 걸리면 lock/AspectJ weaving/classpath 원인을 조사한다. timeout을 늘려 문제를 숨기지 않는다.
- [ ] leader-aspect observation은 context bean 직접 호출로 확인할 수 있는 upstream observation contract만 사용한다. scheduler wrapper observation을 별도 주장하려면 upstream API와 측정 지점을 먼저 확인하고, 현재 사양 범위를 넘는 fixture/metric을 추가하지 않는다.
- [ ] lifecycle의 immediate-trigger fixture는 Spring scheduler wrapper가 실제 callback을 실행하는지만 확인하고 leader-aspect observation 또는 production policy 적용의 증거로 재사용하지 않는다. production policy의 leader-aspect/cache 증거는 main-source direct-call smoke가 담당한다.
- [ ] lifecycle test에서 context close 전후 task 수와 pending cancellation을 증명한다. custom executor/thread assertion은 fixture 구현의 static inspection과 context bean 목록으로 보조한다.
- [ ] 기본 profile을 대상으로 기존 19개 reducer 및 `TenantSchedulerReadmeSnippetTest`를 변경 없이 실행한다.

검증 명령:

```bash
./gradlew :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyContextTest*' --no-daemon --console=plain
./gradlew :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyLifecycleTest*' --no-daemon --console=plain
./gradlew :leader-tenant-scheduler:test --no-daemon --console=plain
```

실패 시 assertion을 약화하거나 timeout을 제거하지 않고, Spring profile/resource loading, AspectJ CTW task ordering, upstream condition, lease-floor 대기 원인을 출력과 함께 수정한다.

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

- [ ] 영어와 한국어 README는 같은 heading 순서, code fence 수, 명령, YAML key, selector, 숫자를 유지한다. 독자-facing 설명은 각 locale로 자연스럽게 작성하고 기계적 직역을 피한다.
- [ ] 두 README의 `Dependencies` 예제에는 복사 가능한 versionless consumer wiring을 함께 둔다: `implementation(libs.bluetape4k.leader.spring.boot)`, `implementation(libs.bluetape4k.leader.micrometer)`, `alias(libs.plugins.aspectj.post.compile.weaving)`. 개별 artifact version이나 별도 BOM은 예제에 넣지 않는다. 같은 표에서 `include-lock-name=false`, `include-exception-details=false`, trusted static-name logger 경계를 함께 안내한다.
- [ ] 두 파일에 다음 실행 경로를 추가한다.

  ```bash
  ./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy'
  ```

- [ ] profile YAML의 exact selector `tenantScheduledPolicyFixture#reconcile`, `localLeaderElectionFactory`, `SKIP`, `wait-time/lease-time/min-lease-time`, static bounded non-PII `name`을 설명한다.
- [ ] `io.freefair.aspectj.post-compile-weaving`은 CTW 경로이고 `@EnableAspectJAutoProxy`를 추가하지 않는다는 점을 설명한다. `spring.aop.auto=false`와 `bluetape4k.leader.aop.enabled=false`가 각각 runtime proxy/leader factory 조건에 미치는 차이를 표로 고정한다.
- [ ] `fixedDelay=5s`와 local `min-lease-time=5s` 조합은 본문이 즉시 끝나도 완료 간격이 대략 10초 이상이 될 수 있고 기본 scheduler thread를 lease floor가 잠시 점유할 수 있음을 설명한다. 이 단일 프로세스 local example을 다중 인스턴스 shared scheduler 설정으로 복사하지 않도록 경고한다.
- [ ] `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 property policy보다 우선하고, empty/malformed/duplicate/unmatched/overload/invalid duration이 startup에서 실패한다는 규칙을 기록한다.
- [ ] Spring이 task/trigger/Observation/close를 소유하고 fixture는 executor/thread를 만들지 않음을 설명한다. context close가 pending task를 취소하지만 in-flight body interrupt는 보장하지 않는 경계를 적는다. Observation handler registration 정리 또한 upstream recorder/coordinator lifecycle의 책임이므로 consumer 수명주기 acceptance에서는 `N/A (upstream delegated)`로 명시한다.
- [ ] local factory는 외부 backend/Docker 없이 wiring을 학습하기 위한 단일 프로세스 예제이며 distributed ownership 증명이 아니라는 제한을 적는다.
- [ ] `application-scheduled-policy.yml`은 profile 파일이지만 외부 `bluetape4k.leader.scheduling.enabled=true` property가 기본 profile을 덮어쓰면 profile 격리를 우회할 수 있음을 경고한다. 운영 설정에서 이 key를 별도 관리하고, 실수로 활성화했을 때는 rollback 전에 외부 override를 제거해야 한다.
- [ ] Observation recorder가 제공하는 이름 `leader.aop.acquire`, `leader.aop.execution`, outcome `acquired/success`, lock tag 기본 redaction(`redacted-lock`)을 설명한다. production YAML의 `include-lock-name`·`include-exception-details` 기본값은 false이고, 테스트가 trusted override로 `include-lock-name=true`를 켜도 `REDACT`가 raw name을 노출하지 않음을 함께 적는다. logger의 raw trusted static name 출력과 dynamic PII/throwable detail 차단은 별도 경계로 설명한다.
- [ ] `FAIL_OPEN_RUN`은 backend 오류 시 lock 없이 실행되어 중복 실행될 수 있으므로 멱등 작업의 trusted deployment override로만 사용하고, 예제 기본값은 `SKIP`임을 기록한다.
- [ ] 기동 성공 신호 `Started TenantSchedulerLabAppKt`, 첫 자동 실행 전 최대 60초, `min-lease-time`에 따른 추가 지연, `Ctrl-C` 종료를 안내한다. 사용자가 실제 callback을 기다리지 않고도 확인할 수 있도록 테스트 명령에서 `leader.aop.acquire`/`leader.aop.execution` 기록의 `outcome=acquired/success`와 `lock.name=redacted-lock`을 확인하는 경로를 함께 제공하고, 실행 로그에는 static `tenant-scheduler:reconcile`만 허용한다.
- [ ] deterministic 검증 명령과 기대 결과를 추가한다.

  ```bash
  ./gradlew :leader-tenant-scheduler:test --tests "*TenantScheduledPolicy*"
  ```

  기대 결과는 신규 context/lifecycle test와 기존 module test 전체가 통과하는 것이다.
- [ ] rollback 문구는 외부 `bluetape4k.leader.scheduling.enabled=true` override 제거 → `scheduled-policy` profile 비활성화 → profile YAML·configuration·fixture와 CTW/dependency 변경 rollback → 재기동 → `tenantScheduledPolicyFixture`, `ScheduledTaskHolder`, registry/BPP가 없는지 확인하는 순서를 사용한다. 외부 override가 남은 채 profile만 끄거나 `bluetape4k.leader.scheduling.enabled=false`만 단독으로 적용하는 것은 안전한 rollback이 아니라고 명시한다.

### 6B. root README와 coverage

- [ ] `README.md`와 `README.ko.md`의 tenant scheduler 표/설명을 `leader-core`만 사용하는 예제가 아니라 versionless `leader-spring-boot`와 `scheduled-policy` profile을 함께 제공하는 예제로 갱신한다.
- [ ] root README pair에 module test 명령과 profile 실행 경로를 추가하되 기존 module 목록과 명령의 의미를 바꾸지 않는다.
- [ ] `docs/coverage-matrix.md`에 `bluetape4k-leader-spring-boot`, YAML scheduled policy, CTW, lifecycle, Issue #869 coverage row를 추가하거나 기존 tenant scheduler row를 갱신한다.

### 6C. narrow stale guard

- [ ] `scripts/smoke-validate.sh stale-check`에 다음 조건만 추가한다.
  - `leader/tenant-scheduler/src/main/resources/application-scheduled-policy.yml` 파일이 존재한다.
  - YAML에 exact selector `tenantScheduledPolicyFixture#reconcile`가 있다.
  - `TenantScheduledPolicyConfiguration.kt`가 `@Profile("scheduled-policy")`, `@EnableScheduling`, bean 이름을 포함한다.
  - `leader/tenant-scheduler/README.md`와 `README.ko.md`에 `--spring.profiles.active=scheduled-policy`가 있다.
  - 두 README의 dependency/plugin snippet에 `bluetape4k.leader.spring.boot`, `bluetape4k.leader.micrometer`, `aspectj.post.compile.weaving` alias가 있다.
- [ ] 실패 메시지는 어떤 파일/문자열이 stale인지 한 줄로 보여 주고, 기존 project count/stale ref/required module/leader diagnostics/image link guard를 변경하지 않는다.
- [ ] `.github/workflows/Examples.yml`에는 이미 `leader/tenant-scheduler`가 push/PR path, smoke test, artifact에 등록되어 있으므로 해당 등록은 중복 추가하지 않는다. 대신 `README and stale contract guards` job을 추가해 `timeout-minutes: 10`, `runs-on: ubuntu-latest`, `actions/checkout@v4`, `actions/setup-java@v4`의 Java 25 Temurin 설정, `gradle/actions/setup-gradle@v4`와 repository Gradle wrapper를 준비한 뒤 `node scripts/validate-readme-parity.mjs leader/tenant-scheduler`와 `bash scripts/smoke-validate.sh stale-check`를 실행한다. `changes`가 `diagram`, `examples`, `gradle` 중 하나를 true로 판정한 PR/push에서만 실행하며, 이 job이 실패하면 PR check가 실패하도록 `needs: changes`, `if: always() && needs.changes.result == 'success' && (needs.changes.outputs.diagram == 'true' || needs.changes.outputs.examples == 'true' || needs.changes.outputs.gradle == 'true')`를 사용한다.
- [ ] architecture/sequence diagram은 reducer와 Spring integration을 혼합하지 않으므로 수정하지 않는다. diagram N/A를 review artifact에 기록한다.

검증 명령:

```bash
node scripts/validate-readme-parity.mjs leader/tenant-scheduler
bash scripts/smoke-validate.sh stale-check
```

## 작업 7 — 전체 검증, 문서 품질, lesson

**파일:**

- `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`
- 작업 1~6의 모든 변경 파일

- [ ] 다음 검증을 새 프로세스에서 실행하고 출력/exit code를 기록한다.

  ```bash
  ./gradlew :leader-tenant-scheduler:compileKotlin --no-daemon --console=plain
  ./gradlew :leader-tenant-scheduler:test --no-daemon --console=plain
  ./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:test --no-build-cache --no-daemon --console=plain
  ./gradlew projects --no-daemon --console=plain
  node scripts/validate-readme-parity.mjs leader/tenant-scheduler
  bash scripts/smoke-validate.sh stale-check
  git diff --check
  ```

- [ ] 필요 시 root compile smoke는 `./scripts/smoke-validate.sh compile`로 실행하되, container-backed full suite를 실행했다면 Docker socket/Colima 상태와 실제 결과를 기록한다. skipped test를 pass로 보고하지 않는다.
- [ ] `gradle/libs.versions.toml`에 explicit bluetape version pin 또는 individual BOM이 생기지 않았는지 `rg`로 확인한다.
- [ ] buildEnvironment/dependencyInsight에서 기록한 plugin marker·AspectJ·Bluetape component provenance와 `2.0.0-SNAPSHOT` 해석 결과를 lesson에 남긴다. checksum metadata가 없는 상태는 숨기지 않고 후속 공급망 hardening 항목으로 분리한다.
- [ ] `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs`를 모든 변경된 한국어 문서와 README에 실행하고 `findings=0`을 확인한다.
- [ ] placeholder/temporary marker 검사는 미완료 표식 검색으로 실행하고 결과가 없어야 한다.
- [ ] `docs/lessons/2026-08-31-issue-869-scheduled-policy.md`를 한국어로 작성한다. 최소 항목은 Context, Decision, Outcome, Verification, Miss/Surprise, Future guard이며, CTW main sourceSet 요구, lease-floor로 인한 동기 호출 지연, scheduler-level Observation을 direct call acceptance에서 제외한 이유, stale guard와 README parity를 기록한다.
- [ ] lesson은 구현·검증 증거가 생긴 뒤 추가하고, spec/plan review에서 이미 확정한 내용을 복사하지 말고 실제 명령 결과와 surprise를 중심으로 작성한다.

## 작업 8 — 계획/구현 커밋과 handoff

- [ ] 이 계획과 plan review artifact는 사용자 계획 승인 전 별도 커밋으로 고정한다. 계획 커밋 제목은 `[2.0.0] Issue #869 구현 계획을 고정한다`로 하고 Lore trailers를 포함한다.
- [ ] 구현 커밋은 기능 단위로 작게 나누고 각 commit message에 Korean intent line과 다음 trailers를 포함한다.

  ```text
  Constraint: consumer module은 root bluetape4k-dependencies BOM만 사용한다
  Rejected: runtime proxy와 custom scheduler는 CTW/Spring lifecycle 경계를 깨므로 제외했다
  Confidence: high
  Scope-risk: moderate
  Directive: profile 기본값과 exact selector를 변경할 때 README/stale guard/test를 함께 갱신한다
  Tested: <실행한 명령과 핵심 결과>
  Not-tested: <실행하지 못한 항목과 사유 또는 none>
  ```

- [ ] 구현 완료 후 `docs/lessons/...`와 tracked review artifact를 포함해 feature branch가 clean인지 확인한다.
- [ ] PR 생성 시 base `develop`, head `feat/issue-869-scheduled-policy`, title `[2.0.0] Issue #869 ...`, body Korean, `Closes #869`, DoD Status, 테스트/CI 증거를 사용한다. PR 생성은 이 task의 구현 검증이 끝난 뒤 실행한다.
- [ ] PR 생성 후 live CI/checks와 review thread를 확인한다. merge는 exact live head, CI green, review 상태를 다시 읽고 새 `승인`이 들어온 뒤에만 수행한다. 이 계획 단계에서는 merge/auto-merge/tag/release를 실행하지 않는다.

## 의존성 순서와 재실행 규칙

1. 작업 1의 catalog/plugin/classpath가 통과해야 작업 2 red test를 실행한다.
2. 작업 2 red 계약을 기록한 뒤 작업 3 main fixture/configuration과 작업 4 YAML을 구현한다.
3. 작업 5 green 검증을 통과해야 작업 6 문서/guard를 갱신한다.
4. 작업 6 parity/stale guard가 통과해야 작업 7 전체 검증과 lesson을 작성한다.
5. 작업 7의 모든 required check가 통과하고 review artifact가 최신화된 뒤 구현 commit/PR을 만든다.
6. Gradle daemon/cache 오류가 발생하면 같은 명령을 `--no-daemon --console=plain`으로 재실행하고, 동일 실패가 계속되면 repo 변경과 환경 문제를 분리해 기록한다. assertion을 삭제하거나 timeout을 무제한으로 늘려 green을 만들지 않는다.
7. profile task가 startup에서 실패하면 YAML binding → selector registry → AspectJ weave/task ordering → local factory/lease floor 순서로 진단한다. 외부 backend를 추가해 해결하지 않는다.

## 수용 기준 추적표

| 수용 기준 | 구현 task | 검증 증거 |
|---|---|---|
| BOM 기반 versionless `leader-spring-boot` classpath | 1 | dependencies/compileKotlin 출력 |
| Freefair CTW와 runtime proxy 비사용 | 1, 3, 5 | compile output, `internalAutoProxyCreator` 부재, direct main-source invocation |
| 기본 profile 비활성 | 2, 3, 5 | default context bean/task assertions, 기존 19개 test |
| exact selector와 task 1개 | 2, 4, 5 | YAML binding, registry lookup, `ScheduledTaskHolder` count |
| open main fixture와 local factory/leader-aspect observation | 3, 5 | `@SpringBootTest`, `assertTimeout(5s)`, observation/factory assertions |
| explicit annotation precedence | 2, 5 | 보조 fixture와 registry `markObserved`/binding assertions |
| fail-fast invalid configurations | 2, 5 | ApplicationContextRunner failure assertions |
| context close cancellation/no custom thread | 2, 5 | lifecycle holder count 1→0, static/context inspection |
| AOP opt-out distinction와 `FAIL_OPEN_RUN` 경계 | 2, 5, 6 | override contexts, README pair, negative assertion |
| static bounded non-PII/SpEL/REDACT | 2, 4, 5, 6 | property assertions, README pair, metric/name negative checks; dynamic SpEL/placeholder execution is upstream delegated |
| existing reducer/README tests | 5, 7 | module full test output |
| README locale parity와 실행 안내 | 6, 7 | `validate-readme-parity.mjs`, Korean audit |
| stale guard와 기존 workflow 등록 | 6, 7 | `smoke-validate.sh stale-check`, workflow path inspection |

## 계획 단계 DoD 및 승인 게이트

- [ ] 계획 문서가 실제 path, exact code/YAML/property, command, expected result, rollback, failure recovery를 포함한다.
- [ ] `SPW-01` 문제/독자/목표, `SPW-02` current-state/evidence, `SPW-03` implementation detail, `SPW-04` tests/verification, `SPW-05` DoD/rollback을 모두 계획에 매핑한다.
- [ ] 한국어 문서 품질 검토 `KO-01`~`KO-07`와 six-perspective Step 3-R plan review가 기록된다.
- [ ] Step 3-R conditional 항목 중 coroutine/suspend, 새 module settings 등록, Exposed import/receiver, JDK preview, external backend capability는 이 issue 범위에 해당하지 않음을 review artifact에 `N/A`로 남기고, 해당하는 auto-configuration ordering, resource ownership/close, blocking lease-floor, stale guard, rollback 항목은 작업 1~7의 구체적인 assertion/명령으로 덮는다.
- [ ] six lanes와 main integration의 최신 결과가 `P0=0`, `P1=0`이다. P2/P3는 disposition과 구현 task가 추적된다.
- [ ] 계획과 plan review가 Lore commit으로 고정되고, helper required-checks에는 `spec`과 `plan` 증거가 등록된다. `tests`, `docs`, `pr`와 main verification은 구현 후에만 pending에서 해소된다.
- [ ] 이 문서와 review artifact를 사용자가 검토할 수 있도록 commit SHA와 파일 링크를 handoff에 제시한다.

이 계획 단계의 최종 상태는 사용자의 계획 승인 전까지 `PENDING (plan approval required)`이다. 계획 승인이 확인되면 작업 1부터 순서대로 TDD 구현을 시작한다.
