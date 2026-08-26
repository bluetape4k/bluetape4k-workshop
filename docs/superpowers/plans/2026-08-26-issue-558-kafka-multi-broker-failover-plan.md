# Issue #558 Multi-broker Kafka Failover Reference 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 승인된 #558 설계에 따라 3-broker KRaft Testcontainers 클러스터에서 partition leader failover, group coordinator recovery, producer reconnect, consumer fetch, replacement broker의 ISR 재가입을 결정론적으로 증명하는 독립 Spring Boot/Kotlin reference module을 추가한다.

**Architecture:** `messaging/kafka-multi-broker-failover`의 `src/main`은 Spring shell과 명시적 Kafka client wiring, 불변 reference event/codec만 제공한다. `src/test`는 동일 cluster ID의 `KafkaContainer` 세 개, AdminClient topology 조회, fault/replacement lifecycle, bounded wait, redacted JSONL evidence, producer/consumer collector와 두 개의 fresh-fixture integration scenario를 소유한다. 기존 `messaging-kafka`의 단일 broker 예제와 #555의 Toxiproxy TCP path 예제는 수정하지 않는다.

**Tech Stack:** Kotlin 2.4.0, Java 25, repository catalog가 해석하는 Spring Boot catalog version(현재 `4.1.0`; 개별 버전 임의 pin 금지), Spring Kafka 4, Kafka clients 4.2.0 catalog alias, Testcontainers BOM 2.0.5, Apache Kafka image `apache/kafka:4.2.0`와 승인된 immutable digest, Jackson 3 explicit JSON string codec, JUnit 5, `bluetape4k-assertions`, Docker/Colima.

---

## 실행 경계와 source ledger

구현은 사용자가 이 계획을 별도로 승인한 뒤에만 시작한다. 계획 승인 전에는 이 문서와 계획 리뷰 artifact 외의 코드·워크플로·README·이미지를 수정하지 않는다. Type-A workflow의 implementation gate, TDD gate, Kotlin checklist, Testcontainers sequential gate를 모두 유지한다.

| 계약/근거 | 구현 작업 | fresh 검증 |
|---|---|---|
| 설계 §모듈과 책임, §구현 계약 | Task 1, Task 8 | `./gradlew projects`, source-set 검사, registration search |
| 설계 §Broker topology | Task 3 | 3 broker startup/quorum, metadata listener, image digest, local-only bind 검사 |
| 설계 §Event and client contract | Task 2, Task 5 | codec/config/collector unit test, producer metadata partition, consumer assignment |
| 설계 §data-leader-failover | Task 6 | fresh fixture integration scenario, leader 이동 15초, suffix ack, ISR 3 |
| 설계 §group-coordinator-failover | Task 7 | 독립 fresh fixture, coordinator/leader 분리, assignment callback, suffix fetch |
| 설계 §timeouts와 evidence | Task 3, Task 4, Task 10 | cumulative deadline, timeout/cancellation/rollback, JSONL/canary/JUnit artifact 검사 |
| 설계 수용 기준 1–6 | Task 3, Task 5–7 | targeted Testcontainers test와 evidence schema |
| 설계 수용 기준 7–11 및 DoD | Task 8–11 | README parity, CI/nightly/smoke/stale-check, SVG/PNG audit, final checklist |

### Kotlin pattern 적용 범위

- `KT-01`: 모든 Kotlin test/fixture/Testcontainers와 new module/Spring configuration trigger에 대해 `bluetape-kotlin-patterns`, `references/testing.md`, `references/spring-boot.md`, `references/module-setup.md`, workflow `repository-hazards.md`, completion 시 `references/checklist.md`를 적용한다.
- `KT-02`: `messaging/kafka`, #555 fixture, `shared`의 TestMutex/fixture helper, catalog, workflow/stale-check를 먼저 읽고 raw fallback 근거를 plan/task에 남긴다.
- `KT-03`: caller 입력은 `require*`, 내부 lifecycle은 `check`/`checkNotNull`, production `!!`·`println`·`System.out`·wildcard trusted package·default typing·suspend `runCatching`을 사용하지 않는다. blocking Kafka/AdminClient/Testcontainers 호출은 test-only bounded blocking API로 격리하고 ownership을 `AutoCloseable`에 명시한다.
- `KT-04`: RED→GREEN targeted test, compile, full module test, `git diff --check`, import/deprecation scan, image/bind/canary 증거를 순서대로 실행한다.
- `KT-05`: 마지막에 `references/checklist.md`의 X=Y, Blocked=0, P0=0/P1=0과 concrete N/A를 기록한다.
- `KT-TEST-01..05`: JUnit 5 descriptive backtick name/Given-When-Then, `bluetape4k-assertions`와 `io.bluetape4k.assertions.assertFailsWith`, real IO dispatcher, bounded polling, sequential Testcontainers, fresh `cleanTest --no-build-cache`를 사용한다. HTTP/HC5는 노출하지 않으므로 `KT-TEST-04`는 concrete N/A로 기록한다.
- `KT-SPR-01..05`: auto-configuration phase는 만들지 않으므로 optional class/ordering checks는 concrete N/A다. Spring shell, immutable properties, explicit serializers와 `@SpringBootTest` narrow context만 검증한다.
- `KT-MOD-01..04`: 자동 settings registration, module README locale pair, test resources, CI/nightly/smoke/stale-check, catalog/BOM governance를 동기화한다. benchmark module이 아니므로 `KT-MOD-03`은 concrete N/A다.
- Exposed, HTTP adapter, coroutine suspend API, benchmark, virtual-thread monitor는 이 모듈의 production 계약에 없으며 각각 concrete N/A를 final checklist에 기록한다. Testcontainers의 blocking lifecycle에는 `runBlocking`이나 ad hoc coroutine을 추가하지 않는다.

## 파일 구조와 소유권

| 영역 | 파일 | 책임 |
|---|---|---|
| 모듈 | `messaging/kafka-multi-broker-failover/build.gradle.kts` | Spring Boot/Kotlin 설정, production/test dependency 경계 |
| main entry | `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverApplication.kt` | `KafkaMultiBrokerFailoverApplicationKt` Spring shell |
| main event/codec | `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverEvent.kt`, `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverCodec.kt` | immutable event와 strict JSON string contract |
| main client config | `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverKafkaConfiguration.kt` | explicit String serializer/deserializer와 producer/consumer properties |
| main resources | `messaging/kafka-multi-broker-failover/src/main/resources/application.yml` | `web-application-type=none`, loopback/no actuator, Kafka properties |
| test fixture | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClusterFixture.kt`, `KafkaFailoverDeadline.kt`, `KafkaFailoverRetry.kt`, `KafkaFailoverTopology.kt`, `KafkaFailoverResourceScope.kt` | 3 broker KRaft startup, dynamic topology, stop/replacement, bounded lifecycle와 resource ownership |
| test admin/evidence | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverAdmin.kt`, `KafkaFailoverEvidence.kt`, `KafkaFailoverEvidenceWriter.kt` | AdminClient state, redacted JSONL, canary/failure summaries |
| test clients | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClientFactory.kt`, `KafkaFailoverCollector.kt` | producer/consumer config, assignment barrier, raw/applied/conflict counts |
| unit tests | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverEventTest.kt`, `KafkaFailoverCodecTest.kt`, `KafkaFailoverEvidenceTest.kt`; fixture package `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverDeadlineTest.kt` | container-free contract tests |
| fixture tests | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClusterFixtureTest.kt`, `KafkaFailoverImageContractTest.kt`, `KafkaFailoverClientFactoryTest.kt` | lifecycle, cleanup, image/bind configuration, redaction tests |
| integration tests | `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverIntegrationTest.kt` | `data-leader-failover`, `group-coordinator-failover` fresh-fixture scenarios |
| module resources | `src/test/resources/junit-platform.properties`, `src/test/resources/logback-test.xml` | same-thread test execution, safe logging |
| public docs | module `README.md`, `README.ko.md`, root `README.md`, `README.ko.md` | first-run command, topology, evidence, boundary table |
| validation/docs | `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`, `scripts/smoke-validate.sh`, `docs/lessons/2026-08-26-issue-558-kafka-multi-broker-failover.md`, four diagram assets | registration, sequential CI, lesson, SVG/PNG pair |

`src/main`에는 `org.testcontainers.*`, `Network`, `AdminClient` fixture, fault injection, evidence writer를 import하지 않는다. Testcontainers와 test-only helpers는 `testImplementation`과 `src/test`에만 둔다. 새 클래스와 non-obvious internal contract의 KDoc/comment는 한국어로 작성하고 public surface는 최소화한다.

## Task 1: 모듈 골격과 clean runtime contract를 먼저 고정

**Files:**

- Create: `messaging/kafka-multi-broker-failover/build.gradle.kts`
- Create: `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverApplication.kt`
- Create: `messaging/kafka-multi-broker-failover/src/main/resources/application.yml`
- Create: `messaging/kafka-multi-broker-failover/src/test/resources/junit-platform.properties`
- Create: `messaging/kafka-multi-broker-failover/src/test/resources/logback-test.xml`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverRuntimeContractTest.kt`

