# Issue #558 Multi-broker Kafka Failover Reference 설계

## 문제와 목표

기존 `messaging-kafka`는 단일 Testcontainers broker를 사용하는 기본 publish/consume 예제다. `commerce/usage-billing-microservices-composition-tests`의 #555는 `Toxiproxy`로 단일 broker의 TCP 경로를 끊었다가 복구하는 시나리오다. 두 예제 모두 Kafka 클러스터에서 broker가 내려가고 다른 broker가 partition leader가 되는 과정을 증명하지 않는다.

이 설계는 Java 25와 Spring Boot로 독립적인 3-broker KRaft reference를 추가한다. 테스트는 partition leader 중단, metadata 갱신, producer 재연결, consumer group recovery, broker 재가입과 ISR 복구를 관측한다. 예제의 보장은 Kafka transport의 at-least-once 범위로 한정하고, exactly-once 처리나 업무 데이터 정합성은 주장하지 않는다.

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

`org.testcontainers.kafka.KafkaContainer`가 single-node 기본값을 가진다는 점은 명시적 환경 변수 override로 보완한다. 구현 단계에서 현재 source/jar와 실제 image startup 로그를 다시 확인하고, override가 동작하지 않으면 test-only `GenericContainer` wrapper로 좁혀 전환한다. production 코드에는 어느 경우에도 container abstraction을 노출하지 않는다.

## 선택한 설계

### 모듈과 책임

새 모듈 `messaging/kafka-multi-broker-failover`를 만든다. Gradle project name은 `messaging-kafka-multi-broker-failover`다.

| 영역 | 책임 |
| --- | --- |
| Spring Boot application | reference 실행 진입점과 Kafka client 설정의 최소 예제 제공 |
| test-only cluster fixture | 동일 KRaft cluster ID와 `KAFKA_CONTROLLER_QUORUM_VOTERS`를 공유하는 broker 3개 시작/중단/재시작 |
| test-only admin/evidence helper | topic leader, replicas, ISR, cluster node, group coordinator/assignment를 조회하고 bounded wait로 검증 |
| failover integration test | leader fault, producer reconnect, consumer recovery, broker restart/ISR catch-up의 관측 순서를 고정 |
| README/diagram/validation surfaces | path interruption와 cluster failover를 구분하고 실행 명령과 증거를 설명 |

production source는 domain 업무 모델이나 공통 Kafka failover API를 추가하지 않는다. logical event ID를 포함한 작은 reference payload와 publisher/listener wiring만 두고, 장애 주입과 상태 관측은 test source에 둔다.

### Broker topology

세 broker는 하나의 Testcontainers `Network`에 `kafka-1`, `kafka-2`, `kafka-3` alias로 붙인다.

- 각 broker는 catalog의 Kafka 4.x line과 맞춘 `KafkaContainer(DockerImageName.parse("apache/kafka:4.2.0"))`를 사용한다. 구현 단계에서 해당 tag의 startup을 확인하고, 사용할 수 없을 때만 근접한 명시적 tag로 설계를 갱신한다.
- `KAFKA_NODE_ID`는 `1`, `2`, `3`으로 고정한다.
- `KAFKA_PROCESS_ROLES`는 `broker,controller`로 두고, `KAFKA_CONTROLLER_QUORUM_VOTERS`는 `1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094`로 고정한다.
- `KAFKA_LISTENERS`는 외부 client용 `PLAINTEXT:9092`, broker 간 통신용 `BROKER:9093`, controller용 `CONTROLLER:9094`를 포함한다.
- `KAFKA_INTER_BROKER_LISTENER_NAME=BROKER`, `KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER`, listener security map은 세 listener를 모두 `PLAINTEXT`로 명시한다.
- application client는 세 broker의 `getBootstrapServers()`를 comma-separated bootstrap list로 사용한다. metadata가 반환하는 각 broker endpoint도 mapped host port를 가리키는지 확인한다.
- 사용자 topic과 `__consumer_offsets`/transaction state 내부 topic은 replication factor `3`, `min.insync.replicas=2`로 설정한다. 테스트는 unclean leader election을 허용하지 않는다.
- 세 broker는 공통 cluster ID를 사용하고, controller quorum이 준비될 때까지 log-based wait와 AdminClient 상태 확인을 함께 통과해야 한다.

fixture의 `start()`는 세 container를 함께 시작한 뒤 cluster node 수와 controller quorum을 확인한다. `stopBroker(id)`는 해당 container만 중단하고, `restartBroker(id)`는 같은 container를 다시 시작한 뒤 metadata와 ISR이 회복될 때까지 기다린다. `close()`는 broker, network 순서로 모든 자원을 닫으며 첫 실패 뒤에도 나머지 자원을 정리한다.

### Event and client contract

reference event는 다음 필드를 가진다.

```text
eventId: stable logical identity
sequence: deterministic order within the test batch
payload: short reader-facing value
```

