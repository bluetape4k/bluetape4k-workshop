# Issue #558 Multi-broker Kafka Failover Reference 설계

## 문제와 목표

기존 `messaging-kafka`는 단일 Testcontainers broker를 사용하는 기본 publish/consume 예제다. `commerce/usage-billing-microservices-composition-tests`의 #555는 `Toxiproxy`로 단일 broker의 TCP 경로를 끊었다가 복구하는 시나리오다. 두 예제 모두 Kafka 클러스터에서 broker가 내려가고 다른 broker가 partition leader가 되는 과정을 증명하지 않는다.

이 설계는 Java 25와 Spring Boot로 독립적인 3-broker KRaft reference를 추가한다. 테스트는 partition leader 중단, metadata 갱신, producer 재연결, consumer group recovery, broker 재가입과 ISR 복구를 관측한다. 예제는 작은 고정 workload를 사용하는 교육용 reference이며 throughput/latency benchmark가 아니다. 보장은 Kafka transport의 at-least-once 범위로 한정하고, exactly-once 처리나 업무 데이터 정합성은 주장하지 않는다.

## 현재 근거

### 로컬 구현과 경계

- `messaging/kafka/build.gradle.kts`는 `spring-kafka`, `kafka-clients`, `testcontainers-kafka`를 사용하지만 single-broker fixture만 제공한다.
- `messaging/kafka/src/main/resources/application.yml`은 `${testcontainers.kafka.bootstrapServers}` 하나를 bootstrap endpoint로 사용한다.
- #555의 `UsageBillingMicroserviceFixture`는 `KafkaContainer` 한 개와 `Toxiproxy`를 사용하고 topic replication factor가 `1`이다. 이 시나리오는 TCP path recovery로 유지한다.
- `settings.gradle.kts`의 `includeModules("messaging", false, true)`는 `messaging` 아래의 새 하위 디렉터리를 `messaging-...` 프로젝트로 자동 등록한다. README, workflow, smoke, stale-check 표면은 자동으로 갱신되지 않는다.
- 현재 `develop` worktree와 feature worktree 모두 시작 시 clean이며, 관련 eco worktree는 이 작업의 범위에서 제외한다.

### 외부 API와 버전 근거