- [x] **Step 1: 최소 골격 후 RED runtime contract 작성**

  module이 아직 없을 때는 module 내부 JUnit에 도달할 수 없으므로 repository-level precondition으로 `./gradlew projects --console=plain`에서 project가 아직 없고 `./gradlew :messaging-kafka-multi-broker-failover:tasks --console=plain`이 project-not-found로 실패하는 RED를 기록한다. 그 다음 `build.gradle.kts`와 빈 source/test 디렉터리만 추가한 최소 Gradle 골격에서 `./gradlew projects --console=plain`과 compile precondition을 먼저 확인하고, `KafkaFailoverRuntimeContractTest`를 descriptive backtick name으로 작성해 RED를 실행한다. 이 테스트는 main class FQCN `io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplicationKt`, `spring.main.web-application-type=none`, `management` 미등록, production resource에 `KafkaContainer` 문자열이 없음, external bootstrap env/property를 읽지 않음을 검증한다. 테스트가 실패한 뒤에만 application/resource 구현으로 진행한다.

- [x] **Step 2: 최소 Gradle/Spring shell 구현**

  `build.gradle.kts`에는 `kotlin.spring`, `spring.boot`만 필요한 범위로 적용하고 `springBoot { mainClass.set("io.bluetape4k.workshop.messaging.kafka.multibroker.failover.KafkaMultiBrokerFailoverApplicationKt") }`를 고정한다. root convention이 제공하는 Java 25/Kotlin 2.4와 root `bluetape4k-dependencies` BOM을 사용하며 개별 bluetape4k BOM/version은 추가하지 않는다. production dependency는 catalog의 `bluetape4k.kafka4`, `bluetape4k.core`, `bluetape4k.jackson3`, `bluetape4k.logging`, `kafka.clients`, `spring.kafka.lib`, `spring.boot.autoconfigure.lib`로 제한하고 configuration metadata processor는 `annotationProcessor(libs.spring.boot.configuration.processor)`에만 둔다. `testImplementation`에는 `bluetape4k.assertions`, `bluetape4k.junit5`, `bluetape4k.testcontainers`, `testcontainers.kafka`, `spring.kafka.test`, `spring.boot.starter.test`를 둔다. `testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())` convention은 sibling module 패턴에 맞춰 필요한 경우에만 사용한다. Testcontainers와 container image coordinate는 production configuration에 노출하지 않는다.

  `KafkaMultiBrokerFailoverApplication.kt`는 `@SpringBootApplication(proxyBeanMethods = false)`와 `fun main(args: Array<String>)`만 제공하고, `application.yml`은 `spring.application.name: messaging-kafka-multi-broker-failover`, `spring.main.web-application-type: none`, `spring.main.banner-mode: off`만 둔다. production shell은 external bootstrap env/property를 읽거나 연결하지 않으며, `KafkaFailoverKafkaConfiguration`은 Spring `@ConfigurationProperties`나 `spring.kafka.*` 자동 binding이 아닌 명시적으로 생성하는 immutable value/factory contract로 고정한다. fixture가 발급한 loopback mapped `PLAINTEXT` endpoint는 test-only client factory에 직접 전달한다. actuator/webflux/starter-web/management exposure는 추가하지 않는다. `junit-platform.properties`는 `junit.jupiter.execution.parallel.enabled=false`, `junit.jupiter.execution.parallel.mode.default=same_thread`, `junit.jupiter.execution.parallel.mode.classes.default=same_thread`를 고정하고, `logback-test.xml`은 `io.bluetape4k.workshop.messaging.kafka.multibroker.failover`, `org.apache.kafka`, `org.springframework.kafka`, `org.testcontainers` logger에 명시 level/filter를 적용해 payload/bootstrap/credential/env와 cause/suppressed 출력이 나오지 않게 한다. 전체 stdout/stderr capture도 canary test 대상에 포함한다.

- [x] **Step 3: GREEN 골격 검증**

  `./gradlew projects --console=plain`에서 `:messaging-kafka-multi-broker-failover`가 정확히 한 번 보이는지 확인한 뒤 `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverRuntimeContractTest' --max-workers=1 --console=plain`을 실행한다. dependency insight 또는 repository dependency verification/checksum task가 제공되면 resolved graph에서 허용된 catalog coordinate/version과 `bluetape4k-dependencies` BOM 단일 import를 기록하고 unexpected transitive dependency를 거부한다. expected result는 runtime contract tests PASS, compile 성공, Testcontainers import 부재, dependency verification PASS다.

## Task 2: immutable event, strict codec와 client property contract를 TDD로 구현

**Files:**

- Create: `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverEvent.kt`
- Create: `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverCodec.kt`
- Create: `messaging/kafka-multi-broker-failover/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverKafkaConfiguration.kt`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverEventTest.kt`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverCodecTest.kt`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverKafkaConfigurationTest.kt`

- [x] **Step 1: event/codec RED fixture 작성**

  `KafkaFailoverEventTest`는 `eventId`, `sequence`, `payload`, `partitionKey`가 불변이고 `topic`이 `kafka-failover-reference`, `partitionKey`가 `failover-partition-0`인지, blank ID/payload/key와 negative sequence가 `require*`의 `IllegalArgumentException`으로 거절되는지 검증한다. durable data class는 `java.io.Serializable`을 구현하고 `companion object` 안에 `@JvmField private const val serialVersionUID: Long = 1L`을 실제 static field로 명시하며, constructor validation이 `copy` 경로에서도 유지되는지 확인한다. `KafkaFailoverCodecTest`는 canonical field order, unknown field, duplicate JSON key, missing required field, null, malformed number, trailing token/concatenated JSON, wrong root/type, coercion, oversized/deep JSON, same `eventId`/different payload fingerprint를 각각 failure로 고정하고, 동일 event가 byte-identical JSON/SHA-256 fingerprint를 내는지 확인한다. `StreamReadConstraints` 한도와 exact scalar type/no-coercion도 fixture로 고정한다. `io.bluetape4k.assertions.assertFailsWith`를 사용하고 JUnit `assertThrows`/`kotlin.test.assertFailsWith`는 사용하지 않는다.

- [x] **Step 2: strict JSON string codec 구현**

  `KafkaFailoverCodec`는 `encode(event): String`, `decode(json): KafkaFailoverEvent`, `fingerprint(event): String`만 노출한다. Jackson 3 mapper는 explicit property schema와 `FAIL_ON_UNKNOWN_PROPERTIES`, `STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_READING_DUP_TREE_KEY`, `StreamReadConstraints`를 사용하고 exact scalar type/no-coercion을 적용하며 Spring Kafka type header, `spring.json.trusted.packages=*`, polymorphic default typing을 사용하지 않는다. codec은 `StringSerializer`/`StringDeserializer`와 byte-for-byte로 호환되고 payload를 log에 쓰지 않는다. ID 충돌 검사는 collector가 fingerprint 결과를 비교하도록 domain error를 명시한다. `KafkaFailoverKafkaConfiguration`은 Spring constructor binding이 아닌 명시적 factory input/value object로 bootstrap/topic/group을 받고, producer에 `acks=all`, `retries=3`, `delivery.timeout.ms=20000`, `request.timeout.ms=5000`, `max.block.ms=10000`, `retry.backoff.ms=200`, `retry.backoff.max.ms=2000`, `enable.idempotence=true`, `max.poll.records`/fetch byte ceiling을 고정하고, consumer에 `enable.auto.commit=false`, `auto.offset.reset=earliest`, `session.timeout.ms=10000`, `heartbeat.interval.ms=3000`, `max.poll.interval.ms=20000`, fixed group ID, listener `AckMode.MANUAL`, AdminClient에 `request.timeout.ms=5000`, `default.api.timeout.ms=10000`을 고정한다.

- [x] **Step 3: configuration GREEN 검증**

  `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverEventTest' --tests '*KafkaFailoverCodecTest' --tests '*KafkaFailoverKafkaConfigurationTest' --max-workers=1 --console=plain`을 실행한다. configuration test는 serializer/deserializer FQCN, no type-header property, all timeout values, idempotence, manual ack와 빈 bootstrap fail-fast를 확인하고, external/public/private/credential-bearing bootstrap URI와 scheme/userinfo/DNS 우회 입력을 negative로 거부한다. `./gradlew :messaging-kafka-multi-broker-failover:compileKotlin --max-workers=1 --console=plain`도 별도로 통과해야 한다.

