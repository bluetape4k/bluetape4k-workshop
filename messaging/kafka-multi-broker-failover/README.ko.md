# Kafka Multi-Broker Failover Reference

[English](README.md) | 한국어

이 모듈은 새로운 3-broker KRaft 클러스터에서 Kafka failover 동작을
소스 기준으로 재현하는 참고 예제입니다. Spring Kafka client로 실제 leader
교체와 별도의 consumer group coordinator 교체를 수행한 뒤, 제한된 redacted
evidence만 기록합니다. 결과는 이 fixture의 동작을 설명하며 production 용량이나
보편적인 가용성 보장을 의미하지 않습니다.

## Scope

모듈은 reference event, strict JSON codec, 명시적인 Kafka client 설정과 두
integration 시나리오가 사용하는 Testcontainers fixture를 소유합니다.
Testcontainers, AdminClient 검사, broker fault, evidence 작성은 `src/test`에만
두며 application source는 HTTP server를 열지 않습니다. 첫 실행은
`bootRun`이나 외부 Kafka 연결이 아니라 integration test입니다.

![3-broker KRaft failover architecture](../../docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-architecture-01.png)

## Prerequisites

로컬 Docker engine(Docker Desktop, Colima 또는 동등한 local context)을
사용합니다. preflight 검사 하나라도 실패하면 테스트를 시작하지 않으며 외부
broker나 public endpoint로 대체하지 않습니다.

```bash
colima status
docker context show
docker info
java -version
docker network ls
docker pull apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9
```

기대 Java 버전은 25입니다. fixture는 하나의 격리된 Docker network와
immutable한
`apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9`
reference로부터 세 broker를 생성합니다. host client에는 random mapped
loopback `PLAINTEXT` listener만 전달하며, broker 간 `BROKER`와
controller `CONTROLLER` listener는 host-client bootstrap 주소가 되지 않습니다.

## Topology

| 항목 | 고정 계약 |
|---|---|
| Runtime | Kafka 4.2.0 image, KRaft, 3개의 broker/controller node |
| Node ID와 alias | `1/kafka-1`, `2/kafka-2`, `3/kafka-3` |
| Controller quorum | `1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094` |
| Host listener | client traffic용 random mapped loopback `PLAINTEXT` port |
| Broker/controller listener | `BROKER`/`CONTROLLER`, Docker-network 전용 |
| Reference topic | `kafka-failover-reference`, 3 partitions |
| Target partition | `0`, replication factor `3`, `min.insync.replicas=2` |
| Image identity | `apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9` |

fixture는 fixed host port, host networking, non-loopback binding, listener
drift, digest drift, `RepoDigest` 누락 또는 복수 결과를 거부합니다. topic은
auto topic creation을 끈 상태에서 명시적으로 생성합니다. 내부 consumer-offset
replication은 assignment barrier 이후에만 검사합니다.

## Scenarios

전체 class를 먼저 실행합니다. 각 시나리오는 fresh fixture를 사용하며 전체
module invocation에는 누적 420초(약 7분) 예산이 적용됩니다. focused 명령은
독립적인 진단 실행입니다.

```bash
./gradlew :messaging-kafka-multi-broker-failover:test --tests "*KafkaMultiBrokerFailoverIntegrationTest" --max-workers=1
./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.dataLeaderFailover' --max-workers=1
./gradlew :messaging-kafka-multi-broker-failover:test --tests '*KafkaMultiBrokerFailoverIntegrationTest.groupCoordinatorFailover' --max-workers=1
```

`data-leader-failover`는 선택 partition의 leader, replicas, ISR을 기록하고
결정적인 prefix event 4개를 acknowledge한 뒤 실제 leader를 중단합니다. 이후
fault 이전 ISR의 다른 member가 leader가 되는 것을 기다리고 4개 suffix를
발행한 뒤, 중단한 node를 ISR 3개가 될 때까지 재기동합니다. leader 또는 ISR
precondition 실패는 성공적인 skip이 아니라 evidence를 포함한 실패입니다.

`group-coordinator-failover`는 먼저 group coordinator와 선택한 data leader가
서로 다른지 증명합니다. 그 다음 coordinator broker를 중단하고 새 coordinator,
generation/assignment callback, suffix fetch, replacement ISR을 확인합니다.
필요한 분리를 만들 수 없는 topology는 allowlisted summary를 포함한 실패입니다.

![Failover recovery sequence](../../docs/images/readme-diagrams/messaging-kafka-multi-broker-failover-readme-sequence-01.png)

## Event and client contract

`KafkaFailoverEvent`는 `eventId`, `sequence`, `payload`, 고정된
`partitionKey=failover-partition-0`을 가집니다. topic은
`kafka-failover-reference`입니다. `KafkaFailoverCodec`는 Spring type header,
wildcard trusted package, default typing, scalar coercion 없이 strict JSON을
출력하며 `fingerprint`로 동일 logical ID의 payload 충돌을 감지합니다.

`KafkaFailoverKafkaConfiguration`은 root의
`bluetape4k-dependencies` BOM과 catalog alias를 사용합니다. producer에는
`acks=all`, idempotence, client retry 3회, 제한된 request/delivery/block
timeout과 backoff를 고정합니다. consumer에는 manual acknowledgment,
auto-commit 비활성화, `earliest` offset reset, 안정적인 group ID, poll/fetch
상한을 고정합니다. Admin 작업도 제한된 request/API timeout을 사용합니다.

