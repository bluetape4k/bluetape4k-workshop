# Planning Contracts

[English](README.md) | 한국어

이 Spring Boot 예제는 비동기 optimization provider를 애플리케이션 경계 안에
두는 방법을 보여줍니다. Planning 결과는 제안일 뿐 최종 업무 결정이 아닙니다.
애플리케이션은 command candidate를 반환하기 직전에 PostgreSQL에서 aggregate
version을 다시 읽습니다.

## 계약

1. `POST /api/planning/requests`는 request와 outbox row를 같은 트랜잭션에 저장합니다.
2. `PlanningOutboxWorker`는 PostgreSQL에서 만료 시간이 있는 lease를 획득한 뒤 Bluetape Java 25 virtual thread에서 `PlanningEngine`을 호출합니다.
3. 기본 deterministic fake는 네트워크와 credential이 필요 없습니다. 비활성 상태인 `timefold`, `custom-solver` profile은 서로 다른 HTTP endpoint를 같은 normalized contract로 변환합니다.
4. Callback signature를 상태 변경 전에 검증합니다. `(provider, event_id)` unique inbox key 때문에 같은 이벤트가 반복 전달되어도 no-op으로 수렴합니다.
5. 요청은 현재 활성 engine을 사용해야 하며 callback provider는 저장된 요청의 provider와 일치해야 합니다. 불일치는 accepted state를 바꾸지 않고 audit에 남습니다.
6. 새 callback은 immutable audit decision을 추가합니다. Stale revision과 변경된 aggregate version도 audit에 남지만 accepted state를 덮어쓰지 않습니다.
7. 조회 API는 outbox payload, callback signature, credential, provider raw body, 내부 오류를 반환하지 않습니다.

## Bluetape stack

| 책임 | 사용 기능 |
|---|---|
| 버전 기준 | `bluetape4k-dependencies:1.4.0` |
| Repository | `bluetape4k-exposed-jdbc`의 `UUIDAuditableJdbcRepository`, `LongAuditableJdbcRepository`, `LongJdbcRepository` |
| PostgreSQL 테스트 | `PostgreSQLServer.Launcher.postgres`, 공개된 `bluetape4k-exposed-jdbc-tests` 지원 기능 |
| Virtual thread | `bluetape4k-virtualthread-api`와 runtime `bluetape4k-virtualthread-jdk25` |
| Provider HTTP | `productionVirtualThreadHttpClientOf`; submit `POST` 자동 retry 비활성화 |
| 동시성 테스트 | `MultithreadingTester` |

JetBrains Exposed 좌표는 버전 없이 선언합니다. 현재
`bluetape4k-dependencies:1.4.0` 해석 결과는 JetBrains Exposed `1.4.0`,
Bluetape Exposed `1.12.1`입니다. 라이브러리 버전과 BOM 버전 `1.4.0`은
서로 다른 값입니다.

모든 configuration에서 JDK 21 virtual-thread provider를 제외합니다. 첫 계약은
PostgreSQL만으로 충분하므로 Redis를 추가하지 않았습니다. 뒤의 예제에서 실제
필요가 확인되면 Lettuce를 사용해야 합니다.

## 영속성

| 테이블 | 역할 |
|---|---|
| `planning_aggregates` | callback과 command에서 비교할 현재 aggregate version |
| `planning_requests` | normalized request 상태와 redacted result projection |
| `planning_outbox` | lease, retry, completion, dead-letter 상태 |
| `planning_callback_inbox` | provider event idempotency |
| `planning_audits` | append-only callback decision |

## API 실행 순서

```bash
curl -s -X POST http://localhost:8080/api/planning/requests \
  -H 'Content-Type: application/json' \
  -d '{"aggregateId":"roster-42","aggregateVersion":7,"datasetId":"dataset-42","provider":"FAKE"}'

curl -s -X POST http://localhost:8080/api/planning/process

curl -s -X POST http://localhost:8080/api/planning/callbacks/fake \
  -H 'Content-Type: application/json' \
  -H 'X-Planning-Signature: fake' \
  -d '{"eventId":"event-42","planningRequestId":"<request-id>","providerRevision":2,"status":"SUCCEEDED","scoreSummary":"0hard/-2soft","constraintExplanations":["balanced workload"]}'

curl -s http://localhost:8080/api/planning/requests/<request-id>
curl -s -X POST http://localhost:8080/api/planning/requests/<request-id>/commands
```

`process` endpoint는 예제 흐름을 눈으로 확인하기 위해 동기 방식으로 두었습니다.
운영 환경에서는 public endpoint로 노출하지 말고, 인증된 operator control 뒤에서
worker를 scheduling해야 합니다.
Callback body는 parsing 전에 256 KiB로 제한하고, provider response는 최대 64 KiB만
streaming 방식으로 읽습니다.

## Provider profile

기본 profile은 fake provider를 사용합니다. HTTP profile에는 base URL과 webhook
secret이 모두 필요합니다.

```bash
SPRING_PROFILES_ACTIVE=timefold \
PLANNING_PROVIDER_BASE_URL=https://example.invalid \
PLANNING_CALLBACK_SECRET=replace-me \
./gradlew :optimization-planning-contracts:bootRun
```

Custom Solver endpoint mapping을 보려면 `timefold` 대신 `custom-solver`를
사용합니다. 테스트는 WireMock만 사용하며 외부 서비스에 연결하지 않습니다.

## 검증

Java 25와 Docker가 필요합니다.

```bash
./gradlew :optimization-planning-contracts:cleanTest \
  :optimization-planning-contracts:test \
  --no-build-cache --max-workers=1
```

테스트는 Java 25 runtime 선택, JDK 25 virtual thread, repository CRUD, 동시 inbox/
outbox 수렴, transaction rollback, callback 보안, HTTP no-retry, redacted MVC 응답,
최종 aggregate version 검증을 다룹니다.