## Task 3: 3-broker KRaft fixture와 bounded lifecycle를 TDD로 구현

**Files:**

- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverDeadline.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverRetry.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverTopology.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClusterFixture.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverResourceScope.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClusterFixtureTest.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverImageContractTest.kt`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverDeadlineTest.kt`
- Test: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverRetryTest.kt`

- [x] **Step 1: deadline/lifecycle RED 테스트 작성**

  `KafkaFailoverDeadlineTest`는 monotonic `System.nanoTime()` 기반 `ModuleDeadline`(test JVM/전체 module invocation에서 한 번 생성), scenario child deadline, phase child deadline의 remaining을 `min(module, scenario, phase)`로 계산하고 음수가 되지 않으며 단조 감소하는지 검증한다. startup `45s`, scenario `180s`, module `420s`, cleanup `10s`를 고정하고, 개별 targeted Gradle 명령은 독립 JVM 진단 lane인 반면 전체 module test 한 번이 두 fresh fixture를 포함한 공통 `420s` budget을 증명한다. timeout 시 `TimeoutException`과 phase/allowlisted state를 남기는지 검증한다. `KafkaFailoverClusterFixtureTest`는 `NEW → STARTING → RUNNING → STOPPING → CLOSED` lifecycle과 단일 owner lock을 RED로 고정하고, partial-start rollback, post-validation failure rollback, `stopBroker`, replacement `restartBroker`, cancellation quiescence, idempotent `close`를 검증한다. resource scope test는 collector stop/await → AdminClient → producer → consumer → broker → network 순서를 요구하고 첫 cleanup 실패 뒤 나머지를 시도하며 suppressed failure를 보존해야 한다.

- [x] **Step 2: KRaft broker topology 구현**

  fixture는 `Network.newNetwork()` 하나와 승인된 digest-qualified `DockerImageName.parse("apache/kafka@sha256:" + APPROVED_IMAGE_DIGEST)` 세 개를 각각 생성한다. 기존 `messaging/kafka`의 `KafkaServer.Launcher`는 single-broker 기본값과 고정 topology를 제공해 독립 node ID/alias, 동일 cluster ID, broker replacement를 표현하지 못하므로 재사용하지 않는 사유를 source ledger에 기록하고 raw `KafkaContainer` wiring은 이 fixture 경계 안에서만 허용한다. `APPROVED_IMAGE_DIGEST`는 구현 전 image pull/`RepoDigests`와 공식 image authority를 확인해 source ledger와 `KafkaFailoverClusterFixture.kt` 단일 상수에 기록하며, 실제 64자리 hex digest가 고정되기 전에는 GREEN이 불가능하다. `CLUSTER_ID`도 fixture 단일 상수로 생성·주입하고 세 broker의 effective cluster ID가 동일함을 startup post-check와 negative test로 검증한다. aliases는 `kafka-1`, `kafka-2`, `kafka-3`, node ID는 `1`, `2`, `3`, `KAFKA_PROCESS_ROLES=broker,controller`, voters는 `1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094`, listeners는 `PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094`, inter-broker listener `BROKER`, controller listener `CONTROLLER`, security map은 세 listener 모두 `PLAINTEXT`로 고정한다. 사용자 topic은 `kafka-failover-reference`, partitions `3`, replication factor `3`, `min.insync.replicas=2`이며 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false`, offsets partitions/RF `3`, transaction state RF `3`/min ISR `2`를 설정한다. `KafkaContainer`의 default wait를 broker별 독립 `Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1).withStartupTimeout(Duration.ofSeconds(45))`로 교체한다.

  `start()`는 lifecycle lock 아래에서 `STARTING`으로 전환한 뒤 각 broker를 `TrackedStartable` wrapper로 감싸 settlement marker를 기록하고 `Startables.deepStart(trackedBrokers.stream())` aggregate future를 module cumulative deadline의 remaining으로 `get(remaining, MILLISECONDS)` 또는 `orTimeout(remaining, MILLISECONDS)` 처리한다. timeout/interruption/`CancellationException`/error에서는 aggregate `cancel(true)` 후 각 wrapper settlement와 container running state를 bounded quiescence까지 확인하고 원래 원인과 partial-start rollback을 보존하며 `RUNNING`으로 승격하지 않는다. child start가 cancellation을 무시해 quiescence를 넘기면 rollback을 강행하지 않고 orphan 진단과 fail-closed 상태를 남긴다. AdminClient quorum/listener/digest post-check까지 하나의 failure-atomic 구간으로 감싸 어떤 post-validation failure도 이미 시작한 broker/network를 rollback한다. bare `join/get` 및 무한 대기를 허용하지 않는다. startup 뒤에는 cluster node 3, controller quorum, listener/digest와 loopback bind만 확인한다. 사용자 topic의 config/effective replicas/ISR 검사는 `data-leader-failover`와 `group-coordinator-failover`에서 명시적으로 topic을 생성한 직후 `topic-ready` phase로 수행하고, `__consumer_offsets` 검사는 assignment barrier 이후에 수행한다. `getBootstrapServers()`로 얻은 mapped `PLAINTEXT` endpoint만 host JVM client에 반환하고, 외부 host-client metadata/evidence에는 mapped `PLAINTEXT`만 허용한다. Docker 내부 `BROKER` endpoint는 broker-to-broker metadata/config 범위에서만 허용하고 host client bootstrap 또는 evidence로 누출되면 실패하며, `CONTROLLER` endpoint는 어느 외부 범위에도 허용하지 않는다. fixed host port와 host network는 사용하지 않으며 Docker runtime에서 loopback-only bind를 입증할 수 없으면 보안 경계 assertion을 실패시킨다. 시작 전 HostConfig binding을 loopback으로 강제하고 시작 후 모든 binding의 `HostIp`는 `127.0.0.1` 또는 `::1` literal이어야 하며, `getHost()`는 `localhost`를 포함해 DNS 해석된 모든 주소가 loopback인지 검증한다. 빈 HostIp, `0.0.0.0`, `::`, 원격 host 또는 non-loopback DNS result는 실패시킨다. metadata advertised endpoint는 host-client protocol scope에서 loopback mapped `PLAINTEXT:9092`만 allowlist하고 hostname, `9093`, `9094`, `0.0.0.0`, public/private endpoint는 거부한다. `BROKER`는 Docker network 내부 범위에서만, `CONTROLLER`는 advertised 대상이 아님을 synthetic metadata negative test로 고정한다.

  image는 구현 시 실제 `RepoDigests`를 읽어 승인된 immutable digest를 `KafkaFailoverClusterFixture.kt`의 단일 상수로 고정하고 `KafkaFailoverImageContractTest`에서 각 broker의 RepoDigests가 정확히 하나이며 승인 repository/digest와 일치하는지 검증한다. digest 누락·복수·repository 불일치·tag-only/mismatch는 negative test로 고정하고 증거를 확보하지 못하면 GREEN을 중지한다. `latest`/자동 fallback, `withEnv` override 미적용, alias/quorum drift는 fail-fast이며 GenericContainer wrapper로 우회하지 않는다. 모든 broker/network에 stable label `bluetape4k.kafka-failover.run-id`와 `node-id`를 부여해 `TESTCONTAINERS_RYUK_DISABLED=true`에서도 orphan을 식별한다. broker registry는 node ID를 key로 하며 stop 전에 현재 container를 원자적으로 `STOPPING` 상태로 표시하고, `stopBroker`는 allowlisted topology summary 저장 → stop → `isRunning=false` 및 container 제거 확인 → registry에서 제거 순서를 따른다. `restartBroker`는 기존 registry entry 제거 확인 뒤 동일 digest-qualified image, config, node ID, network alias의 replacement `KafkaContainer`를 임시 registry에 먼저 등록하고, startup/metadata/ISR 재가입에 실패하면 replacement를 닫고 원래 stopped 상태를 보존한다. 성공 시에만 node ID entry를 원자적으로 교체한다. persistent volume/process restart는 사용하지 않는다.

  `KafkaFailoverRetry`는 AdminClient metadata/config/ISR wait에만 적용하는 단일 deadline-aware retry helper로 두고, 모든 blocking future/send/stop/close 호출에는 `KafkaFailoverDeadline.awaitBlocking`을 적용한다. `MAX_RETRY_ATTEMPTS = 5`를 고정하고 retryable 집합(`RetriableException`, `TimeoutException`, `DisconnectException`)과 initial `200ms`→`2s` exponential backoff, cancellation/non-transient 즉시 실패를 고정한다. `retryCount`는 AdminClient 최초 시도 이후 재시도 횟수만 집계하고 producer client 내부 재시도는 별도 `performance.jsonl` counter로 기록하되 evidence `retryCount`에 합산하지 않는다. attempt cap 초과는 마지막 allowlisted 상태와 함께 즉시 실패한다. Testcontainers start/stop 자체에는 자동 재시도를 붙이지 않는다.

  `KafkaFailoverResourceScope`가 AdminClient, producer, consumer, `ConcurrentMessageListenerContainer`, collector의 유일한 owner다. client factory는 scope에 등록된 handle만 반환하고 개별 caller close를 허용하지 않는다. scenario harness는 `scope.close()`를 `try`로 호출하고 반드시 바깥 `finally`에서 fixture의 broker → network close를 실행한다. scope close가 실패해도 fixture cleanup은 계속되며 첫 failure와 후속 failure를 suppressed exception으로 보존한다. 정상 scope close는 collector stop → callback quiescence/await → AdminClient → producer → consumer 순서로 bounded close하고, 부분 client 생성 실패도 이미 만든 자원과 callback을 같은 순서로 rollback하며 이중 close는 no-op다.