## Evidence

각 시나리오는 `build/reports/kafka-failover/{runId}/` 아래에 run-scoped 파일을
기록합니다. `evidence.jsonl`은 phase마다 하나의 sanitized JSON object를
기록하고, `performance.jsonl`은 시간과 counter 관찰값을 위한 경로이며,
`broker-{brokerId}.log`는 redacted 상태 요약입니다. raw broker log는 만들지
않습니다. 고정 field 순서는 다음과 같습니다.

`runId`, `scenario`, `phase`, `image`, `imageDigest`, `topic`, `partition`,
`nodeCount`, `leader`, `replicas`, `isr`, `coordinator`, `assignmentCount`,
`rawDeliveryCount`, `appliedCount`, `conflictCount`, `retryCount`, `status`

```json
{"runId":"20260826T000000Z-123","scenario":"data-leader-failover","phase":"terminal","image":"apache/kafka","imageDigest":"sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9","topic":"kafka-failover-reference","partition":0,"nodeCount":3,"leader":2,"replicas":[1,2,3],"isr":[1,2,3],"coordinator":null,"assignmentCount":1,"rawDeliveryCount":8,"appliedCount":8,"conflictCount":0,"retryCount":0,"status":"PASS"}
```

phase 순서는 `startup → topic-ready → assignment-ready → prefix-acked
→ fault-injected → recovery → suffix-acked → replacement-ready → isr-restored
→ terminal`입니다. 중간 row는 관찰값이며 terminal row는 `PASS` 또는
`FAIL`입니다. redelivery로 `rawDeliveryCount`가 늘어날 수 있고,
`appliedCount`는 application level unique 결과입니다. 0이 아닌
`conflictCount`는 하나의 logical ID에 다른 payload fingerprint가 전달되었다는
뜻입니다. `retryCount`는 허용된 AdminClient retry만 세며, producer-client retry는
`performance.jsonl`에 별도로 기록합니다.

evidence에는 event ID, payload, bootstrap URL, 환경 변수, credential, owner
token, full exception, raw provider/broker body를 기록하지 않습니다. `#559`
consumer는 18-field redacted JSONL과 terminal evidence만 black-box contract로
읽으며 `#558` fixture나 package를 import하지 않습니다.

## Failure runbook

모든 진단 명령은 read-only입니다. live lock은 즉시 실패하고 stale lock은
명시적인 operator 처리를 위해 보고만 하며 자동 삭제하지 않습니다. sanitizer가
실패하면 CI는 의도적으로 artifact upload를 차단합니다.

```bash
./scripts/with-kafka-failover-lock.sh -- ./gradlew :messaging-kafka-multi-broker-failover:test --max-workers=1 --console=plain
docker ps -a --filter 'label=bluetape4k.kafka-failover.run-id=<run-id>' --format '{{.ID}} {{.Names}} {{.Labels}}'
docker network ls --filter 'label=bluetape4k.kafka-failover.run-id=<run-id>'
docker inspect --format '{{json .HostConfig.PortBindings}}' <container-id>
docker inspect --format '{{json .NetworkSettings.Ports}}' <container-id>
export KAFKA_FAILOVER_RUN_ID=<run-id>
./scripts/validate-kafka-failover-artifacts.sh --module messaging/kafka-multi-broker-failover --run-id "$KAFKA_FAILOVER_RUN_ID" --staging messaging/kafka-multi-broker-failover/build/reports/kafka-failover/sanitized
```

실패는 phase로 분류합니다. digest/listener drift와 precondition 실패는 실행을
중단합니다. ISR timeout은 허용된 AdminClient 상태와 redacted broker summary로
조사합니다. live 또는 stale lock을 우회하지 않으며 orphan 검사는 label이 붙은
container와 network로 제한합니다. bind-mount 오류를 해결하기 위해 정상인
Colima를 재시작하지 않습니다.

## Boundaries

| Issue | 경계 |
|---|---|
| #555 | Toxiproxy TCP-path recovery와 usage/billing domain 동작 |
| #558 | 이 3-broker KRaft leader/coordinator, replica, ISR, evidence reference |
| #559 | black-box 동작/evidence consumer이며 #558 fixture를 import하지 않음 |

## Unsupported

이 모듈은 `bootRun` application이나 production deployment가 아닙니다. 외부
Kafka cluster, ZooKeeper, Kafka Connect, cloud credential, XA, distributed
transaction, public endpoint와 production capacity 주장은 범위 밖입니다.
테스트는 at-least-once transport와 application-level deduplication을
보여주며 exactly-once business effect를 주장하지 않습니다. 관찰된 3-broker/ISR
결과는 재현 가능한 fixture evidence이지 보편적 보장이 아닙니다.

## Related

- [Workshop 직렬화 및 메시징 카탈로그](../../README.ko.md#3-직렬화--메시징)
- [Issue #558](https://github.com/bluetape4k/bluetape4k-workshop/issues/558)
- [Issue #559](https://github.com/bluetape4k/bluetape4k-workshop/issues/559)
