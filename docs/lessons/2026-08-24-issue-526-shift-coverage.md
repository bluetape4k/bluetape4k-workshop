# Issue #526 Shift Coverage 구현 교훈

## 결과

`optimization/shift-coverage`에 synthetic multi-site worker/shift coverage와
사람이 확인하는 shift swap의 결정적인 demo 경계를 추가했다. 기본 `demo`
profile은 recorded fake adapter와 loopback API만 사용하고, `postgres` profile은
PostgreSQL을 authority로 사용한다. planner proposal은 immutable snapshot digest와
stable reason code를 보존하며, assignment projection은 approval 또는 CAS 기반 swap
acceptance에서만 변경된다.

## Bluetape 재사용

- root `bluetape4k-dependencies` BOM만 사용하고 개별 Bluetape 버전은 고정하지 않았다.
- `Uuid.V7.nextId()`로 aggregate/request ID를 만들고 `Base58.randomString(22)`로
  bounded opaque effect key를 만들었다.
- `AuditableUUIDTable`, `UUIDAuditableJdbcRepository`, `UserContext.DEFAULT_USERNAME`,
  top-level Exposed `eq`/`and`를 aggregate persistence에 재사용했다.
- `VirtualThreads`, `KLogging`, `requirePositiveNumber`, Bluetape assertion과
  `PostgreSQLServer.Launcher.postgres`를 각각 runtime/lifecycle, validation, test
  boundary에 사용했다.
- root Gradle `test-mutex` BuildService의 `usesService(testMutex)`와 module
  `junit-platform.properties`, `--max-workers=1`을 함께 유지했다.

## 결정적인 경계

- canonical v1 JSON은 field/array ordering, NFC, UTC `Instant`, Jackson 3 escaping과
  lowercase SHA-256 digest를 고정한다.
- provider ABI는 `FAKE`, `TIMEFOLD_PLATFORM`, `CUSTOM_SOLVER`의 closed enum과
  submit/accept 두 method만 노출한다. 기본 adapter는 네트워크와 credential을
  사용하지 않는다.
- inbox는 provider/event key와 digest를 묶고 `2/4/8/16/30s` bounded retry 뒤
  `RETRY_EXHAUSTED`에서 멈춘다. manager requeue만 새 request ID로 재개한다.
- outbox는 `DELIVERY_UNKNOWN`을 definitive lookup 전 redrive하지 않고, `NOT_FOUND`
  reconciliation 이후에만 manager redrive를 허용한다. bounded queue가 차면
  outbox row를 만들거나 claim하지 않는다.
- local wall-clock은 `ZoneRules.getValidOffsets`로 해석해 DST gap/ambiguous를
  안정적인 reason code로 거절하고, generation row는 동일 digest replay와 stale/
  failed terminal state를 보존한다.
- callback은 signature/target/issued-at preflight를 inbox claim보다 먼저 수행하고,
  strict duplicate/unknown-key closed envelope를 canonicalize한다. error response에는
  stable code와 request ID만 남긴다. controller origin은 `localhost`/`127.0.0.1`의
  정확한 HTTP origin만 허용한다.
- Micrometer observation은 `result`의 bounded allowlist만 tag로 사용하며 callback
  body, worker, credential, tenant를 metric label로 만들지 않는다.

## 검증 영수증

- `./gradlew :optimization-shift-coverage:cleanTest :optimization-shift-coverage:test --no-build-cache --max-workers=1 --console=plain`
  — 46개 테스트 통과. planner, canonical digest, approval/CAS, swap race,
  idempotency restart, inbox retry, generation/event stale, DST boundary, outbox
  fencing/queue, Java 25 lifecycle, callback canonical preflight, MockMvc error
  matrix, role/redaction, bounded metrics, PostgreSQL authority를 포함한다.
- `./gradlew :optimization-shift-coverage:build --no-build-cache --max-workers=1 --console=plain`
  — BUILD SUCCESSFUL.
- `colima status`, `docker context show`, `docker info` — Colima/Docker healthy;
  Testcontainers PostgreSQL 테스트를 실제 실행했다.
- `bash scripts/smoke-validate.sh optimization` — planning-contracts,
  field-service-dispatch, shift-coverage test group 통과.
- `bash scripts/smoke-validate.sh stale-check` — 125 active modules, stale ref와
  README broken image 없음.
- `actionlint .github/workflows/Examples.yml`, `bash -n scripts/smoke-validate.sh`,
  `git diff --check` — 통과.
- `bootRun` demo smoke — `Started ShiftCoverageApplicationKt` 확인 후 timeout 종료;
  `/actuator/health`가 `UP`, authenticated replan 202, `/actuator/prometheus`에서
  `workshop_shift_coverage_*` 8개 line을 확인했으며 lingering process 없음.

## 남은 범위와 rollback

- 이 module에는 별도 Gradle `detekt` task가 등록되지 않아
  `./gradlew :optimization-shift-coverage:detekt`는 `task 'detekt' not found`로
  실행되지 않는다. 이는 Java 25 workflow에서 detekt를 제외하는 workspace 정책과
  일치하지만, 정식 repository detekt gate가 생기기 전까지 PENDING으로 남긴다.
- full HTTP MockMvc error matrix, restart 후 generation sweep, metrics cardinality
  load/long-run, external Timefold callback integration은 이 demo 범위에 포함하지
  않았다.
- #527/#528/#529, PR 생성·push·merge는 이 변경의 scope가 아니며 #526 DoD 이후
  순서대로 진행한다. 구현을 되돌릴 때는 이 feature branch의 Lore commit만
  revert하고 `develop`과 sibling worktree는 건드리지 않는다.