- [x] **Step 3: fixture GREEN와 resource safety 검증**

  Docker 또는 Colima를 먼저 확인하고 `colima status`, `docker context show`, `docker info` 결과를 기록한다. `run_id="$(printenv KAFKA_FAILOVER_RUN_ID 2>/dev/null || true)"; if [ -z "$run_id" ]; then run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"; fi`로 CI/local run ID를 생성하거나 전달하고, `container_id="$(docker ps --filter "label=bluetape4k.kafka-failover.run-id=$run_id" --format '{{.ID}}' | head -n 1)"; test -n "$container_id" || { echo 'current run broker container not found' >&2; exit 1; }`로 빈 container ID를 fail-fast한다. 각 broker container에 대해 `docker inspect --format '{{json .HostConfig.PortBindings}}' "$container_id"`와 `docker inspect --format '{{json .NetworkSettings.Ports}}' "$container_id"`를 read-only 실행해 HostIp가 `127.0.0.1`/`::1` literal인지 증명하고, `getHost()` DNS 결과는 `localhost`를 포함해 loopback인지 확인한다. 빈 HostIp/`0.0.0.0`/`::`/원격 host/non-loopback DNS result이면 실패시킨다. orphan 진단은 `docker ps -a --filter "label=bluetape4k.kafka-failover.run-id=$run_id" --format '{{.ID}} {{.Names}} {{.Labels}}'`와 `docker network ls --filter "label=bluetape4k.kafka-failover.run-id=$run_id"`로 read-only 수행하며 삭제 명령은 자동화하지 않는다. 그 다음 `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverClusterFixtureTest' --tests '*KafkaFailoverDeadlineTest' --tests '*KafkaFailoverRetryTest' --tests '*KafkaFailoverImageContractTest' --max-workers=1 --console=plain`을 순차 실행한다. expected result는 3 node/quorum, loopback HostConfig/HostIp proof와 unsafe binding negative test, digest-qualified image의 단일 RepoDigest match와 missing/multiple/repository mismatch negative test, random mapped port, protocol-scoped advertised metadata allowlist, bounded startup/rollback/post-validation rollback/replacement registry/close, retry allowlist와 orphan non-destructive inspection이다. 실패 시 code 변경 전에 container/network 상태와 redacted summary를 진단하고, 건강한 Colima를 재시작하지 않는다.

## Task 4: AdminClient topology, evidence schema와 redaction/canary를 TDD로 구현

**Files:**

- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverAdmin.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverEvidence.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverEvidenceWriter.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaFailoverEvidenceTest.kt`

- [x] **Step 1: evidence/admin RED 테스트 작성**

  `KafkaFailoverEvidenceTest`는 JSONL 한 줄의 field set을 `runId`, `scenario`, `phase`, `image`, `imageDigest`, `topic`, `partition`, `nodeCount`, `leader`, `replicas`, `isr`, `coordinator`, `assignmentCount`, `rawDeliveryCount`, `appliedCount`, `conflictCount`, `retryCount`, `status`로 정확히 고정한다. 모든 phase가 18개 key를 유지하고 사용할 수 없는 scalar는 `null`/빈 배열/0 표현을 고정한다. phase enum과 순서는 `startup → topic-ready → assignment-ready → prefix-acked → fault-injected → recovery → suffix-acked → replacement-ready → isr-restored → terminal`로 고정하고, `PREFIX_EVENTS=4`, data suffix `4`, coordinator suffix `2`의 exact logical ID union 및 raw/applied/conflict 조건을 검증한다. payload, bootstrap URL, environment variable, credential, owner token, full stack trace, raw provider/broker body가 output에 없음을 synthetic canary로 검사한다. broker diagnostic은 `build/reports/kafka-failover/{runId}/broker-{brokerId}.log` redacted summary만 허용하며 raw log artifact라는 이름의 파일은 생성하지 않는다. evidence, broker summary, JUnit XML, CI artifact 전체에 payload/eventId/bootstrap/credential/env/token/exception/raw-log canary corpus와 JSON/XML/URL-encoded 변형이 발견되면 fail-closed 하는 scanner test를 먼저 작성한다. scanner 출력은 secret value가 아닌 path/count/hash만 포함한다.

- [x] **Step 2: AdminClient/evidence writer 구현**

  `KafkaFailoverAdmin`은 cluster nodes, topic description, partition leader/replicas/ISR, group coordinator, consumer assignment/generation, effective broker/topic configs를 allowlisted scalar로만 반환한다. `KafkaFailoverRetry`는 retryable metadata/config/ISR wait를 담당하고 `KafkaFailoverDeadline.awaitBlocking`은 모든 `KafkaFuture.get`, producer send future, consumer stop/close에 phase remaining을 전달하며 naked `get/join/await`를 금지한다. `KafkaFailoverEvidenceWriter`는 먼저 `build/reports/kafka-failover/{runId}/evidence.jsonl.tmp`에 line 단위로 쓰고 flush/fsync 후 atomic rename하여 current run artifact를 만든다. legacy 고정 경로 `build/reports/kafka-failover/evidence.jsonl`가 필요하면 current run 파일을 atomic replace하며 이전 run line을 누적하지 않는다. 반복 실행 test는 line count와 `runId` 필터가 오염되지 않음을 확인한다. 각 scenario는 `try/finally`에서 cleanup 전 allowlisted terminal `PASS` 또는 `FAIL` phase를 한 번 기록하고, writer failure가 원래 assertion을 덮지 않도록 suppressed failure로 보존한다. 별도 allowlisted `performance.jsonl`에는 phase elapsed, deadline remaining, Admin round-trip count, ack/poll/retry count, cleanup duration, max buffered records/bytes만 기록한다. failure message는 phase와 마지막 allowlisted AdminClient summary만 포함한다. payload/eventId/bootstrap URL/credentials/전체 exception은 log/evidence에 넣지 않는다. summary file path와 synthetic canary scan 대상은 constants로 고정한다.

- [x] **Step 3: evidence GREEN 검증**

  `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverEvidenceTest' --max-workers=1 --console=plain`을 실행한다. 결과 JSONL을 line-by-line parse해 18개 required field, phase enum/순서, exact ID/count invariant, numeric count, terminal status, no-secret/no-endpoint invariant를 확인하고 같은 test를 두 번 실행해 atomic current-run replacement를 확인한다. `git diff --check`도 통과시킨다. 다음 integration task는 이 writer만 사용하고 임의 `println`/raw container log copy를 추가하지 않는다.

