# Field Service Dispatch

[English](README.md) | 한국어

이 Spring Boot reference application은 synthetic 데이터만 사용하는 Field
Service dispatcher 예제입니다. 방문, worker, planning proposal, 확정 route를
PostgreSQL에 저장하고 deterministic fixture에서 planning 결과를 만듭니다. 기본
실행 경로는 Timefold, 지도 provider, 그 밖의 외부 서비스에 연결하지 않습니다.

## 범위와 경계

- synthetic 방문은 visit type, required skill, time window, service duration,
  priority, synthetic coordinate만 가집니다.
- planner는 required skill, worker availability, time window, travel matrix,
  started/pinned visit 제약을 deterministic tie-break와 함께 적용합니다.
- approval은 proposal 상태만 변경합니다. worker-route confirmation은 현재
  worker와 visit version을 다시 확인한 뒤 전체 route를 원자적으로 확정합니다.
- callback과 replay fixture는 local contract 테스트입니다. live Timefold tenant나
  production route quality의 증거로 해석하지 않습니다.
- patient record, diagnosis, insurance, clinical advice, production credential,
  production map provider는 다루지 않습니다.

## API 실행 순서

기본 `demo` profile로 애플리케이션을 시작합니다. 서버는 loopback에 바인딩하고
`/field-service`에서 정적 console을 제공합니다.

```bash
./gradlew :optimization-field-service-dispatch:bootRun
curl -s http://127.0.0.1:8080/field-service
```

변경 endpoint는 demo operator header와 길이가 제한된 idempotency key를 요구합니다.
다음 synthetic 흐름은 방문 생성, replan 요청, proposal 조회와 approval, worker
route 하나의 confirmation을 순서대로 실행합니다.

```bash
curl -s -X POST http://127.0.0.1:8080/api/field-service/visits \
  -H 'Content-Type: application/json' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: visit-001' \
  -d '{"visitId":"visit-001","coordinateId":"coord-001","requiredSkill":"INSTALL","windowStart":"2026-08-20T09:00:00Z","windowEnd":"2026-08-20T12:00:00Z","serviceDurationSeconds":1800}'

curl -s -X POST http://127.0.0.1:8080/api/field-service/plans/replan \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: replan-001' \
  -H 'Content-Type: application/json' \
  -d '{"planId":"field-service","datasetId":"demo-dataset"}'

curl -s http://127.0.0.1:8080/api/field-service/plans/<revision>

curl -s 'http://127.0.0.1:8080/api/field-service/plans?planId=field-service&limit=20'

curl -s -X POST 'http://127.0.0.1:8080/api/field-service/plans/<revision>/approve?planId=field-service' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: approve-001'

curl -s -X POST 'http://127.0.0.1:8080/api/field-service/dispatch/workers/<worker-id>/confirm?planId=field-service&revision=<revision>' \
  -H 'X-Demo-Operator: true' \
  -H 'Idempotency-Key: confirm-001'
```

redacted read model은 list endpoint로 확인합니다.

```bash
curl -s http://127.0.0.1:8080/api/field-service/visits
curl -s http://127.0.0.1:8080/api/field-service/workers
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/actuator/prometheus
```

confirmation 단계에서는 workers endpoint가 반환한 `workerId`를 사용합니다.
demo schema는 disposable하므로 전체 route confirmation 흐름을 실행하기 전에
fixture 또는 테스트 setup으로 worker와 방문을 준비해야 합니다.

응답에는 synthetic identifier, 숫자 score, closed reason code만 포함합니다.
credential, raw callback payload, provider text, address, 내부 SQL 오류는 반환하지
않습니다. demo loopback/operator guard는 예제 경계이며 production authentication이나
CSRF 보호를 대체하지 않습니다. console과 plans read model은 plan revision, approval
상태, assigned/unassigned 수, numeric score, constraint reason, manual pin 수를
표시합니다.

## 검증

PostgreSQL Testcontainers를 사용하므로 Java 25와 Docker가 필요합니다. module 테스트와
optimization 그룹 smoke 진입점을 순차 실행합니다.

```bash
./gradlew :optimization-field-service-dispatch:cleanTest \
  :optimization-field-service-dispatch:test \
  --no-build-cache --max-workers=1
./scripts/smoke-validate.sh optimization
```

오래된 테스트 결과나 container 설정 실패가 의심되면 `cleanTest --no-build-cache`
명령을 다시 실행하고 코드를 바꾸기 전에 테스트 report를 확인합니다. schema는
`SchemaUtils`로 만드는 disposable fixture이며 production migration은 추가하지
않습니다. local rollback은 module과 README/workflow/smoke 등록을 함께 되돌리는
범위로 제한합니다.

이 모듈의 Bluetape 버전 기준은 `bluetape4k-dependencies` 하나입니다.
`planning-contracts` 구현을 의존하지 않으며 개별 Bluetape module 버전을 고정하지
않습니다.
