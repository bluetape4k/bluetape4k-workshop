# Issue #560 Kafka recovery 열차 상태

## 현재 단계

Kafka recovery 연속 작업은 `#555` broker-path interruption, `#558` 독립
3-broker KRaft failover, `#559` black-box conformance 계약 순서로 분리한다.
현재 branch에서는 `#558`의 data leader/coordinator 실제 시나리오와 `#559`의
공통 test-fixture/validator를 함께 검증한다. `#555` production fixture는
기존 Toxiproxy와 PostgreSQL 소유권을 유지하고, conformance 입력만 제공한다.

## 의존성 경계

`#559`는 `#555`나 `#558`의 fixture/package를 import하지 않는다. 두 예제는
각자 broker image, network, Spring 설정, domain persistence를 소유하며
`KafkaRecoveryConformanceFixture`는 관찰값과 redacted JSONL만 소비한다.

`#558`의 18-field evidence는 data leader와 group coordinator 결과를
구분한다. leader 경로는 leader 이동과 ISR 3, coordinator 경로는 coordinator
이동과 선택 data leader 안정성을 별도로 기록한다. 두 경로 모두 at-least-once
delivery와 local dedup 경계만 증명하며 exactly-once를 주장하지 않는다.

## 남은 열차 항목

- `#555`와 `#558` CI에서 동일 conformance validator를 실제 artifact에 연결한다.
- workflow/validation matrix/lesson 등록이 모두 같은 branch에 있는지 확인한다.
- FastFory 전환은 별도 `#577` BOM release gate가 통과한 뒤에만 진행한다.