producer는 `acks=all`, bounded `retries`, bounded delivery timeout, `enable.idempotence=true`를 사용한다. idempotent producer 설정은 retry 중 wire duplicate를 줄이는 client 설정일 뿐, exactly-once 업무 효과를 의미하지 않는다. consumer는 manual acknowledgement와 고정 group ID를 사용하고, test collector는 `eventId`를 application-level dedup key로 기록한다.

### 장애 관측 흐름

1. AdminClient로 세 broker와 replication factor `3`인 test topic을 확인하고, partition leader와 ISR 상태 기록을 저장한다.
2. deterministic event batch의 일부를 publish하고 consumer가 application-level dedup 후 적용한 ID를 기록한다.
3. 현재 partition leader broker를 중단한다. 다른 ISR broker가 새 leader가 되고, AdminClient metadata가 bounded timeout 안에 바뀌는지 확인한다.
4. 같은 producer 인스턴스로 남은 batch를 publish한다. client metadata refresh/reconnect 후 전송이 완료되고, collector의 적용 ID가 기대 집합과 일치하는지 확인한다.
5. consumer group coordinator가 중단된 경우에는 assignment callback과 새 coordinator를 관측해 group recovery를 증명한다. data leader만 이동한 경우에는 consumer가 새 leader에서 fetch를 재개하는 것을 별도 evidence로 기록한다.
6. 중단한 broker를 재시작하고 해당 partition의 ISR에 재가입하는지, replicas/ISR 수가 `3`으로 돌아오는지 확인한다.
7. raw delivery count와 dedup 적용 count를 함께 기록해 at-least-once transport에서 duplicate delivery 가능성을 숨기지 않는다.

각 단계는 `Awaitility` 또는 기존 repository wait helper로 bounded retry를 사용한다. 무한 대기, sleep 기반 고정 타이밍, broker 로그 문자열만으로 통과하는 assertion은 허용하지 않는다.

## 대안과 기각 이유

| 대안 | 판단 |
| --- | --- |
| 기존 `messaging-kafka`에 multi-broker profile 추가 | 기본 publish/consume와 cluster 장애 조치의 목적과 비용이 섞인다. 기존 단일 broker 학습 경로와 테스트 시간을 보존하기 어렵다. |
| #555 `UsageBillingMicroserviceFixture`를 3-broker로 확장 | #555의 TCP path recovery와 #558의 cluster failover가 같은 fixture에 결합되어 failure mechanism 독립성이 사라진다. |
| `shared`에 production Kafka cluster harness 추가 | #559가 요구하는 black-box conformance와 production abstraction 금지 경계를 위반할 수 있다. |
| raw Docker CLI 또는 외부 Kafka cluster 사용 | Testcontainers-only 재현성과 CI 격리를 잃고 외부 credential/infra를 요구한다. |
| 단일 broker stop을 cluster failover로 설명 | replication, ISR, leader election을 증명하지 못하므로 #558 수용 기준을 충족하지 않는다. |

## 실패 모드와 처분

- **quorum startup failure:** 세 alias 또는 controller listener가 준비되지 않으면 fixture startup을 실패시킨다. 일부 broker만 살아 있는 상태를 green으로 기록하지 않는다.
- **advertised listener drift:** metadata가 container 내부 hostname이나 `localhost`를 반환하면 client recovery assertion을 실패시키고 listener 구성을 수정한다. bootstrap 연결만 성공한 것을 recovery 증거로 인정하지 않는다.
- **leader does not move:** 중단한 broker가 실제 partition leader가 아니었거나 ISR이 부족한 경우를 구분해 diagnostic을 남긴다. unclean election을 켜서 통과시키지 않는다.
- **producer timeout:** bounded retry 뒤에도 publish가 끝나지 않으면 재시도 횟수를 늘려 숨기지 않고 metadata/reconnect/replication 원인을 조사한다.
- **consumer recovery timeout:** group coordinator와 data leader failure를 구분한 뒤 assignment/fetch evidence가 없으면 실패시킨다. 단순 consumer 재생성으로 회복을 우회하지 않는다.
- **ISR catch-up failure:** broker 재시작 뒤 ISR이 `3`으로 돌아오지 않으면 replication recovery를 통과시키지 않는다. 테스트 종료 시 broker를 강제 삭제하지 않고 원시 로그와 AdminClient 상태를 보존한다.
- **resource leak:** 테스트 중 예외가 발생해도 broker와 network를 역순으로 정리한다. cleanup 실패는 원래 assertion을 덮어쓰지 않고 suppressed failure로 남긴다.

## 호환성과 범위

- Java 25, Kotlin 2.4.0, Spring Boot 4.0.6, Testcontainers BOM `2.0.5`의 기존 catalog를 사용한다.
- `bluetape4k-dependencies` BOM을 유지하고 개별 bluetape4k 버전을 추가하지 않는다.
- Kafka client는 기존 Kafka 4.x catalog alias를 사용한다. 이미지 tag는 구현 전에 Testcontainers source와 image startup을 확인해 고정한다.
- 기존 `messaging-kafka`, `messaging-kafka-reply`, `messaging-kafka-outbox-fallback`, #555 usage-billing fixture의 public behavior는 변경하지 않는다.
- 외부 Kafka, ZooKeeper, Kafka Connect, cloud credential, XA, distributed transaction, exactly-once 업무 효과는 범위 밖이다.

