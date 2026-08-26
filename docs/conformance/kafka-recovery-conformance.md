# Kafka recovery black-box conformance

## 목적

`#559`는 Kafka를 공통 production abstraction으로 묶지 않는다. 각 예제가
자기 broker/client/domain fixture를 유지한 채, 복구 결과를 관찰값으로
제공하고 같은 `KafkaRecoveryConformanceFixture`가 그 결과를 검증한다.

공통 fixture의 입력은 다음 네 가지다.

- 논리 이벤트 ID 집합과 delivery ID 목록
- 적용된 고유 효과 ID 집합
- 복구 경로와 복구 소요 시간
- leader/coordinator 이동, replacement/ISR 복구, fingerprint conflict 여부

fixture는 Kafka client, Testcontainers, Spring bean, Exposed table을 만들지
않는다. 따라서 `#555`의 Toxiproxy broker-path 장애와 `#558`의 3-broker
KRaft leader/coordinator 장애는 서로 독립된 설정을 유지한다.

## 경로 구분

| 경로 | 검증하는 사실 | 주장하지 않는 사실 |
|---|---|---|
| `TRANSPORT_INTERRUPTION` | durable local outbox가 broker path 복구 뒤 한 번 적용됨 | broker leader/coordinator가 이동했다는 사실 |
| `BROKER_LEADER_FAILOVER` | 실제 data leader 이동, replacement와 ISR 3 복구, 동일 ID 집합 적용 | exactly-once, 모든 Kafka 토폴로지의 보편 보장 |
| `BROKER_COORDINATOR_FAILOVER` | group coordinator 이동, 선택 data leader 안정, replacement와 ISR 3 복구 | data leader failover를 동시에 증명했다는 사실 |

delivery 목록에는 replay duplicate가 포함될 수 있다. `appliedEventIds`는
논리 ID별 고유 효과만 허용하며 `conflictCount`가 0이어야 한다.

## 사용 위치

공유 test-fixture 의존성만 추가한다.

```kotlin
testImplementation(testFixtures(project(":shared")))
```

`#558`는 두 실제 broker 시나리오의 terminal 관찰값을 fixture에 전달하고,
`#555`는 Toxiproxy broker-path 복구 관찰값과 duplicate replay 관찰값을 같은
fixture에 전달한다. 두 모듈의 production source에는 공통 Kafka abstraction을
추가하지 않는다.

18-field redacted JSONL을 소비하는 CI 검사는 별도 black-box validator다.

```bash
node scripts/validate-kafka-recovery-conformance.mjs \
  messaging/kafka-multi-broker-failover/build/reports/kafka-failover/<runId>/evidence.jsonl
```

validator는 한 파일에 누적된 두 scenario stream을 각각 분리해 exact field set,
run-scoped phase order, terminal `PASS`, raw/applied 관계, conflict 0, path별
leader/coordinator identity와 적용 개수를 검사한다. 특정 경로만 확인하려면
`--path broker-leader` 또는 `--path broker-coordinator`를 추가한다.
payload, bootstrap endpoint, credential, environment, token, stack trace는
fixture 입력이나 JSONL에 넣지 않는다.

## 범위와 재사용 규칙

- `#555`는 Toxiproxy TCP path interruption과 PostgreSQL-authoritative outbox를
  증명한다.
- `#558`는 세 broker KRaft cluster, 실제 leader/coordinator 선택, replacement
  lifecycle, ISR catch-up을 증명한다.
- `#559`는 두 결과를 같은 black-box contract로 비교할 뿐 topology, image,
  Spring wiring, domain persistence를 통일하지 않는다.
- delivery recovery는 at-least-once 경계다. duplicate 방지는 각 domain의
  local identity/fingerprint 경계이며 distributed exactly-once가 아니다.
