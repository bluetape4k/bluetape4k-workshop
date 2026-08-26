# Issue #559 Kafka recovery conformance 교훈

## 결과

`shared/src/testFixtures`에 `KafkaRecoveryConformanceFixture`를 추가했다.
fixture는 broker나 Kafka client를 소유하지 않고, 각 예제가 전달한 관찰값만
검증한다. 따라서 `#555`의 Toxiproxy 경로 복구와 `#558`의 3-broker KRaft
leader/coordinator 복구가 production 코드 결합 없이 같은 계약을 사용한다.

검증 계약은 path를 분리한다. transport interruption은 leader/coordinator
이동을 주장하지 않고, broker leader failover는 leader 이동과 replacement/ISR
복구를 요구하며, coordinator failover는 선택 data leader 안정과 coordinator
이동을 요구한다. 모든 경로에서 logical ID 집합과 unique applied 집합이 같고
fingerprint conflict가 없어야 한다.

## 놓치기 쉬운 점

delivery count만 비교하면 replay와 duplicate effect를 구분할 수 없다. 그래서
fixture는 `deliveredEventIds`와 `appliedEventIds`를 별도로 받고, 의도한 duplicate
delivery가 적용 개수를 늘리지 않는지 독립적으로 검사한다. 이 경계는
at-least-once delivery를 exactly-once로 과장하지 않기 위한 최소 조건이다.

18-field JSONL validator도 fixture 구현과 분리했다. JSONL은 run-scoped
redacted evidence만 소비하고, source-specific Testcontainers/Admin 객체나
payload를 import하지 않는다.

## 검증 영수증

- `KafkaRecoveryConformanceFixtureTest` — replay delivery 2건과 unique applied
  1건, transport evidence를 broker failover로 잘못 표시하는 경우를 검증한다.
- `BrokerPathRecoveryIntegrationTest` — 실제 Toxiproxy path interruption 결과를
  `TRANSPORT_INTERRUPTION` 관찰값으로 전달한다.
- `UsageBillingMicroserviceCompositionIntegrationTest` — 실제 duplicate
  replay 후 `assertDedupBoundary`를 사용한다.
- `KafkaMultiBrokerFailoverIntegrationTest` — 실제 data leader와 coordinator
  시나리오의 terminal 결과를 같은 fixture로 검증한다.
- `scripts/validate-kafka-recovery-conformance.mjs` — 18-field schema, fixed
  phase, terminal status, path별 identity와 count invariant를 fail-closed로
  검사한다.

## 미래 guard

- 공통 fixture에 Kafka client, Testcontainers, Spring wiring, domain table을
  추가하지 않는다.
- 새 recovery example은 transport, leader, coordinator 중 하나를 명시하고
  다른 path의 invariant를 재사용하지 않는다.
- evidence가 logical ID나 payload를 직접 노출해야 한다면 conformance 계약을
  확장하지 말고 redaction 경계를 먼저 재설계한다.