## Task 5: producer/consumer client, assignment barrier와 collector를 구현

**Files:**

- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClientFactory.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverCollector.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverResourceScope.kt`
- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/fixture/KafkaFailoverClientFactoryTest.kt`

- [x] **Step 1: client/collector RED 테스트 작성**

  `KafkaFailoverClientFactoryTest`는 producer/consumer/admin property map가 Task 2 값과 byte-for-byte 일치하는지, `ProducerRecord(topic, 0, partitionKey, encodedEvent)`가 partition `0`을 명시하는지, producer `RecordMetadata.partition == 0` assertion이 없는 publish를 거부하는지 검증한다. producer batch는 4개 record를 먼저 모두 submit한 뒤 하나의 shared phase remaining으로 futures를 await하여 순차 `4 × delivery.timeout` 누적을 금지한다. consumer assignment barrier는 bounded `CompletableFuture`/latch와 generation 기준값으로 callback count·assigned partition set을 기록하고 earliest offset에서 group을 연결한 뒤에만 baseline을 허용한다. close 이후 callback이 증가하지 않는 quiescence도 검증한다. collector test는 동일 eventId의 first delivery를 applied 1, duplicate delivery를 raw +1/applied +0, 다른 payload fingerprint를 conflict +1/fail로 기록하는 matrix를 먼저 작성하며 최대 buffered records/bytes ceiling을 고정한다.

- [x] **Step 2: client factory/collector 구현**

  `KafkaFailoverClientFactory`는 fixture가 발급한 loopback mapped `PLAINTEXT` bootstrap list만 comma-separated로 받아 explicit `StringSerializer`/`StringDeserializer` producer, consumer, AdminClient를 만들고 `KafkaFailoverResourceScope`에 등록된 handle을 반환한다. scope가 유일한 owner이므로 caller가 개별 resource를 close하지 않는다. `KafkaFailoverCollector`는 `ConcurrentMessageListenerContainer` assignment callback, bounded latch/future, `AckMode.MANUAL`, `Acknowledgment.acknowledge()` 이후에만 applied set을 갱신하고 raw/applied/conflict/retry counters와 `max.poll.records`/fetch byte/collector capacity ceiling을 관리한다. consumer group ID는 scenario마다 고정된 값으로 두되 각 fresh fixture에서 재사용하고, `auto.offset.reset=earliest`와 no auto commit을 유지한다. poll은 `min(250ms, phase remaining)`, assignment/fetch wait는 cumulative `min(module, scenario, phase)` remaining으로 제한하며 `Thread.sleep` 고정 타이밍을 사용하지 않는다. 모든 producer send future와 consumer stop/close는 `KafkaFailoverDeadline.awaitBlocking`으로 감싸고, callback/collector의 mutable state는 public flow/channel로 노출하지 않는다. scope close는 collector stop → callback quiescence → AdminClient → producer → consumer 순서로 bounded close하며 이후 fixture close를 호출한다.

- [x] **Step 3: client GREEN 검증**

  `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverClientFactoryTest' --max-workers=1 --console=plain`을 실행한다. expected result는 property/partition/batch-await/assignment-generation/dedup/conflict/buffer-ceiling matrix PASS, production source에 Testcontainers import 없음, test-only resource-scope close path와 callback quiescence 확인이다. 실제 broker 연결은 다음 Task 6/7의 fresh fixture에서만 수행한다.

## Task 6: `data-leader-failover` integration scenario 구현

**Files:**

- Create: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverIntegrationTest.kt`

- [x] **Step 1: scenario RED test 작성**

  `data-leader-failover` test는 class-level `ModuleDeadline`에서 child scenario deadline `180s`를 만들고 method 시작 시 fresh `KafkaFailoverClusterFixture`와 `KafkaFailoverResourceScope`를 생성한다. quorum 뒤 `auto.create.topics.enable=false` 상태에서 user topic을 먼저 명시 생성하고 partitions `3`, RF `3`, min ISR `2`를 확인한다. 그 다음 consumer assignment barrier로 group을 연결해 `__consumer_offsets` 생성 완료를 확인한 뒤 internal offsets/transaction state effective replicas/ISR를 검증한다. consumer assignment barrier 이후에만 producer prefix를 보낸다. 아직 fixture implementation이 없을 때 `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.dataLeaderFailover' --max-workers=1 --console=plain`은 RED여야 하며, targeted 명령의 module deadline은 해당 독립 JVM 진단에만 적용된다.

- [x] **Step 2: prefix/fault/suffix/rejoin 구현**

  AdminClient로 partition `0`의 실제 pre-fault leader와 replicas/ISR를 찾고 topology evidence를 쓴다. effective `unclean.leader.election.enable=false`도 이 시점에 assertion한다. `PREFIX_EVENTS=4`의 logical ID 집합을 같은 producer instance로 모두 submit한 뒤 하나의 shared phase remaining으로 ack를 await하고 consumer baseline applied ID를 확인한 뒤에만 leader broker를 `stopBroker`한다. stop 대상이 pre-fault leader가 아니거나 pre-fault ISR가 3이 아니면 상태를 기록하고 실패한다. 새 leader가 15초 안에 metadata에 나타나고 `newLeader != stoppedLeader`, `newLeader ∈ preFaultIsr`인지 확인하며 stopped broker endpoint가 외부 metadata에 남지 않는지 검사한다. 같은 producer로 `SUFFIX_EVENTS=4`를 모두 submit하고 metadata refresh/reconnect 후 하나의 shared suffix remaining으로 4개 ack, partition `0`, prefix∪suffix의 exact expected logical ID set, raw=`8` 이상 가능성·applied unique=`8`·conflict=`0` invariant를 확인한다. stopped broker를 replacement container로 시작하고 partition replicas/ISR가 3으로 돌아오는 것을 30초 안에 기다린다. 각 phase는 18개 evidence field와 고정 phase enum을 기록한다.

  모든 phase와 blocking future/close는 class module → scenario → phase의 `min` remaining을 전달하고, `KafkaFailoverRetry`의 allowlist/attempt cap/backoff를 사용한다. timeout/producer exception/consumer close는 cancellation 및 callback quiescence 뒤 bounded cleanup(전체 `10s`)으로 이어진다. test는 at-least-once transport와 application-level dedup만 주장하며 exactly-once 업무 효과를 문서·assertion에 넣지 않는다. 각 phase evidence는 18개 field를 모두 저장하고 unavailable scalar 표현을 고정하며, phase 순서·현재 runId·exact ID union을 검증한다.

- [x] **Step 3: targeted GREEN 검증**

  Docker prerequisite 확인 후 `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.dataLeaderFailover' --max-workers=1 --console=plain`을 단독 실행한다. expected result는 fresh 3-broker startup, topic → assignment → internal config 순서, prefix exact baseline, actual pre-fault leader stop, new leader identity/≤15초, suffix batch ack, exact ID union, replacement ISR 3, 18-field evidence JSONL 생성이다. 실패하면 재시도 횟수를 늘리지 말고 listener/metadata/replication 원인을 redacted state로 조사한다.

