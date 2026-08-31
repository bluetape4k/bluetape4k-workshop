# Issue #869 설계 사양서: YAML scheduled policy를 tenant scheduler 예제에 적용

## 메타데이터

- 이슈: [#869](https://github.com/bluetape4k/bluetape4k-workshop/issues/869)
- 제목: `[2.0.0] 기존 leader/tenant-scheduler에 YAML scheduled policy 적용`
- 대상 모듈: `leader/tenant-scheduler` (`:leader-tenant-scheduler`)
- 작업 브랜치: `feat/issue-869-scheduled-policy`
- 독자: workshop 예제를 따라 하면서 Spring Boot scheduling과 leader policy의 경계를 확인하는 Kotlin 개발자
- 문서 언어: 한국어. 코드, 명령, API 이름, 식별자, URL은 원문을 보존한다.

## 문제와 목표

현재 모듈은 `TenantSchedulerLab`의 logical-tick reducer만 실행한다. 이 구조는 tenant별 lock 이름, 공정성, stale handoff, bounded metric tag를 결정론적으로 설명하지만, 실제 Spring `@Scheduled` 메서드에 YAML로 leader 정책을 연결하는 2.0.0-SNAPSHOT의 신규 기능은 보여 주지 않는다. 독자는 reducer 예제와 production-facing scheduling integration 사이의 연결을 별도 모듈에서 다시 추론해야 한다.

이 변경의 목표는 기존 reducer의 의미를 바꾸지 않고, 저장소가 제공하는 `scheduled-policy` profile에서 다음 흐름을 실행 가능한 예제로 제공하는 것이다. upstream 자동설정은 profile이 아니라 `bluetape4k.leader.scheduling.enabled` property로 opt-in된다는 조건도 함께 드러낸다.

1. `@Scheduled` 메서드 `tenantScheduledPolicyFixture#reconcile`를 선언한다.
2. `bluetape4k.leader.scheduling` YAML policy가 exact selector와 local leader factory를 연결한다.
3. upstream Spring Boot 자동설정이 policy registry와 BPP를 구성하고, Spring이 scheduled task와 close lifecycle을 소유한다.
4. 기본 profile에서는 registry, policy BPP, background task가 생성되지 않아 기존 결정론 경로가 그대로 유지된다.

## 현재 근거와 출처 원장

| 근거 | 확인한 내용 |
|---|---|
| `leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/service/TenantSchedulerLab.kt` 및 domain 파일 | reducer는 logical tick 입력만 처리하며 backend나 scheduler를 시작하지 않는다. |
| `leader/tenant-scheduler/src/test/...` | 기존 테스트는 19개이며 reducer, 식별자, lock name, metric tag, README snippet을 검증한다. |
| `leader/tenant-scheduler/build.gradle.kts` | `leader-core`와 Spring Boot 기본 모듈만 사용하며 `leader-spring-boot` alias는 아직 선언하지 않는다. |
| `gradle/libs.versions.toml:5,143-144,180,228` | `bluetape4k-dependencies-version = "2.0.0-SNAPSHOT"`이고 `bluetape4k-leader-spring-boot`는 BOM이 버전을 결정하는 versionless alias다. AspectJ CTW plugin alias를 추가할 위치도 확인했다. |
| upstream `bluetape4k-leader/leader-spring-boot/.../LeaderScheduledPolicyProperties.kt` | 실제 prefix는 `bluetape4k.leader.scheduling`; policy 필드는 `selector`, `name`, `waitTime`, `leaseTime`, `minLeaseTime`, `bean`, `autoExtend`, `streamBounded`, `failureMode`다. |
| upstream `.../LeaderScheduledPolicyRegistry.kt` | selector는 공백 없는 정확한 `beanName#methodName`이어야 하며, duplicate·overload·unmatched selector는 startup에서 실패한다. registry는 scheduler나 Observation을 직접 만들지 않는다. |
| upstream `.../LeaderScheduledPolicyBeanPostProcessor.kt` | plain `@Scheduled` 메서드에만 property policy를 적용하고, `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 있으면 annotation을 우선한다. singleton 초기화 후 registry를 freeze한다. |
| upstream `.../LeaderScheduledPolicyAutoConfiguration.kt` 및 `LeaderAopFactoryAutoConfiguration.kt` | policy 자동설정은 AOP factory 뒤, AOP 적용 앞에 오며 `localLeaderElectionFactory`를 기본 local backend로 제공한다. `enabled`가 기본 `false`라 opt-in이다. |
| upstream `leader-spring-boot` 테스트 `LeaderScheduledPolicyAutoConfigurationTest.kt`, `LeaderScheduledTaskLifecycleTest.kt` | `ApplicationContextRunner`, `ScheduledTaskHolder`, context close, observation 수로 binding과 lifecycle을 검증하는 패턴이 이미 있다. |
| `leader/tenant-scheduler/README.md`, `README.ko.md`, root `README.md`, `README.ko.md`, `docs/coverage-matrix.md`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh` | 현재 README와 coverage row는 reducer와 `leader-core`만 설명하고, 모듈 test는 smoke/full workflow에 이미 등록되어 있다. dependency 설명과 coverage gap은 갱신하되 구조 변경이 없으므로 diagram 파일은 유지한다. |

## 제약과 범위

### 포함 범위

- `leader/tenant-scheduler`의 versionless `bluetape4k-leader-spring-boot` consumer dependency 추가
- `scheduled-policy` profile 전용 Spring configuration, fixture, YAML
- YAML binding, exact selector, annotation precedence, fail-fast, task lifecycle을 검증하는 context test
- 영어/한국어 module README의 실행법, YAML, selector 규칙, lifecycle 경계 갱신
- 사양서, 구현 계획, review artifact, lesson 및 기존 workflow/coverage 등록의 필요성 점검

### 제외 범위

- Redis, ZooKeeper, Kubernetes, DynamoDB 등 외부 backend 연결
- 별도 scheduler, executor, trigger, retry, hot reload 구현
- 기존 `TenantSchedulerLab` reducer 또는 domain 모델의 동작 변경
- 개별 bluetape4k artifact 버전 pinning, 추가 BOM import, 기존 Bluetape catalog alias 재정의
- 기존 architecture/sequence diagram의 내용 변경
- 기본 profile에서 background scheduler를 켜는 동작

### 호환성·안전 제약

- 버전 관리는 root의 `bluetape4k-dependencies` BOM만 사용한다. module build script에는 version을 쓰지 않는다.
- 저장소가 제공하는 `application-scheduled-policy.yml`에서만 `bluetape4k.leader.scheduling.enabled=true`를 선언한다. upstream 자동설정 자체는 profile 조건이 아니라 이 property의 opt-in 조건으로 동작하므로, 외부 설정이 property를 true로 덮으면 다른 profile에서도 활성화될 수 있다는 점을 문서화한다.
- 예제는 local factory를 사용하므로 Docker와 외부 자격 증명이 필요 없다. 이것은 distributed ownership 증명이 아니라 Spring integration wiring 학습용이다.
- Spring이 task 등록, trigger, executor, Observation, context close를 소유한다. 예제 코드는 이 lifecycle을 중복 생성하거나 닫지 않는다.
- tenant alias와 policy name에는 PII나 raw customer identifier를 넣지 않는다.

## 선택한 설계

### 1. 의존성 경로

`leader/tenant-scheduler/build.gradle.kts`에 다음 versionless alias를 추가한다.

```kotlin
implementation(libs.bluetape4k.leader.spring.boot)
```

alias는 이미 `gradle/libs.versions.toml`에 있고, root BOM이 `2.0.0-SNAPSHOT` 버전을 공급한다. `leader/backend-comparison-lab`이 같은 alias를 사용하는 현재 저장소 패턴도 확인했다.

동일 module의 `plugins`에는 `io.freefair.aspectj.post-compile-weaving` alias도 적용한다. 이 플러그인은 upstream이 사용하는 compile-time weaving(CTW) 경로이며, workshop catalog에 plugin version `9.5.0`과 versioned plugin alias를 추가한다. `@EnableAspectJAutoProxy`는 적용하지 않는다. 이 예제의 `@Scheduled` execution pointcut은 실제 main sourceSet의 woven bytecode를 통과해야 하므로, dependency/classpath와 별개로 weaving 결과를 invocation test에서 확인한다. 이 plugin version은 Bluetape4k library version pinning이 아니다.

### 2. profile 격리

`leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyConfiguration.kt`에 `TenantScheduledPolicyConfiguration`을 `@Profile("scheduled-policy")`, `@Configuration(proxyBeanMethods = false)`, `@EnableScheduling`으로 만든다. configuration은 bean 이름이 `tenantScheduledPolicyFixture`인 main-source fixture를 하나 제공한다. AspectJ CTW 자체는 Kotlin final class/method도 weaving하지만 upstream의 `strict=true` method-shape validator가 final method를 경고/실패로 분류하므로 fixture는 `open class`와 `open fun reconcile()`으로 선언해 이 예제의 fail-fast 경계를 통과한다. 이것은 Spring runtime proxy를 활성화하기 위한 설정이 아니다. `reconcile`은 `@Scheduled(fixedDelay = 5_000, initialDelay = 60_000)`인 plain scheduled method로 남겨 property policy가 실제로 적용되는 경로를 보인다. YAML의 `min-lease-time: 5s`를 local elector가 보장하는 동안 호출이 대기할 수 있으므로 유효한 시작 간격은 fixed delay보다 길어질 수 있으며, README와 테스트에서 이 blocking 경계를 설명한다. 긴 `initialDelay`는 예제 기동 직후의 비결정적인 반복 실행을 막는다.

`src/main/resources/application-scheduled-policy.yml`은 profile 실행 때만 읽히며 다음 계약을 사용한다.

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
```

기본 `application.yml`에는 scheduling enable 값을 넣지 않는다. 따라서 일반 `bootRun`과 기존 test는 background task 없이 유지된다. 저장소 제공 profile 예제를 직접 실행할 때는 다음 명령을 사용한다.

```bash
./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy'
```

### 3. upstream 자동설정과 실행 경계

자동설정은 다음 순서로 사용한다.

```text
@Scheduled fixture
    │
    ├─ Spring SchedulingPostProcessor → Spring ScheduledTaskRegistrar
    │                                      └─ task/trigger/close lifecycle
    │
    └─ LeaderScheduledPolicyBeanPostProcessor
          └─ exact selector → LeaderScheduledPolicyRegistry
                              └─ LeaderElectionAspect가 policy lookup
```

workshop 모듈은 `LeaderScheduledPolicyRegistry`, BPP, AOP aspect, local factory를 복제하지 않는다. `leader-spring-boot` 자동설정 import ordering과 local factory를 그대로 소비한다. scheduler engine을 직접 만들지 않는 것이 이 예제의 핵심 경계다.

### 4. selector와 우선순위 계약

- selector는 bean 이름과 method 이름을 `#` 하나로 연결한 exact 값이다. 앞뒤 공백, 내부 whitespace, `#` 중복, 빈 이름은 허용하지 않는다.
- `enabled=true`인데 policy 목록이 비어 있으면 context startup이 실패한다.
- configured selector가 `@Scheduled` 메서드와 매칭되지 않거나 overloaded method를 모호하게 가리키면 startup이 실패한다.
- plain `@Scheduled`에는 YAML policy가 적용된다.
- 같은 메서드에 `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`가 있으면 명시적 annotation이 property policy보다 우선한다. selector는 observed로 처리하여 불필요한 unmatched failure를 만들지 않는다.
- YAML duration의 문법 오류(예: 해석할 수 없는 문자열)는 binding 단계에서 항상 startup failure가 된다.
- plain `@Scheduled` 메서드에 실제로 적용되는 policy의 `wait-time`은 0 이상, `lease-time`은 양수, `min-lease-time`은 0 이상이며 lease-time 이하이어야 한다. `name`은 비어 있지 않아야 한다.
- 같은 메서드에 명시적 annotation이 있으면 property 값은 사용하지 않고 annotation 검증·실행 경로를 따른다. 이 경우 policy selector는 `markObserved`되어 unmatched 오류를 피하지만, 사용하지 않는 property의 name/duration semantic 검증은 수행하지 않는다. 명시적 annotation 자체의 검증은 upstream validator 계약으로 고정한다.

CTW 실행 모델에서는 `spring.aop.auto=false`가 runtime proxy만 끄며 woven leader aspect에는 영향을 주지 않는다. 반대로 `bluetape4k.leader.aop.enabled=false`이면 factory 조건이 꺼져 policy registry/BPP와 aspect bean이 구성되지 않을 수 있고, profile configuration의 Spring scheduled task만 남는다. 이 경우 task가 leader ownership 없이 실행될 수 있으므로 README에 단일 프로세스 학습용 opt-out 경계를 경고하고 context test에서 `internalAutoProxyCreator` 부재, registry/task 조건, 실제 woven invocation의 차이를 각각 확인한다. 기본 profile과 `scheduled-policy` profile은 이 값을 끄지 않는다.

### 5. 테스트 구성

`leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyContextTest.kt`와 `TenantScheduledPolicyLifecycleTest.kt`를 추가하고, upstream 테스트와 같은 `ApplicationContextRunner` 패턴을 사용한다. profile binding 테스트는 `ConfigDataApplicationContextInitializer`와 `spring.profiles.active=scheduled-policy`를 사용해 실제 `application-scheduled-policy.yml`을 읽는다. inline property는 malformed/edge-case와 failure assertion에만 사용해 파일과 테스트 계약이 서로 우회되지 않게 한다. CTW invocation은 test sourceSet fixture로 흉내 내지 않고 `open`으로 선언한 main-source `tenantScheduledPolicyFixture`를 실제 context에서 호출하는 `@SpringBootTest` smoke test로 검증한다. CTW smoke 호출에는 테스트 전용 `bluetape4k.leader.scheduling.policies[0].min-lease-time=0s` override를 주어 production YAML의 5초 lease floor가 호출자 스레드를 불필요하게 막지 않게 하고, 호출 자체도 `assertTimeout(Duration.ofSeconds(5))`로 상한을 둔다. 이 테스트는 runtime proxy 성공 여부가 아니라 `org.springframework.aop.config.internalAutoProxyCreator` 빈 부재와 woven invocation의 leader factory/leader-aspect observation 경로를 확인하며, `@EnableAspectJAutoProxy`를 사용하지 않는다. 직접 bean 호출은 Spring `ScheduledMethodRunnable` wrapper를 우회하므로 Spring scheduler-level Observation을 이 smoke의 성공 조건으로 주장하지 않는다. scheduler task 등록과 close lifecycle은 별도 `ScheduledTaskHolder` 검증으로 고정한다. `ApplicationContextRunner`는 registry/task binding과 조건부 auto-configuration 검증에 한정한다. overload method의 registry signature 검증은 별도 unit test로 두어 Spring의 “scheduled method는 인자를 받지 않는다” 검증과 policy selector 모호성 검증을 구분한다.

| 검증 | 기대 결과 |
|---|---|
| 기본 또는 `scheduled-policy` 미활성 | registry/BPP가 없고 기존 19개 reducer test가 그대로 통과한다. |
| 유효한 YAML + exact selector | context startup 성공, registry/BPP 존재, `ScheduledTaskHolder` task가 정확히 1개다. |
| `@LeaderScheduled`가 이미 있는 fixture 메서드 | property 값이 덮어쓰지 않고 명시적 annotation이 유지된다. |
| 빈 policy, malformed selector, unmatched selector, duplicate selector, invalid duration/lease | startup failure가 발생하고 메시지에 selector 또는 property 경계가 포함된다. |
| context close | 아직 실행되지 않은 task가 `ScheduledTaskHolder.scheduledTasks`에서 제거되어 취소된다. fixture가 별도 executor나 thread를 만들지 않는다. 이미 실행 중인 메서드 body의 interrupt/즉시 중단은 Spring의 `cancel(false)` 계약상 보장하지 않으며 acceptance 대상에서 제외한다. |
| woven invocation + leader-aspect observation | main-source `tenantScheduledPolicyFixture.reconcile()`를 context bean에서 직접 한 번 호출해 woven leader aspect, local factory 선택, leader-aspect observation 경로가 동작함을 확인하고, `ScheduledTaskHolder` 등록은 정확히 1개로 유지한다. 테스트 전용 `min-lease-time=0s` override와 `assertTimeout(Duration.ofSeconds(5))`로 동기 호출의 실행 시간을 제한한다. 이 직접 호출은 Spring `ScheduledMethodRunnable` wrapper를 거치지 않으므로 scheduler-level Observation은 이 행의 acceptance 범위가 아니며, task 등록·trigger·close는 별도 lifecycle assertion으로 검증한다. context close와 fixture 상태 정리는 `finally`에서 수행한다. production fixture의 `initialDelay = 60_000` 때문에 자동 첫 tick을 기다리지 않아도 되며 직접 호출은 scheduler 재실행과 경쟁하지 않는다. |
| CTW/AOP 경계 | `spring.aop.auto=false`에서는 runtime proxy 설정과 무관하게 CTW leader aspect가 유지되고, `bluetape4k.leader.aop.enabled=false`에서는 factory/registry/aspect 조건이 빠져 task만 남을 수 있다. `internalAutoProxyCreator` 부재와 woven invocation을 main-source smoke test가 확인한다. |
| README snippet | 기존 `TenantSchedulerReadmeSnippetTest`가 계속 통과한다. |

테스트는 시간 경쟁을 피하도록 lifecycle fixture에 `initialDelay = 60_000`, `fixedDelay = 50`을 설정한다. 실제 scheduled method의 woven invocation smoke는 main-source fixture를 `@SpringBootTest` context bean에서 직접 한 번 호출하고, 테스트 전용 `min-lease-time=0s`와 `assertTimeout(Duration.ofSeconds(5))`로 local elector의 lease-floor blocking을 분리한다. 이 direct-call smoke는 leader aspect observation만 검증하고 Spring scheduler wrapper의 Observation을 검증한다고 표현하지 않는다. pending task 취소는 별도 lifecycle assertion으로 검증한다. `Thread.sleep` 기반 대기는 사용하지 않고 `CountDownLatch`와 `Duration.ofSeconds(5)` 이상의 bounded timeout을 사용하며, in-flight task가 context close로 interrupt된다고 가정하지 않는다. test sourceSet의 fixture는 CTW 대상이 아니므로 woven invocation 증거는 main sourceSet fixture와 실제 `@SpringBootTest` context에서만 수집한다. upstream `LeaderElectionAspect`의 target/method metadata cache와 immutable registry는 그대로 소비하며, consumer 예제가 매 tick마다 reflection scan·selector parsing·factory lookup을 새로 수행하지 않는다는 비회귀 근거를 테스트 설명에 남긴다.

## 고려했으나 거부한 대안

| 대안 | 거부 이유 |
|---|---|
| fixture에 `@LeaderScheduled`만 붙인다 | YAML scheduled policy의 신규 binding, exact selector, fail-fast 계약을 학습할 수 없다. annotation precedence 회귀용 보조 fixture로만 사용한다. |
| tenant scheduler 내부에 `ScheduledExecutorService`를 직접 추가한다 | Spring task/trigger/Observation/close lifecycle과 중복되고, 기본 profile의 결정론적 reducer 경계를 깨뜨린다. |
| YAML을 기본 profile에 항상 활성화한다 | 기존 smoke test가 background task와 시간 의존성을 갖게 되고, local-only lab의 안전한 기본값을 잃는다. |
| Redis backend를 예제에 연결한다 | Issue #869의 목표는 2.0.0-SNAPSHOT Spring integration 적용이며, 외부 인프라 추가는 기존 smoke/full workflow와 범위를 불필요하게 넓힌다. |

## 실패 모드와 대응

1. **selector 오타·overload**: registry freeze 또는 method binding 단계에서 startup failure를 발생시킨다. README에 정확한 `beanName#methodName` 규칙을 명시하고 테스트에서 selector를 오류 메시지로 확인한다.
2. **lease duration 오류**: malformed YAML duration은 항상 binding startup failure가 된다. plain `@Scheduled` policy에 사용되는 음수 wait, 0 또는 음수 lease, lease보다 큰 min lease는 startup에서 거부한다. 명시적 annotation이 우선하는 selector의 미사용 property semantic은 검증하지 않으며 annotation 자체의 validator가 책임진다. 잘못된 설정이 실행 중 조용히 skip되지 않게 한다.
3. **기본 profile의 accidental background task**: profile guard와 비활성 기본값을 함께 검사한다. default context에서 task holder가 신규 task를 갖지 않는 것을 acceptance로 둔다.
4. **context close 누수**: Spring이 등록한 task만 사용하고 close 후 pending scheduled task set이 비어 있는지 검증한다. custom executor/thread를 만들지 않으며 in-flight body의 중단은 보장하지 않는다.
5. **annotation precedence drift**: explicit annotation fixture에 property selector를 함께 제공하고, annotation 경로가 유지되는지 context test로 고정한다.
6. **CTW/AOP opt-out 오해**: `spring.aop.auto=false`는 CTW를 끄지 않지만 `bluetape4k.leader.aop.enabled=false`는 factory/registry/aspect를 조건부로 끌 수 있다. 두 설정의 task·registry·woven invocation 상태와 README 경고를 함께 검증한다.
7. **무잠금 실행 설정**: `failure-mode: FAIL_OPEN_RUN`은 backend 오류 시 lock 없이 본문을 실행해 여러 노드에서 중복 실행될 수 있다. 이 예제는 `SKIP`을 안전한 기본값으로 고정하고, `FAIL_OPEN_RUN`은 멱등 작업에만 허용되는 trusted deployment override라는 점을 README와 테스트 설명에 남긴다.
8. **식별자·표현식 노출**: `bean`과 `name`은 tenant/request 입력이 아닌 trusted deployment configuration이다. `name`은 정적이고 bounded한 non-PII 값만 사용하며, SpEL method invocation은 profile에서 끄고 lock-name metric tag는 `REDACT`로 고정한다. 로그·Observation·metric에 raw customer ID를 넣지 않는 규칙과 negative assertion을 둔다.

## README와 workflow 영향

- `leader/tenant-scheduler/README.ko.md`와 `README.md`에 `scheduled-policy` profile 실행 명령, YAML selector, local factory 의미, precedence/fail-fast/lifecycle 경계를 같은 구조로 추가한다. `io.freefair.aspectj.post-compile-weaving`이 실제 leader aspect를 적용하는 CTW 경로이며 `@EnableAspectJAutoProxy`를 추가하지 않는다는 점, `spring.aop.auto=false`/`bluetape4k.leader.aop.enabled=false` 경계, `FAIL_OPEN_RUN`의 멱등성 조건과 trusted `bean`/`name` 설정 경계를 함께 경고한다. 양쪽 README에는 공통으로 versionless dependency·JDK 25/Gradle 전제조건, profile 기동 성공 신호(`Started TenantSchedulerLabAppKt`), 첫 scheduled invocation 전 최대 60초 대기와 `min-lease-time`에 따른 추가 지연, `Ctrl-C` 종료 방법, deterministic 검증 명령 `./gradlew :leader-tenant-scheduler:test --tests "*TenantScheduledPolicy*"` 및 기대 결과(신규 context/lifecycle 테스트 통과)를 기록한다. `SKIP`/`RETHROW`/`FAIL_OPEN_RUN`의 의미와 이 local 예제가 backend 장애를 재현하지 않는다는 점도 짧은 표로 맞춘다. 설정을 되돌릴 때는 profile YAML/CTW plugin과 README 변경을 함께 제거하고, `bluetape4k.leader.scheduling.enabled`를 false로 복귀한 뒤 재기동해야 한다는 rollback 한 줄도 넣는다.
- 기존 architecture와 sequence diagram은 reducer와 Spring integration을 혼합하지 않으므로 변경하지 않는다.
- 모듈은 이미 `scripts/smoke-validate.sh`와 `.github/workflows/Examples.yml`에 등록되어 있다. 새 module registration은 필요 없지만, 변경된 profile test가 현재 Gradle task와 workflow artifact 경로에서 수집되는지 확인한다. `scripts/smoke-validate.sh stale-check`에 `application-scheduled-policy.yml`, `TenantScheduledPolicyConfiguration`, exact selector, 양쪽 README의 profile 명령이 함께 존재하는 좁은 stale guard를 추가하고 실행 결과를 기록한다. 별도 외부 프로세스 CI 단계 대신 `@SpringBootTest`의 `scheduled-policy` profile startup/close 테스트로 resource packaging·main class·profile scan을 검증한다.
- root `README.md`와 `README.ko.md`의 tenant scheduler dependency 표를 `leader-spring-boot` 사용 사실과 profile 실행 경로에 맞게 갱신한다.
- `docs/coverage-matrix.md`의 `bluetape4k-leader-spring-boot` row에 YAML scheduled policy coverage와 Issue #869를 기록한다. task 경로와 workflow 등록은 기존 값을 유지한다.

## 수용 기준

- [ ] BOM 기반 versionless `bluetape4k-leader-spring-boot` dependency가 compile/test classpath에 제공된다.
- [ ] `io.freefair.aspectj.post-compile-weaving` CTW plugin이 module에 적용되고, `@EnableAspectJAutoProxy` 없이 실제 scheduled invocation이 leader aspect를 통과한다.
- [ ] `scheduled-policy` profile을 명시하지 않은 default context에서 registry/BPP/background task가 생성되지 않는다.
- [ ] profile YAML의 `tenantScheduledPolicyFixture#reconcile`가 exact selector로 binding되고 task가 정확히 하나 등록된다.
- [ ] profile main fixture가 `strict=true` method-shape 검증을 통과하는 `open` class/method로 CTW 대상이 되고, runtime proxy 없이 실제 woven invocation·leader-aspect observation·`localLeaderElectionFactory` 선택을 `@SpringBootTest`가 확인한다. 이 smoke는 테스트 전용 `min-lease-time=0s`와 5초 실행 상한을 사용하며, Spring scheduler-level Observation은 별도 acceptance로 주장하지 않는다.
- [ ] `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled` 명시 annotation이 property policy보다 우선한다.
- [ ] empty, malformed, duplicate, unmatched, overload 설정과 plain policy의 invalid duration이 startup에서 실패한다. explicit annotation의 미사용 property semantic은 annotation validator 범위와 구분한다.
- [ ] context close 후 pending Spring scheduled task가 취소되고 custom thread/executor가 남지 않으며, in-flight body 중단은 보장하지 않는다는 경계를 검증한다.
- [ ] `spring.aop.auto=false`에서는 runtime proxy 설정과 무관하게 CTW leader ownership이 유지됨을, `bluetape4k.leader.aop.enabled=false`에서는 factory/registry/aspect가 조건부로 빠져 task가 무잠금 실행될 수 있음을 각각 확인하고, `FAIL_OPEN_RUN`은 멱등 작업 전용 trusted override임을 문서화한다.
- [ ] profile의 static bounded non-PII name, `allow-method-invocation=false`, lock-name metric `REDACT` 설정을 테스트로 고정한다.
- [ ] 기존 19개 reducer test와 README snippet test가 변경 없이 통과한다.
- [ ] 영어/한국어 README가 profile 명령, YAML 키, exact selector, task 수, precedence, fail-fast, lifecycle, local-only 제한, PII 경고와 failure-mode 표를 같은 사실로 설명하고 `scripts/validate-readme-parity.mjs`로 구조 parity를 검증한다.
- [ ] README에 `Started TenantSchedulerLabAppKt` 성공 신호, 첫 실행 전 최대 60초 대기·min lease 추가 지연, `Ctrl-C` 종료와 deterministic test 명령/기대 결과를 안내한다.
- [ ] `scripts/smoke-validate.sh stale-check`가 profile YAML·main configuration·selector·양쪽 README 실행 명령의 drift를 감지하고, `@SpringBootTest` profile startup/close가 packaged resource와 main class를 검증한다.
- [ ] smoke/full workflow, stale-check, catalog/BOM, diff check, `:leader-tenant-scheduler:test`, compile 검증 결과가 문서화된다.

## 구현 후 DoD

1. spec과 plan이 각각 `SPW-01`~`SPW-05`를 통과하고, 6관점 review의 최신 통합 결과가 `P0=0`, `P1=0`이다.
2. TDD red/green 증거가 남고, 신규 context/lifecycle 테스트와 기존 테스트가 통과한다.
3. 변경 파일의 Kotlin/README/Gradle/workflow hazard를 점검하고 `git diff --check`가 통과한다.
4. lesson과 tracked review artifact를 commit하고, PR은 `[2.0.0]` prefix·`develop` base·Issue #869 연결·DoD Status를 갖는다.
5. PR 생성 뒤 live CI와 review 상태를 확인하되, merge는 별도 fresh `승인` 없이는 수행하지 않는다.

## 설계 상태

설계는 2026-08-31 사용자가 승인했다. 이 문서는 승인된 설계를 기록한 것이며, 구현 계획 작성과 6관점 사양 review가 다음 단계다.
