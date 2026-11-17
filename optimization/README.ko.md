# Optimization 워크숍

[English](README.md) | 한국어

`optimization/` 그룹은 애플리케이션이 소유하는 planning 및 optimization 경계를 다룹니다. 이 그룹 아래의 모든 모듈은 Java 25 toolchain을 사용합니다. provider HTTP 호출과 JDBC 작업은 Bluetape JDK 25 virtual-thread runtime에서 실행합니다.

## 이 그룹이 필요한 이유

Planning 결과는 “solver가 제안을 찾은 순간”과 “업무가 변경을 승인한 순간”의
간격을 견딜 수 있을 때만 유용합니다. Clinic slot, worker route, 배송 정류장,
warehouse 재고는 계산 중에도 바뀔 수 있으므로 이 예제들은 optimization을
자동 commit이 아니라 제한된 제안으로 다룹니다.

공통 해결 방향은 application ownership입니다. Planner는 synthetic 또는
정규화된 입력을 읽고 결정론적인 proposal과 reason code를 반환하며,
승인 시 상태 변경은 PostgreSQL compare-and-set 또는 명시적인 사람의 승인에
맡깁니다. Provider HTTP, callback replay, inbox/outbox 전달, redacted read
model, loopback demo guard는 필요한 모듈에서 보여 주고, live credential,
production map, PHI, autonomous fulfillment는 문서화한 경계 밖에 둡니다.

각 모듈 README는 업무 시나리오, 한계, 검증 명령을 함께 설명합니다.
`scripts/smoke-validate.sh optimization`은 그룹 수준의 test-backed 검사이고,
각 모듈 아래의 Gradle suite는 해당 constraint와 persistence 계약을 검증합니다.
실제 route 품질이나 solver 최적성을 증명하는 검사는 아닙니다.

| 모듈                                                      | 목적                                                                                                                                                                       | 인프라                                       |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| [`planning-contracts`](planning-contracts/)               | provider-neutral planning 제출, PostgreSQL inbox/outbox 수렴, callback idempotency, 최종 aggregate version 재검증                                                          | PostgreSQL + WireMock (Testcontainers)       |
| [`field-service-dispatch`](field-service-dispatch/)       | synthetic Field Service dispatch, deterministic planning, proposal approval, worker-route CAS confirmation, redacted browser console                                       | PostgreSQL (Testcontainers)                  |
| [`last-mile-routing`](last-mile-routing/)                 | 고정 travel matrix 기반 synthetic pickup/delivery 라우팅, Bluetape Exposed CAS repository, 정규화 provider callback/outbox lifecycle, CSP 안전 redacted browser projection | PostgreSQL (Testcontainers)                  |
| [`warehouse-allocation`](warehouse-allocation/)           | synthetic warehouse allocation 및 pick-wave 제안, PostgreSQL 권위 재고 예약, 결정론적 제약·replay, redacted browser console                                                | PostgreSQL (Testcontainers)                  |
| [`shift-coverage`](shift-coverage/)                       | synthetic multi-site worker/shift coverage, hard-rule deterministic planning, 사람이 확인하는 shift swap, inbox/outbox fencing, redacted demo console                      | PostgreSQL + Testcontainers (기본 demo fake) |
| [`clinic-appointment-solver`](clinic-appointment-solver/) | embedded Timefold Solver를 사용한 synthetic clinic appointment/resource 최적화, confirmed booking pin, 결정론적 proposal, 읽기 전용 score projection                       | In-memory fixture (외부 credential 불필요)   |

그룹 검증:

```bash
./scripts/smoke-validate.sh optimization
```

공개된 모든 Bluetape 모듈의 버전은 `bluetape4k-dependencies:2.1.0-SNAPSHOT`만 관리합니다. Optimization 예제는 개별 library BOM을 import하거나 Bluetape 모듈 버전을 직접 고정하지 않습니다.