## Task 7: 독립 `group-coordinator-failover` integration scenario 구현

**Files:**

- Modify: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverIntegrationTest.kt`

- [x] **Step 1: coordinator scenario RED fixture 작성**

  두 번째 test method는 class-level module deadline의 남은 시간을 상속하되 Task 6과 자원·group·evidence를 공유하지 않는 fresh fixture와 fresh `KafkaFailoverResourceScope`를 만든다. user topic 생성 → consumer assignment barrier → `__consumer_offsets` 생성/effective internal config 확인 순서를 지킨다. assignment callback이 최소 한 번 발생한 뒤 AdminClient로 pre-fault group coordinator와 세 partition leader를 읽고, coordinator와 다른 leader broker를 가진 partition을 동적으로 선택한다. 가능한 partition이 없으면 skip하지 않고 topology evidence와 함께 실패한다. precondition, pre-coordinator, pre-data-leader, selected partition, `COORDINATOR_PREFIX_EVENTS=4`, `COORDINATOR_SUFFIX_EVENTS=2`와 exact logical ID assertion을 먼저 둔다.

- [x] **Step 2: coordinator stop/recovery 구현**

  선택 partition에 `COORDINATOR_PREFIX_EVENTS=4`를 모두 submit하고 하나의 shared phase remaining으로 ack한 뒤 assignment baseline, callback count, generation과 exact prefix ID set을 기록한다. data leader와 pre-fault coordinator가 서로 다름을 재확인한 뒤 data leader는 유지한 채 현재 coordinator broker만 `stopBroker`한다. AdminClient가 15초 안에 `newCoordinator != preCoordinator`를 반환하고 선택 partition의 data leader가 fault 전과 동일하며 consumer assignment generation과 callback count가 증가하는지 확인한 뒤에만 같은 consumer/group과 같은 producer로 suffix 2개를 모두 submit하고 shared suffix remaining으로 await한다. suffix의 실제 fetch, prefix∪suffix exact logical ID set, raw/applied/conflict count(`applied unique=6`, `conflict=0`), 선택 partition assignment를 검증하고 replacement coordinator broker가 다시 시작되어 관련 replicas/ISR 3으로 회복되는 것을 30초 안에 확인한다. coordinator failure를 data-leader scenario의 조건부 branch로 대체하지 않으며 모든 phase에 18개 evidence field/고정 enum을 기록한다.

- [x] **Step 3: targeted GREEN 검증**

  `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.groupCoordinatorFailover' --max-workers=1 --console=plain`을 sequential로 실행한다. expected result는 coordinator/leader 분리와 pre/post identity evidence, new coordinator, 증가한 generation/callback, 선택 partition assignment, suffix fetch/applied exact ID union, replacement/ISR 3이다. topology precondition이 불가능하면 테스트는 PASS/skip가 아니라 allowlisted summary를 가진 FAIL이다.

## Task 8: README, module map, CI/nightly/smoke/stale-check registration

**Files:**

