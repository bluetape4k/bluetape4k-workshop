# Shift Coverage

이 Spring Boot 참조 애플리케이션은 synthetic multi-site worker·shift
coverage와 사람이 확인하는 shift swap을 보여 줍니다. planner는 immutable
canonical snapshot만 읽어 deterministic proposal을 반환하며, assignment
projection을 변경하는 경로는 manager approval과 swap acceptance뿐입니다.

## 경계

- 기본 `demo` profile은 loopback에 바인딩하고 deterministic fake adapter만
  사용합니다. Timefold를 호출하지 않고 provider credential을 저장하지 않으며
  autonomous reassignment를 수행하지 않습니다.
- overlap, unavailable worker, skill, minimum rest, started shift, pinned
  assignment를 hard rule로 검사합니다. gap은 closed reason code, signed
  minor-unit score, plan revision으로 표현합니다.
- local wall-clock은 DST gap/ambiguous를 명시적인 reason code로 거절하며,
  explicit offset이 있을 때만 Instant로 정규화합니다. source event는 assignment를
  쓰지 않고 해당 plan/generation을 stale로 수렴시킵니다.
- `postgres` profile에서 PostgreSQL이 authority입니다. inbox/event claim,
  idempotency fingerprint, revision CAS, fenced outbox delivery, audit row는
  PostgreSQL/Testcontainers를 기준으로 하며 demo는 DB 없이 시작할 수 있습니다.
- Java 25 virtual thread는 blocking I/O에만 사용하고 CPU planning은 bounded
  four-worker/eight-queue admission 경로를 사용합니다.
- Actuator health와 Prometheus metrics는 bounded `result` label만 노출하며
  worker·tenant·credential·callback body는 metric label로 사용하지 않습니다.

## 실행과 검증

```bash
./gradlew :optimization-shift-coverage:test --max-workers=1 --console=plain
./gradlew :optimization-shift-coverage:bootRun
curl -s http://127.0.0.1:8080/shift-coverage/
```

`postgres` profile은 `SHIFT_COVERAGE_DATABASE_URL`,
`SHIFT_COVERAGE_DATABASE_USERNAME`, `SHIFT_COVERAGE_DATABASE_PASSWORD`를
환경에서 제공하지 않으면 fail-closed 한다. 저장소에는 기본 JDBC URL이나
credential을 두지 않는다.

Demo header에는 `manager-demo`, `worker-a-demo`, `worker-b-demo`와 일치하는
`X-Demo-Role`을 사용합니다. mutation에는 `Idempotency-Key`와 loopback guard가
필요합니다. browser console은 의도적으로 redacted read model만 호출하고
plan revision, coverage/gap, fairness, closed reason만 표시합니다.

이 consumer는 root `bluetape4k-dependencies` BOM만 사용하며 sibling
planning-contracts implementation에 의존하지 않습니다.