- Testcontainers `2.0.5`의 `org.testcontainers.kafka.KafkaContainer`는 `apache/kafka`와 `apache/kafka-native` 이미지를 지원하고 `getBootstrapServers()`와 추가 listener를 제공한다.
- 동일 버전 source jar의 `KafkaHelper.envVars()`는 `KAFKA_NODE_ID`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, `KAFKA_PROCESS_ROLES`, `KAFKA_LISTENERS`를 환경 변수로 구성한다. `withEnv`로 broker별 node ID와 quorum voters를 override할 수 있다.
- `containerIsStarting`은 mapped host port를 `PLAINTEXT` advertised listener로 계산하므로 host JVM client가 metadata 이후 각 broker에 직접 연결할 수 있다. broker 간 통신은 Docker network alias의 `BROKER` listener를 사용한다.
- [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/)은 Apache Kafka image, bootstrap server, listener 등록, Testcontainers dependency를 설명한다.
- [Apache Kafka design](https://kafka.apache.org/41/design/design/)은 committed record의 leader failure 이후 ISR replica 선출과 replication factor의 failure tolerance를 정의한다.

`org.testcontainers.kafka.KafkaContainer`가 single-node 기본값을 가진다는 점은 명시적 환경 변수 override로 보완한다. 구현 단계에서 현재 source/jar와 실제 image startup을 다시 확인하고, override가 동작하지 않으면 fixture를 fail-fast 한다. `GenericContainer` wrapper로의 자동 fallback은 허용하지 않으며, 별도 설계·검토·회귀 근거가 있는 경우에만 후속 변경으로 다룬다. production 코드에는 어느 경우에도 container abstraction을 노출하지 않는다.

## 선택한 설계

### 모듈과 책임

새 모듈 `messaging/kafka-multi-broker-failover`를 만든다. Gradle project name은 `messaging-kafka-multi-broker-failover`다.

| 영역 | 책임 |
| --- | --- |
| Spring Boot application | reference 실행 진입점과 Kafka client 설정의 최소 예제 제공 |
| test-only cluster fixture | 동일 KRaft cluster ID와 `KAFKA_CONTROLLER_QUORUM_VOTERS`를 공유하는 broker 3개 시작/중단/재시작 |
| test-only admin/evidence helper | topic leader, replicas, ISR, cluster node, group coordinator/assignment를 조회하고 수치화한 deadline으로 검증하며 redacted JSONL을 기록 |
| failover integration test | leader fault, producer reconnect, consumer recovery, broker restart/ISR catch-up의 관측 순서를 고정 |
| README/diagram/validation surfaces | path interruption와 cluster failover를 구분하고 실행 명령과 증거를 설명 |

production source는 domain 업무 모델이나 공통 Kafka failover API를 추가하지 않는다. logical event ID를 포함한 작은 reference payload와 publisher/listener wiring만 두고, 장애 주입과 상태 관측은 test source에 둔다.

### Broker topology

세 broker는 하나의 Testcontainers `Network`에 `kafka-1`, `kafka-2`, `kafka-3` alias로 붙인다. 고정 host port나 host network는 사용하지 않는다.

- 각 broker는 catalog의 Kafka 4.x line과 맞춘 `KafkaContainer(DockerImageName.parse("apache/kafka:4.2.0"))`를 사용한다. 이 tag는 구현 전까지 provisional이며, startup과 확인한 image digest를 증명한 뒤에만 README와 CI에 고정한다. 구현 시 승인된 digest를 image reference로 직접 사용하거나 runtime `RepoDigests`가 승인값과 일치하지 않으면 fail-fast 한다. `latest` 또는 자동 tag fallback은 허용하지 않는다.
- `KAFKA_NODE_ID`는 `1`, `2`, `3`으로 고정한다.
- `KAFKA_PROCESS_ROLES`는 `broker,controller`로 두고, `KAFKA_CONTROLLER_QUORUM_VOTERS`는 `1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094`로 고정한다.
- `KAFKA_LISTENERS`는 유효한 URI 형식인 `PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094`를 사용한다.
- `KAFKA_INTER_BROKER_LISTENER_NAME=BROKER`, `KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER`, listener security map은 세 listener를 모두 `PLAINTEXT`로 명시한다.
- `KafkaContainer`가 생성하는 `PLAINTEXT` advertised listener는 각 broker의 mapped host port이며, `BROKER` advertised listener는 Docker network 내부 hostname이다. application client는 세 broker의 `getBootstrapServers()`를 comma-separated bootstrap list로 사용하고, metadata endpoint가 host JVM에서 접근 가능한 mapped port인지와 broker/controller endpoint가 외부 client에 섞이지 않는지를 각각 확인한다.
- 사용자 topic은 `kafka-failover-reference`, partition `3`, replication factor `3`, `min.insync.replicas=2`로 고정한다. broker 환경 변수 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false`, `KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS=3`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2`를 명시한다. `__consumer_offsets`와 transaction state log는 consumer group을 실제로 연결한 뒤 내부 topic의 effective replicas/ISR을 확인한다. AdminClient로 broker/topic의 effective config와 unclean election 비활성 상태를 assertion한다.
- 세 broker는 공통 cluster ID를 사용하고, controller quorum이 준비될 때까지 log-based wait와 AdminClient 상태 확인을 함께 통과해야 한다.

fixture의 `start()`는 `KafkaContainer` 생성자가 가진 기본 wait strategy를 broker마다 독립적인 `Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1).withStartupTimeout(Duration.ofSeconds(45))`로 교체하고, `Startables.deepStart(brokers.stream())`가 반환한 future를 cumulative module deadline의 남은 시간으로 `get(remaining, MILLISECONDS)` 또는 `orTimeout(remaining, MILLISECONDS)` 처리한다. timeout 시 future를 취소하고 partial-start rollback을 수행하며, 세 container를 같은 startup 단계에 올린 뒤 cluster node 수와 controller quorum을 확인한다. `stopBroker(id)`는 중단 전에 허용된 상태 요약 로그를 저장하고 해당 container를 중단한다. Testcontainers의 stop은 container를 제거할 수 있으므로 `restartBroker(id)`는 같은 fixture 설정·node ID·network alias로 replacement container를 생성한 뒤 metadata와 ISR이 회복될 때까지 기다린다. persistent volume이나 process-only restart를 주장하지 않는다. `close()`는 AdminClient, producer, consumer를 bounded-close한 뒤 broker, network 순서로 모든 자원을 닫으며 첫 실패 뒤에도 나머지 자원을 정리하고 cleanup 실패를 suppressed failure로 보존한다.

### 구현 계약

- canonical package는 `io.bluetape4k.workshop.messaging.kafka.multibroker.failover`, Spring Boot main class는 `KafkaMultiBrokerFailoverApplication`으로 고정한다. `springBoot { mainClass.set(...) }`를 사용하며, 첫 실행과 수용 기준은 `bootRun`이나 외부 Kafka가 아니라 Testcontainers integration test다.
- `src/main`은 Kafka client wiring과 reference event만 제공하고 Testcontainers를 import하지 않는다. `KafkaContainer`, `Network`, AdminClient fixture, broker fault와 evidence writer는 `src/test`에만 둔다. Testcontainers 의존성은 `testImplementation`으로만 선언한다.
- Spring shell은 `spring.main.web-application-type=none`으로 HTTP server를 열지 않으며 `spring-boot-starter-actuator`를 추가하지 않는다. bootstrap 주소·환경 변수·payload를 HTTP endpoint나 health 상세정보로 노출하지 않는다.
- event codec은 명시적 JSON 문자열 schema와 `StringSerializer`/`StringDeserializer`를 test와 main에서 동일하게 사용하며 Spring Kafka type header를 생성하지 않는다. `spring.json.trusted.packages=*`나 polymorphic default typing을 사용하지 않고, 허용 필드 외 입력과 동일 `eventId`의 payload fingerprint 충돌을 거부한다.

### Event and client contract

reference event와 routing 계약은 다음과 같다.

```text
eventId: stable logical identity
sequence: deterministic order within the test batch
payload: short reader-facing value
partitionKey: `failover-partition-0`
topic: `kafka-failover-reference`
partition: deterministic target partition `0` (coordinator 시나리오는 사전조건에 따라 다른 partition을 선택)
```

producer는 `ProducerRecord(topic, 0, partitionKey, encodedEvent)`로 target partition을 명시하고 `RecordMetadata.partition == 0`을 확인한다. `acks=all`, `retries=3`, `delivery.timeout.ms=20s`, `request.timeout.ms=5s`, `max.block.ms=10s`, `retry.backoff.ms=200`, `retry.backoff.max.ms=2s`, `enable.idempotence=true`를 사용한다. idempotent producer 설정은 retry 중 wire duplicate를 줄이는 client 설정일 뿐, exactly-once 업무 효과를 의미하지 않는다. consumer는 `enable.auto.commit=false`, `auto.offset.reset=earliest`, `session.timeout.ms=10s`, `heartbeat.interval.ms=3s`, `max.poll.interval.ms=20s`, 고정 test group ID와 `AckMode.MANUAL`을 사용한다. AdminClient는 `request.timeout.ms=5s`, `default.api.timeout.ms=10s`를 사용한다. test collector는 `eventId`를 application-level dedup key로 기록하되 동일 ID의 payload fingerprint 충돌을 실패로 처리하고 raw delivery, applied unique, conflict count를 별도로 기록한다.

### 장애 관측 흐름

공통 준비 단계는 controller quorum 확인 직후 AdminClient로 `auto.create.topics.enable=false`를 유지한 채 사용자 topic을 명시 생성하고 partition `3`, replication factor `3`, `min.insync.replicas=2`의 effective 설정을 먼저 확인하는 것이다. 그 다음 consumer를 연결해 assignment barrier를 통과시키고 `auto.offset.reset=earliest`로 group을 연결한다. 이 연결이 `__consumer_offsets`를 생성한 뒤 내부 topic의 effective replicas/ISR을 확인한다. 이후 다음 두 시나리오를 서로 다른 test method와 fresh fixture로 실행한다.

### `data-leader-failover` 시나리오

1. AdminClient로 세 broker와 `kafka-failover-reference`의 partition `0`, replicas `3`, ISR `3`을 확인하고 leader/route 상태 기록을 저장한다.
2. `PREFIX_EVENTS=4`를 producer로 전송하고 각 `RecordMetadata` ack와 consumer baseline 적용 ID를 확인한다. ack가 없는 상태에서 fault를 주입하지 않는다.
3. partition `0`의 실제 leader broker를 동적으로 찾아 중단한다. 다른 ISR broker가 새 leader가 되고, AdminClient metadata가 bounded timeout 안에 바뀌는지 확인한다.
4. 같은 producer 인스턴스로 `SUFFIX_EVENTS=4`를 전송한다. client metadata refresh/reconnect 후 모든 ack가 완료되고, collector의 적용 ID가 기대 logical ID 집합과 일치하는지 확인한다.
5. 중단한 broker를 replacement container로 재기동하고 해당 partition의 ISR에 재가입하는지, replicas/ISR 수가 `3`으로 돌아오는지 확인한다.

### `group-coordinator-failover` 시나리오

1. fresh fixture에서 consumer assignment를 활성화하고 AdminClient로 group coordinator와 각 partition leader를 조회한다. coordinator와 다른 leader를 가진 partition이 없으면 시나리오를 건너뛰지 않고 topology evidence와 함께 실패한다.
2. 선택한 partition에 prefix batch를 ack하고 assignment callback 횟수와 baseline을 기록한다. data leader는 살아 있는 상태를 유지한다.
3. 현재 coordinator broker만 중단한다. AdminClient가 새 coordinator를 반환하고 consumer assignment callback이 증가하는지 확인한다. 새 coordinator가 확인된 뒤 `COORDINATOR_SUFFIX_EVENTS=2`를 같은 consumer/group에 전달해 실제 fetch와 새 logical ID의 raw/applied count를 검증한다. 이 시나리오를 data-leader failover의 조건부 분기로 대체하지 않는다.
4. coordinator broker를 replacement container로 교체·재가입시키고 관련 replicas/ISR이 `3`으로 회복되는지 확인한다.

두 시나리오 모두 raw delivery count와 dedup 적용 count를 함께 기록해 at-least-once transport에서 duplicate delivery 가능성을 숨기지 않는다.

각 단계는 `Awaitility` 또는 기존 repository wait helper로 다음 수치를 고정해 bounded retry를 사용한다. 각 시나리오 deadline은 `180s`, 두 fresh fixture를 포함한 module deadline은 `420s(7m)`로 고정하고, 모든 phase는 이 cumulative deadline의 남은 시간으로 계산한다. broker 동시 startup/quorum은 `45s`, metadata/leader 전환은 `15s`, producer prefix delivery는 `15s`, producer suffix delivery는 `20s`, consumer assignment/fetch 또는 coordinator recovery는 `20s`, ISR catch-up은 `30s`, cleanup은 `10s`, poll interval은 `250ms`, retry backoff는 `200ms`에서 `2s`까지 지수 증가로 고정한다. 모든 deadline은 monotonic clock으로 계산하고, 실패 메시지에 phase와 allowlist된 마지막 AdminClient 상태를 포함한다. 무한 대기, sleep 기반 고정 타이밍, broker 로그 문자열만으로 통과하는 assertion은 허용하지 않는다.

evidence는 `build/reports/kafka-failover/evidence.jsonl`에 한 줄씩 기록한다. 필드는 `runId`, `scenario`, `phase`, `image`, `imageDigest`, `topic`, `partition`, `nodeCount`, `leader`, `replicas`, `isr`, `coordinator`, `assignmentCount`, `rawDeliveryCount`, `appliedCount`, `conflictCount`, `retryCount`, `status`로 고정하며 payload, bootstrap URL, 환경 변수, credential, 전체 stack trace는 기록하지 않는다. broker 진단 로그는 중단 전에 허용된 상태 요약만 redaction해 `build/reports/kafka-failover/broker-{brokerId}.log`로 저장하고, `raw log artifact`라는 이름의 산출물은 만들지 않는다. AdminClient failure message, JUnit XML, broker summary, evidence JSONL에 synthetic payload/endpoint/credential canary가 나타나면 fail-closed 한다. Examples와 nightly workflow는 성공·실패 모두 `if: always()`로 이 report 디렉터리를 artifact에 올린다.

## 대안과 기각 이유

| 대안 | 판단 |
| --- | --- |
| 기존 `messaging-kafka`에 multi-broker profile 추가 | 기본 publish/consume와 cluster 장애 조치의 목적과 비용이 섞인다. 기존 단일 broker 학습 경로와 테스트 시간을 보존하기 어렵다. |
| #555 `UsageBillingMicroserviceFixture`를 3-broker로 확장 | #555의 TCP path recovery와 #558의 cluster failover가 같은 fixture에 결합되어 failure mechanism 독립성이 사라진다. |
| `shared`에 production Kafka cluster harness 추가 | #559가 요구하는 black-box conformance와 production abstraction 금지 경계를 위반할 수 있다. |
| raw Docker CLI 또는 외부 Kafka cluster 사용 | Testcontainers-only 재현성과 CI 격리를 잃고 외부 credential/infra를 요구한다. |
| 단일 broker stop을 cluster failover로 설명 | replication, ISR, leader election을 증명하지 못하므로 #558 수용 기준을 충족하지 않는다. |

## 실패 모드와 처분

- **quorum startup failure:** 세 alias 또는 controller listener가 준비되지 않으면 fixture startup을 실패시킨다. 일부 broker만 살아 있는 상태를 green으로 기록하지 않는다. `Startables.deepStart` timeout 뒤 GenericContainer fallback으로 통과시키지 않는다.
- **advertised listener drift:** metadata가 container 내부 hostname이나 `localhost`를 반환하면 client recovery assertion을 실패시키고 listener 구성을 수정한다. bootstrap 연결만 성공한 것을 recovery 증거로 인정하지 않는다.
- **leader does not move:** 중단한 broker가 실제 partition leader가 아니었거나 ISR이 부족한 경우를 구분해 diagnostic을 남긴다. unclean election을 켜서 통과시키지 않는다.
- **producer timeout:** bounded retry 뒤에도 publish가 끝나지 않으면 재시도 횟수를 늘려 숨기지 않고 metadata/reconnect/replication 원인을 조사한다.
- **consumer recovery timeout:** group coordinator와 data leader failure를 구분한 뒤 assignment/fetch evidence가 없으면 실패시킨다. 단순 consumer 재생성으로 회복을 우회하지 않는다.
- **ISR catch-up failure:** broker 재시작 뒤 ISR이 `3`으로 돌아오지 않으면 replication recovery를 통과시키지 않는다. 테스트 종료 시 broker를 강제 삭제하지 않고 redacted 상태 요약과 AdminClient 상태만 보존한다.
- **resource leak:** 테스트 중 예외가 발생해도 broker와 network를 역순으로 정리한다. replacement container 생성 실패와 partial startup을 rollback하고, cleanup 실패는 원래 assertion을 덮어쓰지 않고 suppressed failure로 남긴다. 종료 후에는 삭제 명령 없이 orphan container/network를 조회해 진단만 남긴다.

## 호환성과 범위

- Java 25, Kotlin 2.4.0, Spring Boot 4.0.6, Testcontainers BOM `2.0.5`의 기존 catalog를 사용한다.
- `bluetape4k-dependencies` BOM을 유지하고 개별 bluetape4k 버전을 추가하지 않는다.
- Kafka client는 기존 Kafka 4.x catalog alias를 사용한다. 이미지 tag와 확인한 digest는 구현 전에 Testcontainers source와 image startup을 확인해 고정한다. tag 변경은 설계·lesson·CI evidence를 함께 갱신한다.
- 기존 `messaging-kafka`, `messaging-kafka-reply`, `messaging-kafka-outbox-fallback`, #555 usage-billing fixture의 public behavior는 변경하지 않는다.
- 외부 Kafka, ZooKeeper, Kafka Connect, cloud credential, XA, distributed transaction, exactly-once 업무 효과는 범위 밖이다. test-only listener는 random mapped port와 local Docker runtime에서만 사용하고, 외부 접근·host network·production 배포를 지원하지 않는다.

## 수용 기준

1. `:messaging-kafka-multi-broker-failover:test --tests "*KafkaMultiBrokerFailoverIntegrationTest" --max-workers=1`가 세 broker와 하나의 KRaft controller quorum을 시작하고 cluster node 수 `3`을 확인한다.
2. `kafka-failover-reference` partition `0`의 leader, replicas `3`, ISR `3`과 internal offset topic의 effective replication을 failure 전후 AdminClient evidence로 확인한다.
3. `data-leader-failover`에서 실제 leader broker 중단 뒤 다른 ISR broker가 `15s` 안에 leader가 된다.
4. 동일 producer가 prefix ack 후 metadata refresh/reconnect를 거쳐 suffix batch를 publish하고, consumer application-level dedup 결과가 기대 logical ID 집합과 일치한다. payload fingerprint conflict는 실패한다.
5. `group-coordinator-failover`가 data leader와 독립적으로 coordinator를 중단하고 새 coordinator·assignment callback·fetch recovery를 각각 확인한다.
6. replacement broker가 재가입한 뒤 해당 partition ISR이 `3`으로 회복된다.
7. README와 README.ko.md가 #555 TCP path recovery, #558 cluster failover, #559 black-box conformance를 경계표로 구분하고, at-least-once와 exactly-once 비주장을 명시한다. 두 locale의 first-run command, topic/field, evidence sample, prerequisite가 일치한다.
8. root README, messaging module map, `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml`, nightly validation/stale-check, lesson, diagram asset pair가 새 모듈과 일치하고 report artifact가 성공·실패 모두 보존된다.
9. Testcontainers-heavy 검증은 Docker/Colima prerequisite를 확인한 뒤 다른 module/worktree와 병렬 실행하지 않고, 실패 시 원인을 조사한 뒤 재실행한다. 두 fresh fixture를 포함한 module test budget은 `7m` 이내로 고정한다.
10. 모든 broker port는 Testcontainers random mapping을 사용하고 host network·고정 port·외부 Kafka 연결이 없으며, `PLAINTEXT` mapped endpoint만 host client에 반환되고 `BROKER`/`CONTROLLER` endpoint는 외부 evidence에 나타나지 않는다. Docker runtime의 host bind가 local-only인지 확인할 수 없으면 테스트를 실패시키고 보안 경계를 주장하지 않는다.
11. 모든 evidence의 `imageDigest`가 설계 승인 digest와 일치하고, evidence·redacted broker summary·JUnit XML·CI artifact 전체에 synthetic payload/endpoint/credential canary가 남지 않는지 fail-closed 검사한다.

## 문서와 시각 자료

- `messaging/kafka-multi-broker-failover/README.md`와 `README.ko.md`는 topology, 실행 명령, failure evidence, 범위 밖 항목을 같은 구조로 설명한다.
- 두 README의 첫 실행은 Docker 또는 Colima가 준비된 환경에서 `./gradlew :messaging-kafka-multi-broker-failover:test --tests "*KafkaMultiBrokerFailoverIntegrationTest" --max-workers=1`로 고정한다. `bootRun`과 외부 Kafka credential은 지원하지 않으며, 대표 성공 출력과 실패 시 `build/reports/kafka-failover/` 진단 경로를 함께 설명한다.
- 두 README에는 `#555 = Toxiproxy TCP path`, `#558 = 3-broker leader/ISR`, `#559 = black-box behavior/evidence` 경계표를 넣고, #559는 fixture API가 아닌 외부 동작과 evidence만 소비한다고 명시한다.
- `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.svg/png`는 Spring Boot client, bootstrap metadata, 세 broker, controller quorum, replica/ISR 경계를 정적으로 보여준다.
- `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg/png`는 produce → leader stop → metadata refresh → new leader → consumer recovery → broker replacement/rejoin → ISR catch-up 순서를 보여준다.
- SVG/PNG pair와 README PNG 참조를 diagram asset audit로 확인한다. 시각 자료는 source-backed topology를 줄이지 않고, path interruption과 cluster failover를 서로 다른 색상/범례로 구분한다.

## 구현 전 위험 예측

| 위험 | 신호 | 완화 | 재실행 지점 |
| --- | --- | --- | --- |
| KRaft image 환경 변수 변화 | broker가 `RECOVERY`에서 `RUNNING`으로 전이하지 않음 | source/jar와 image startup/digest를 먼저 확인하고 tag/config를 한 곳에서 고정 | fixture startup test |
| dynamic mapped listener 오류 | metadata endpoint가 `localhost`/container-only hostname | `getBootstrapServers()`와 AdminClient metadata를 함께 검증 | leader failover test 전 |
| leader/coordinator 선택 비결정성 | 중단 후 leader 또는 coordinator가 예상과 다름 | AdminClient 상태 기록으로 대상 broker를 동적으로 선택하고 bounded wait | 각 failure scenario 시작 |
| Testcontainers resource/CI timing | retry마다 다른 timeout 또는 leaked container | sequential Gradle lane, explicit timeout, reverse cleanup, redacted broker summary artifact | module test 전체 |
| at-least-once와 dedup 의미 혼동 | raw delivery와 applied ID count가 다름 | raw/applied/conflict count와 payload fingerprint를 모두 기록하고 exactly-once 문구를 금지 | consumer recovery assertion |
| evidence/운영 재현성 부족 | 테스트 XML만 있고 마지막 cluster 상태가 없음 | 고정 JSONL schema, redacted broker summary, `if: always()` artifact와 phase budget을 사용 | CI failure artifact |

## DoD

- 새 모듈이 `./gradlew projects`와 validation surfaces에 등록된다.
- unit/container-free helper tests와 3-broker Testcontainers integration tests가 각각 통과한다.
- leader 이동, producer reconnect, consumer recovery, broker rejoin/ISR 회복의 redacted machine-readable evidence가 테스트 출력과 README에 연결된다.
- README locale parity, diagram SVG/PNG pair, semantic/visual audit, `git diff --check`가 통과한다.
- `docs/lessons/`에 KRaft listener/ISR recovery에서 얻은 재사용 가능한 lesson을 기록한다.
- pre-PR review에서 P0/P1이 0이고, PR body는 한국어로 작성하며 마지막 `## DoD Status`에서 exact head와 남은 merge gate를 보고한다.

## 미결정 사항과 구현 시 고정할 값

- Apache Kafka image의 정확한 tag와 digest는 implementation step에서 current catalog와 Docker image startup을 확인한 뒤 고정한다. 이미지를 임의로 최신으로 바꾸거나 자동 fallback하지 않는다.
- `KafkaContainer.withEnv` override가 KRaft quorum에 적용되지 않으면 primary fixture를 fail-fast 하고, GenericContainer wrapper는 별도 설계·승인·회귀 증거 없이는 도입하지 않는다.
- group coordinator failure는 독립 test scenario로 실행하며, AdminClient 결과로 data leader와 다른 coordinator broker/partition을 동적으로 선택한다. 조건부 branch나 고정 broker ID를 acceptance 대체로 사용하지 않는다.
