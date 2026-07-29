# Issue #555 Kafka Broker-Path Recovery 설계

## 문제와 목표

현재 composition test의 `MeterTransportFailureSwitch`는 outbox 재시도 상태를 빠르고 결정적으로 증명하지만, 실제 Kafka TCP 경로가 끊어졌을 때 Spring Kafka producer가 metadata와 socket을 복구하는지는 증명하지 않는다. 이 단계는 기존 financial correctness 경계를 바꾸지 않고, nightly에서 실제 broker 경로 단절과 복구를 검증한다.

## 현재 근거

- `UsageBillingMicroserviceFixture`는 host JVM에서 다섯 Spring context를 실행하고 단일 `KafkaContainer`를 사용한다.
- Testcontainers Kafka 2.0.5는 custom listener와 advertised listener supplier를 제공한다. 따라서 초기 bootstrap뿐 아니라 metadata가 돌려주는 listener도 프록시 host port로 고정할 수 있다.
- Testcontainers Toxiproxy는 같은 Docker network에서 TCP 양방향을 차단하고 toxic 제거 후 연결을 복구할 수 있다.

## 선택한 설계

`UsageBillingMicroserviceFixture`에 opt-in broker-path mode를 추가한다.

1. Kafka와 Toxiproxy는 동일한 Docker network에 속한다.
2. Kafka는 `kafka:19092` custom listener를 열고, 해당 listener의 advertised endpoint를 Toxiproxy의 host-mapped port로 제공한다.
3. 각 Spring context의 bootstrap property는 Kafka의 직접 endpoint 대신 이 proxy endpoint를 사용한다.
4. 테스트는 proxy의 upstream/downstream bandwidth를 모두 0으로 만들어 실제 TCP 흐름을 끊고, `publishMeterEvents()`가 기존 outbox row를 `RETRY_WAIT`로 남기는지 확인한다.
5. toxic을 제거한 뒤 같은 row를 재시도하여 Usage service의 price evidence가 도착할 때까지 기다린다.

`MeterTransportFailureSwitch`와 현재 deterministic `OutageIntegrationTest`는 기본 suite의 빠른 실패 경로로 유지한다. 새 scenario는 `integration` tag로 현재 nightly `integrationTest`에만 포함한다.

## 대안과 기각 이유

| 대안 | 판단 |
| --- | --- |
| 기존 failure switch만 사용 | 실제 socket, metadata, advertised listener 복구를 증명하지 못한다. |
| KafkaContainer를 pause 또는 stop | 단일 broker lifecycle은 TCP path fault와도 true cluster failover와도 다른 신호이며 flaky하다. |
| proxy를 bootstrap endpoint에만 삽입 | Kafka metadata가 direct broker endpoint를 반환하면 이후 연결이 proxy를 우회한다. |
| 3-broker cluster failover를 이 모듈에 포함 | #558의 독립 예제 범위다. 이 단계는 단일 broker-path 복구까지만 다룬다. |

## 실패 모드와 경계

- proxy toxic 등록 실패 또는 Kafka custom listener가 준비되지 않으면 fixture startup을 실패시킨다.
- 양방향 toxic 중 하나라도 남으면 `finally`에서 제거하고 fixture close는 contexts, containers, network를 역순으로 닫는다.
- 단절 중 publish가 우연히 성공하면 테스트는 `RETRY_WAIT` assertion으로 실패한다. 따라서 named behavior를 우회해 pass할 수 없다.
- 복구 후 timeout이면 network retry 또는 advertised listener 문제가 드러난 것으로 보고 재시도가 아니라 원인을 조사한다.

## 수용 기준

- nightly composition integration test가 실제 TCP 단절 중 Meter outbox backlog를 하나 남긴다.
- toxic 제거 후 동일 logical event가 전달되어 Usage price evidence가 관측된다.
- README와 README.ko는 deterministic fault와 real broker-path fault를 분리해 설명한다.
- 기본 `test`는 integration tag를 계속 제외하고, nightly는 새 scenario를 실행한다.
- single broker path-recovery를 Kafka leader election 또는 cluster failover로 주장하지 않는다.

## 범위 밖

- 3-broker leader election, ISR, replication recovery는 #558의 범위다.
- XA, distributed exactly-once, production infrastructure, cloud Kafka는 추가하지 않는다.