- Create: `messaging/kafka-multi-broker-failover/README.md`
- Create: `messaging/kafka-multi-broker-failover/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `.github/workflows/nightly.yml`
- Modify: `scripts/smoke-validate.sh`
- Modify: `scripts/validate-readme-parity.mjs`
- Create: `scripts/validate-kafka-failover-readme.mjs`
- Create: `scripts/validate-kafka-failover-artifacts.sh`
- Create: `scripts/with-kafka-failover-lock.sh`
- Modify: `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md`

- [x] **Step 1: README RED parity fixture 작성**

  module `README.md`와 `README.ko.md`의 구조/코드 block/first-run command/topic/field/evidence sample/prerequisite를 비교하는 RED 검사를 먼저 작성하고, 구현 GREEN에서는 repository helper에 추가한 scoped path 옵션 `node scripts/validate-readme-parity.mjs messaging/kafka-multi-broker-failover/README.md`, 의미 계약 전용 `node scripts/validate-kafka-failover-readme.mjs messaging/kafka-multi-broker-failover`, `node scripts/validate-readme-language.mjs`를 실행한다. 의미 validator는 정규화된 두 문서에서 첫 실행 명령, Docker/Colima preflight 조건, topic, 18-field schema, evidence 해석 규칙, unsupported 범위, 실패 runbook 명령을 exact equality로 비교한다. 기존 전체 helper 실행 결과는 baseline failure 목록과 비교해 기존 3건을 악화시키지 않았음을 기록하고, 새 module pair는 scoped 구조/의미 결과가 각각 0 failure여야 한다. 두 문서는 focused `data-leader-failover`와 `group-coordinator-failover` 명령, fresh 3-broker fixture, 약 7분 module budget을 먼저 안내하며 `bootRun`, external Kafka credential, public endpoint를 지원하지 않는다고 명시한다. #555는 Toxiproxy TCP path, #558은 3-broker leader/ISR, #559는 fixture implementation이 아닌 black-box behavior/evidence consumer라는 boundary table을 양쪽에 동일하게 둔다.

- [x] **Step 2: docs와 module map 구현**

  module README는 KRaft topology, `data-leader-failover`, `group-coordinator-failover`, at-least-once/dedup 범위, exactly-once 비주장, Docker/Colima prerequisite를 양 locale에 동일한 의미로 설명한다. preflight는 `colima status`가 Running, `docker context show`가 의도한 local context, `docker info`가 Server 응답, `java -version`이 Java 25, Docker network와 승인 digest image pull이 성공해야 한다는 조건과 실패 시 중단 규칙을 포함한다. `build/reports/kafka-failover/{runId}/evidence.jsonl`, `performance.jsonl`, `broker-{brokerId}.log` redacted summary의 위치와 예시를 설명하고 terminal `PASS/FAIL`, 고정 phase row, duplicate로 인한 `rawDeliveryCount` 증가 허용, `appliedCount`, `conflictCount`, Admin retry와 producer retry 분리, eventId/payload redaction을 해석 규칙으로 명시한다. digest/listener drift, leader/coordinator precondition 실패, ISR timeout, stale lock/orphan inspection, sanitizer 실패 시 artifact 업로드 차단을 위한 실제 read-only 명령과 처분을 runbook으로 둔다. external Kafka, ZooKeeper, Kafka Connect, cloud credential, XA, distributed transaction, production deployment는 지원하지 않으며 #558의 3-broker/ISR 결과는 이 fixture의 재현 증거이지 universal guarantee가 아님을 명시한다. #559는 18-field redacted JSONL과 terminal evidence만 소비하고 #558 fixture/package를 import하지 않는다. root README 양 locale의 serialization/messaging 표에 Advanced `messaging-kafka-multi-broker-failover` 행과 focused command를 같은 위치로 추가하고 module tree/module map에 directory를 추가한다. dependency snippet은 `bluetape4k-dependencies` BOM과 catalog alias만 사용한다.

- [x] **Step 3: CI/nightly/smoke/stale-check 구현**

  `.github/workflows/Examples.yml`의 pull과 push path filter 양쪽에 `messaging/kafka-multi-broker-failover/**`를 추가하고 container-backed command에 `:messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest' --max-workers=1`를 기존 Kafka 항목 뒤에 둔다. report artifact에는 module의 `build/test-results/test/*.xml`, `build/reports/tests/test/`, `build/reports/kafka-failover/**`를 추가하고 `if-no-files-found: error` 및 test 시작 시 report directory pre-create를 고정한다. nightly의 aggregate `test`가 settings auto-discovery로 이 module을 이미 한 번 포함하므로 explicit module task를 중복 추가하지 않고, nightly artifact path와 module-specific report verification만 추가해 정확히 한 번 실행됨을 확인한다. validation matrix `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md`의 T3 전체 module 목록과 직렬화/메시징 Testcontainers 명령에 `:messaging-kafka-multi-broker-failover`와 Kafka 3-broker/Colima 인프라를 같은 branch에서 추가하고 exact task count를 검증한다. `container-examples` job의 기존 `timeout-minutes: 35`는 새 module의 7분 budget과 기존 최악 실행 여유를 합산해 `45`분으로 상향하고, nightly 전체 job budget도 같은 방식으로 재검토한다. `scripts/with-kafka-failover-lock.sh`는 atomic `mkdir` 기반 `/tmp/bluetape4k-kafka-failover.lock`에 PID/worktree metadata를 기록하고 trap으로 자기 lock만 제거하며, 다른 live PID lock은 fail-fast하고 stale lock은 read-only 진단 후 명시적 안전 처분 없이는 삭제하지 않는다. Examples/nightly integration command와 local targeted commands를 이 guard로 감싼다. 각 workflow는 `KAFKA_FAILOVER_RUN_ID`를 생성한 뒤 `./scripts/validate-kafka-failover-artifacts.sh --module messaging/kafka-multi-broker-failover --run-id "$KAFKA_FAILOVER_RUN_ID" --staging build/reports/kafka-failover/sanitized` sanitizer step을 upload 직전에 실행하고, PASS 시에만 sanitized staging/JUnit artifact를 업로드한다. scanner failure/missing directory는 fail-closed한다. `scripts/validate-kafka-failover-artifacts.sh`는 current runId artifact, JUnit XML/HTML, captured stdout/stderr, CI staging을 재귀 스캔하고 canary corpus/변형·cause/suppressed/raw log를 검출하며 PASS 시에만 sanitized staging을 만든다. 각 workflow는 `if: always()` sanitizer step을 upload 직전에 두고 `steps.kafka_failover_sanitize.outcome == 'success'`일 때만 artifact upload를 허용하며, scanner failure/missing directory는 fail-closed한다. `scripts/smoke-validate.sh`의 `messaging` daily smoke에는 Testcontainers-heavy module을 넣지 않고, help/stale-check required module registration에 실제 module path와 README pair를 추가한다. required module 누락/stale ref는 warning이 아닌 non-zero로 종료하도록 scoped validation을 추가한다. module 등록은 root settings auto-discovery로 증명하며 별도 include를 중복 작성하지 않는다. custom integration task를 추가하는 경우 repository `test-mutex` 연결을 검증한다.

- [x] **Step 4: docs/registration GREEN 검증**

  다음을 순서대로 실행한다: `./gradlew projects --console=plain`, nightly aggregate task graph에서 새 module이 정확히 한 번 포함되는지 확인, `./scripts/smoke-validate.sh stale-check`, `node scripts/validate-readme-parity.mjs messaging/kafka-multi-broker-failover/README.md`, `node scripts/validate-kafka-failover-readme.mjs messaging/kafka-multi-broker-failover`, `node scripts/validate-readme-language.mjs`, 전체 `node scripts/validate-readme-parity.mjs` baseline 비교, validation matrix에서 T3/메시징 명령의 module path와 task count 확인, `./scripts/validate-kafka-failover-artifacts.sh --help`, README locale/semantic parity 검사, `git diff --check`, `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` 또는 설치되지 않은 경우 YAML parse와 exact changed-block inspection. expected result는 project graph 1개 등록, aggregate/nightly 중복 실행 0, validation matrix/stale/smoke required files 충족, scoped structural/semantic README failure 0, baseline failure 비증가, broken README image 0, sanitizer PASS gate와 syntax PASS다.

## Task 9: source-backed architecture/sequence diagram과 lesson 작성

**Files:**

- Create: `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.svg`
- Create: `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.png`
- Create: `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg`
- Create: `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.png`
- Create: `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-semantic-ledger.json`
- Create: `docs/lessons/2026-08-26-issue-558-kafka-multi-broker-failover.md`

- [x] **Step 1: diagram source/asset RED 점검**

  `$bluetape-diagram`의 `references/common.md`, `references/architecture.md`, `references/sequence.md`, `references/semantic-ledger.md`를 적용한다. `messaging-kafka-readme-message-sequence-01.png`와 `kafka-outbox-fallback-readme-sequence-01.png`를 full-size reference로 열어 palette/marker/row-height 기준을 기록한다. `messaging-kafka-multi-broker-failover-semantic-ledger.json`에 reader question, source paths, unique node IDs, closed edges, complexity budget을 먼저 작성하고 다음 명령으로 RED/입력 오류를 확인한다: `python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-semantic-audit.py --repo-root . --json docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-semantic-ledger.json`. architecture diagram은 Spring Boot client, mapped PLAINTEXT bootstrap, three brokers, KRaft controller quorum, replica/ISR와 #555 path boundary를 보여주고, sequence diagram은 produce → leader stop → metadata refresh → new leader → consumer recovery → replacement/rejoin → ISR catch-up 순서를 보여준다. #555 TCP path와 #558 cluster failover는 서로 다른 색상/범례로 구분하고, README가 참조할 PNG가 아직 없음을 asset check의 RED로 고정한다.

- [x] **Step 2: SVG/PNG pair와 README link 구현**

  기존 `docs/images/readme-diagrams` naming/layout을 따르고 SVG와 PNG를 같은 basename으로 생성한다. SVG text는 한국어 또는 API identifier를 사용하며 image에 bootstrap URL, payload, credential, owner token을 삽입하지 않는다. module README 양쪽에서 architecture/sequence PNG를 참조한다. 두 SVG를 `python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-svg-text-normalize.py docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.svg docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg`로 검사하고, `cairosvg docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.svg -o docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.png -s 2`와 `cairosvg docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg -o docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.png -s 2`로 PNG를 렌더링하며 명령 출력과 renderer version을 ledger에 기록한다.

- [x] **Step 3: lesson 구현과 visual/semantic audit**

  `docs/lessons/2026-08-26-issue-558-kafka-multi-broker-failover.md`에 KRaft listener URI, dynamic leader/coordinator selection, replacement-container semantics, ISR catch-up, cumulative deadline/cancellation/cleanup, evidence redaction, #555/#559 boundary와 재사용 가능한 교훈을 한국어로 기록한다. semantic audit를 다시 실행한 뒤 `python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-sequence-style-audit.py docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg`, `python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-visual-audit.py --json docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.png docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.png`, `python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-asset-pair-audit.py --asset-dir docs/images/readme-diagrams --readme messaging/kafka-multi-broker-failover/README.md --require-all-referenced`, XML parse와 PNG full-size inspection을 실행한다. `git diff --check`, README broken-image scan과 `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/lessons/2026-08-26-issue-558-kafka-multi-broker-failover.md`도 통과시킨다. 모든 audit row는 dimensions, aspect, occupancy, margins, marker/connector/label counts와 failures=0을 기록한다.

## Task 10: 전체 순차 검증, canary와 failure artifact 검증

**Files:**

- No planned file changes; only files listed in Tasks 1–9 may be corrected when fresh targeted evidence exposes a defect

- [x] **Step 1: fresh unit/compile validation**

  `./gradlew :messaging-kafka-multi-broker-failover:cleanTest --no-build-cache --max-workers=1 --console=plain` 후 container-free tests를 실행한다: `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaFailoverRuntimeContractTest' --tests '*KafkaFailoverEventTest' --tests '*KafkaFailoverCodecTest' --tests '*KafkaFailoverKafkaConfigurationTest' --tests '*KafkaFailoverEvidenceTest' --tests '*KafkaFailoverDeadlineTest' --tests '*KafkaFailoverRetryTest' --tests '*KafkaFailoverClientFactoryTest' --max-workers=1 --console=plain`. expected result는 모든 unit/helper test PASS이며 stale cache output을 사용하지 않는다.

- [x] **Step 2: fresh Testcontainers integration validation**

  Docker/Colima evidence를 다시 확인하고 `./scripts/with-kafka-failover-lock.sh --`로 cross-process/worktree integration lock을 획득한 뒤 두 targeted scenario를 반드시 순차 실행한다: `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.dataLeaderFailover' --max-workers=1 --no-build-cache --console=plain`, 다음 명령 `./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.groupCoordinatorFailover' --max-workers=1 --no-build-cache --console=plain`, 마지막으로 full module command `./gradlew :messaging-kafka-multi-broker-failover:test --max-workers=1 --no-build-cache --console=plain`. targeted commands는 독립 diagnostic budgets이고 full module invocation만 class-level shared `ModuleDeadline`에서 두 fresh fixture total `420s(7m)` 이내를 증명한다. lock을 얻지 못하거나 다른 worktree/process가 integration을 실행 중이면 fail-fast하고 병렬 실행하지 않는다. 실패 시 동일 command를 무분별하게 반복하지 않고 container logs, AdminClient summary, image digest, bind proof를 분류한다.

- [x] **Step 3: evidence/JUnit/CI artifact canary 검증**

  `KAFKA_FAILOVER_RUN_ID`를 current run identifier로 export한 뒤 `./scripts/validate-kafka-failover-artifacts.sh --module messaging/kafka-multi-broker-failover --run-id "$KAFKA_FAILOVER_RUN_ID" --staging build/reports/kafka-failover/sanitized`를 실행한다. 이 명령은 `build/reports/kafka-failover/{runId}/evidence.jsonl`, `performance.jsonl`, `broker-{brokerId}.log`, module JUnit XML/HTML, Gradle stdout/stderr capture, CI staging directory와 workflow artifact path를 읽어 synthetic payload/eventId/bootstrap endpoint/credentials/env/owner token, URL-encoded/escaped 변형, cause/suppressed raw exception/stack trace, raw container log가 어느 산출물에도 없는지 재귀 fail-closed scanner로 검사한다. evidence line 수는 current `runId`의 고정 phase enum/순서와 일치하고 18개 field/exact ID union/terminal status를 가지며 `imageDigest`가 `KafkaFailoverClusterFixture.kt`의 승인 상수와 일치해야 한다. scanner 오류·누락 directory·leak 발견 시 sanitizer가 먼저 실패하고 upload step은 실행되지 않는다. `if: always()`는 sanitizer에만 허용하고 upload는 sanitizer success 조건으로 gate하는지 YAML block inspection으로 증명한다.

- [x] **Step 4: full module/Kover ordering와 static checks**

  full module test가 PASS한 뒤에만 `./gradlew :messaging-kafka-multi-broker-failover:koverXmlReport --max-workers=1 --console=plain`을 report-only로 실행한다. 이어 `./gradlew detekt --max-workers=1 --console=plain` 또는 affected module detekt task, `./gradlew :messaging-kafka-multi-broker-failover:compileKotlin :messaging-kafka-multi-broker-failover:compileTestKotlin --max-workers=1 --console=plain`, `git diff --check`를 실행한다. final diff search는 production `!!`, `println`, `System.out`, `System.err`, suspend `runCatching`, `spring.json.trusted.packages=*`, default typing, `latest`, fixed host port, host network, Testcontainers import in `src/main`이 0건이어야 한다. `performance.jsonl`의 elapsed/deadline/call/ack/poll/retry/cleanup/max buffered records/bytes와 `max.poll.records`/fetch/collector ceiling을 함께 확인한다. dependency verification 또는 repository가 제공하는 resolved graph/checksum 검사가 있으면 exact allowed coordinate/version과 unexpected transitive dependency 0건을 증명한다.

## Task 11: final Kotlin checklist, independent review, commit과 PR gate 준비

**Files:**

- Modify: implementation/docs/workflow files only for findings from final review
- Create: `docs/review/2026-08-26-issue-558-kafka-multi-broker-failover-plan-review.md`

- [x] **Step 1: Kotlin final checklist 렌더링**

  `references/checklist.md`의 KT-FIN-01..11과 triggered `KT-TEST-01..05`, `KT-SPR-01..05`, `KT-MOD-01..04`를 현재 final diff와 fresh outputs에 매핑한다. 각 N/A는 “이 모듈에 Exposed/HTTP/HC5/benchmark/production coroutine API가 없음”처럼 concrete evidence를 갖고, unresolved IDE diagnostic은 compile/test fallback으로 설명한다. X=Y, Blocked=0, P0=0/P1=0을 만족하지 못하면 completion을 주장하지 않는다.

- [x] **Step 2: six-lens plan/implementation review**

  plan과 구현 diff에 대해 performance, stability, security, operator/ops, developer/API, user/caller six lens를 독립 read-only lane으로 실행한다. 각 lane은 exact file:line/evidence, P0/P1/P2/P3와 required edit만 반환하며 source/issue/PR/commit을 직접 수정하지 않는다. performance는 blocking/round trip/allocations/7m budget, stability는 deadline/cancel/cleanup/replacement, security는 digest/bind/redaction/codec, ops는 artifact/rollback/nightly, API는 package/source-set/Gradle, user는 README misuse/boundary를 확인한다. P0/P1가 있으면 해당 artifact를 고치고 영향 lane을 rerun한다. P2/P3는 수정하거나 후속 issue와 rationale을 남긴다.

- [x] **Step 3: plan/implementation artifact와 Lore commit**

  계획 승인 후 구현이 끝나면 plan checkbox와 review artifact를 최신 상태로 갱신한다. commit message는 한국어 intent line과 다음 Lore trailer를 포함한다: `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested`. `git status --short --branch`, `git diff --check`, `git log -1 --format=%B`를 확인하고 관련 없는 변경이 없음을 증명한다. 이 단계에서는 PR 생성/merge/issue close를 실행하지 않는다.

- [x] **Step 4: PR 전 정지 지점**

  PR을 만들기 전에 exact head, local tests, evidence/canary, README parity, workflow registration, six-lens P0/P1 결과와 `## DoD Status` 초안을 수집한다. PR creation은 대상 repository/base/head가 명시된 별도 gate이며 merge/close는 fresh explicit approval 없이는 실행하지 않는다. 이 계획의 stop condition은 구현·검증·pre-PR review가 완료되고 PR/merge approval을 기다리는 상태다.

## 위험, rollback, 재실행 규칙

| 위험 | fail signal | rollback/재실행 |
|---|---|---|
| Kafka image tag/digest drift | `RepoDigests` 불일치 또는 startup log 미도달 | fallback 없이 fixture startup을 실패시키고 승인된 digest/source 확인 후 Task 3부터 재실행 |
| KRaft listener/quorum drift | metadata에 container hostname/controller port 노출 | config를 수정하고 Task 3 fixture test부터 순차 재실행 |
| deepStart hang/partial startup | cumulative deadline 초과 | future cancel, reverse cleanup, orphan read-only inspection 후 Task 3 재실행 |
| leader/coordinator precondition 부재 | dynamic target이 실제 leader/coordinator가 아님 | skip하지 않고 topology evidence FAIL, Task 6/7 선택 로직 수정 |
| producer/consumer duplicate/conflict | raw/applied/fingerprint counts 불일치 | retries를 늘리지 않고 Task 4/5 collector와 client metadata 조사 |
| replacement ISR 미복구 | ISR가 3에 도달하지 않음 | persistent-volume/process restart로 우회하지 않고 replacement config/rejoin을 조사 |
| Docker bind/security evidence 부족 | loopback-only를 입증할 수 없음 | 보안 경계를 주장하지 않고 test FAIL/PENDING으로 보고 |
| CI/JUnit artifact secret leak | synthetic canary 발견 | artifact upload 전 fail-closed, redaction/canary 범위를 수정하고 Task 4/10 rerun |
| unrelated dirty worktree/failed cleanup | status에 외부 변경 또는 cleanup exception | 변경을 보존하고 safe-git-cleanup 규칙으로 대상만 재확인; broad reset/stash 금지 |

## 계획 완료 기준

- [x] 계획 자체가 승인되고 `docs/superpowers/plans/2026-08-26-issue-558-kafka-multi-broker-failover-plan.md`가 Korean terminology audit, placeholder scan, `git diff --check`를 통과한다.
- [x] 구현 후 `./gradlew projects`, container-free targeted tests, 두 개의 sequential fresh-fixture scenario, full module test, report-only Kover 대신 concrete N/A, detekt/compile이 fresh evidence를 가진다.
- [x] leader 이동, coordinator recovery, producer reconnect, consumer dedup/conflict, replacement ISR 3, image digest/local bind, evidence canary가 모두 PASS다.
- [x] README locale parity, root/module map, Examples/nightly/smoke/stale-check, SVG/PNG pair와 lesson이 synchronized 상태다.
- [x] Kotlin final checklist X=Y/Blocked=0, P0=0/P1=0이며 PR creation 전 exact-head DoD가 준비된다.