## 수용 기준

1. `:messaging-kafka-multi-broker-failover:test`가 세 broker와 하나의 KRaft controller quorum을 시작하고 cluster node 수 `3`을 확인한다.
2. test topic의 partition leader, replicas `3`, ISR `3`을 failure 전후 AdminClient evidence로 확인한다.
3. 실제 leader broker 중단 뒤 다른 ISR broker가 bounded timeout 안에 leader가 된다.
4. 동일 producer가 metadata refresh/reconnect 후 남은 deterministic batch를 publish하고, consumer application-level dedup 결과가 기대 logical ID 집합과 일치한다.
5. broker restart 뒤 consumer fetch가 재개되고, coordinator failure 시 consumer group assignment가 새 coordinator에서 회복된다.
6. 중단한 broker가 재가입한 뒤 해당 partition ISR이 `3`으로 회복된다.
7. README와 README.ko.md가 #555 TCP path recovery와 #558 cluster failover를 구분하고, at-least-once와 exactly-once 비주장을 명시한다.
8. root README, messaging module map, `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml`, validation/stale-check, lesson, diagram asset pair가 새 모듈과 일치한다.
9. Testcontainers-heavy 검증은 다른 module/worktree와 병렬 실행하지 않고, 실패 시 원인을 조사한 뒤 재실행한다.

## 문서와 시각 자료

- `messaging/kafka-multi-broker-failover/README.md`와 `README.ko.md`는 topology, 실행 명령, failure evidence, 범위 밖 항목을 같은 구조로 설명한다.
- `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.svg/png`는 Spring Boot client, bootstrap metadata, 세 broker, controller quorum, replica/ISR 경계를 정적으로 보여준다.
- `docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.svg/png`는 produce → leader stop → metadata refresh → new leader → consumer recovery → broker restart/ISR catch-up 순서를 보여준다.
- SVG/PNG pair와 README PNG 참조를 diagram asset audit로 확인한다. 시각 자료는 source-backed topology를 줄이지 않고, path interruption과 cluster failover를 서로 다른 색상/범례로 구분한다.

## 구현 전 위험 예측

| 위험 | 신호 | 완화 | 재실행 지점 |
| --- | --- | --- | --- |
| KRaft image 환경 변수 변화 | broker가 `RECOVERY`에서 `RUNNING`으로 전이하지 않음 | source/jar와 image log를 먼저 확인하고 tag/config를 한 곳에서 고정 | fixture startup test |
| dynamic mapped listener 오류 | metadata endpoint가 `localhost`/container-only hostname | `getBootstrapServers()`와 AdminClient metadata를 함께 검증 | leader failover test 전 |
| leader/coordinator 선택 비결정성 | 중단 후 leader 또는 coordinator가 예상과 다름 | AdminClient 상태 기록으로 대상 broker를 동적으로 선택하고 bounded wait | 각 failure scenario 시작 |
| Testcontainers resource/CI timing | retry마다 다른 timeout 또는 leaked container | sequential Gradle lane, explicit timeout, reverse cleanup, raw log artifact | module test 전체 |
| at-least-once와 dedup 의미 혼동 | raw delivery와 applied ID count가 다름 | 두 count를 모두 기록하고 exactly-once 문구를 금지 | consumer recovery assertion |

## DoD

- 새 모듈이 `./gradlew projects`와 validation surfaces에 등록된다.
- unit/container-free helper tests와 3-broker Testcontainers integration tests가 각각 통과한다.
- leader 이동, producer reconnect, consumer recovery, broker rejoin/ISR 회복의 raw evidence가 테스트 출력과 README에 연결된다.
- README locale parity, diagram SVG/PNG pair, semantic/visual audit, `git diff --check`가 통과한다.
- `docs/lessons/`에 KRaft listener/ISR recovery에서 얻은 재사용 가능한 lesson을 기록한다.
- pre-PR review에서 P0/P1이 0이고, PR body는 한국어로 작성하며 마지막 `## DoD Status`에서 exact head와 남은 merge gate를 보고한다.

## 미결정 사항과 구현 시 고정할 값

- Apache Kafka image의 정확한 tag는 implementation step에서 current catalog와 Docker image startup을 확인한 뒤 고정한다. 이미지를 임의로 최신으로 바꾸지 않는다.
- `KafkaContainer.withEnv` override가 KRaft quorum에 적용되지 않으면 source-compatible test-only `GenericContainer` wrapper로 전환하고, 전환 근거를 lesson에 기록한다.
- group coordinator failure에서 실제 assignment callback이 발생하는 broker 선택은 AdminClient 결과로 동적으로 결정한다. coordinator를 고정 ID로 가정하지 않는다.
