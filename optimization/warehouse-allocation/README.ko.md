# Warehouse Allocation

`optimization-warehouse-allocation`은 결정론적 warehouse allocation과
pick-wave 제안을 보여 주는 Java 25/Spring Boot reference application입니다.
synthetic order와 stock snapshot을 사용하며, 승인 시 reservation의 최종 권위는
PostgreSQL에 있습니다. planner는 제안만 반환합니다.

## 범위

- order line, warehouse/SKU stock, pick wave, carrier cutoff, shipping rule,
  picker capacity, committed allocation pin을 모델링합니다.
- 재고, capability, cutoff, capacity, incident, pin 제약을 bounded 입력/출력과
  함께 결정론적으로 적용합니다.
- plan, reservation, inbox, idempotency, audit, replan, outbox, local effect를
  revision과 compare-and-set으로 저장합니다.
- redacted query model과 local `FAKE` planning/callback 경계를 제공합니다.

Production WMS, robotics, carrier API, 자동 stock commit, Kafka broker와
Timefold credential은 이 예제의 범위가 아닙니다. 기록된 outbox event는 fixture
증거이며 production fulfillment를 의미하지 않습니다.

## 로컬 실행

기본 애플리케이션은 HTTP와 management endpoint를 loopback에 바인딩하고 `test`
profile을 사용합니다. 변경 route는 추가로 local-only `demo` profile과
`X-Demo-Operator: true` header를 요구합니다.

```bash
./gradlew :optimization-warehouse-allocation:bootRun \
  --args='--spring.profiles.active=test,demo'

curl -s http://127.0.0.1:8080/warehouse-allocation
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/api/warehouse-allocation/stock
```

demo mutation마다 길이가 제한된 새 `Idempotency-Key`와 `X-Request-Id`를 사용합니다.
demo header는 local guard일 뿐 authentication, authorization, CSRF 보호 또는
production credential이 아닙니다.

```bash
curl -s -X POST http://127.0.0.1:8080/api/warehouse-allocation/events \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: event-001' \
  -H 'X-Request-Id: request-001' \
  -d '{"eventId":"event-001","eventKey":"inventory-001","eventType":"INVENTORY_ADJUSTED","sourceEventRevision":1,"target":{"warehouseId":"wh-001","sku":"sku-001"},"payload":{"onHandQuantity":10}}'

curl -s -X POST http://127.0.0.1:8080/api/warehouse-allocation/replans \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: replan-001' \
  -H 'X-Request-Id: request-002' \
  -d '{"datasetId":"demo-dataset","parentPlanRevision":0}'

curl -s 'http://127.0.0.1:8080/api/warehouse-allocation/outbox/<operation-key>'
```

fixture ABI는 `testFixtures` source set에서만 제공하며,
`WarehouseAllocationFixturePort.reset(seed)`, `ingest(canonicalEvent)`,
`snapshot(datasetId)`를 결정론적 테스트에 사용합니다. production data loading
경계로 사용하지 않습니다.

## 검증

영속성 테스트는 PostgreSQL Testcontainers를 사용하므로 정상 Docker/Colima
context가 필요합니다. container runtime을 사용할 수 없으면 성공으로 처리하지
않고 `PENDING`으로 기록합니다.

```bash
./gradlew :optimization-warehouse-allocation:cleanTest \
  :optimization-warehouse-allocation:test \
  --no-build-cache --max-workers=1 --console=plain
./scripts/smoke-validate.sh optimization
```

진단 및 성능 산출물은 생성될 때
`build/reports/warehouse-allocation-diagnostics/`와
`build/reports/performance/*.jfr` 아래에 둡니다. 로그와 payload는 반드시 redacted
상태여야 합니다.

Bluetape 버전은 root `bluetape4k-dependencies` BOM만 사용하며
`planning-contracts` 내부 구현에 의존하지 않습니다.
