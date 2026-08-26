# Issue #526 Shift Coverage 구현 교훈

## 결과

`optimization/shift-coverage`에 synthetic multi-site worker/shift coverage와
사람이 확인하는 shift swap의 결정적인 demo 경계를 추가했다. 기본 `demo`
profile은 recorded fake adapter와 loopback API만 사용하고, `postgres` profile은
assignment approval/swap CAS와 command idempotency claim을 PostgreSQL로 보낸다.
planner proposal은 immutable 기준 데이터 digest와 stable reason code를 보존하며,
assignment projection은 approval 또는 CAS 기반 swap acceptance에서만 변경된다.

하드닝 단계에서는 controller가 nullable remote address를 허용하지 않도록 strict
loopback guard를 적용하고, 정확한 HTTP `Origin` allowlist와 operator recovery 경계를
같은 규칙으로 맞췄다. error response는 code별 bounded `nextAction`을 포함하고,
retryable replan rejection만 `Retry-After: 1`을 반환한다. generation restart sweep은
RUNNING만 FAILED로 복구하고 terminal state는 보존하며, callback/result metrics는 여섯
metric family와 bounded result allowlist를 넘지 않는다.

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
  stable code/request ID와 bounded `nextAction`만 남긴다. controller origin은
  `localhost`/`127.0.0.1`의
  정확한 HTTP origin만 허용한다.
- directory URL `/shift-coverage/`는 static `index.html`로 명시적으로 redirect하며,
  static console은 CSP와 `textContent`/`replaceChildren` 기반 safe DOM으로 렌더링한다.
  202 accepted, 409 revision conflict, 413 oversize, 429 retry-after 상태를 bounded
  사용자 상태로 표시하고 redacted API만 호출한다.
- Micrometer observation은 `result`의 bounded allowlist만 tag로 사용하며 callback
  body, worker, credential, tenant를 metric label로 만들지 않는다.
- `postgres` profile은 `SHIFT_COVERAGE_DATABASE_*` 환경값이 없으면 시작하지 않으며,
  저장소 기본 JDBC URL·credential을 제거해 demo와 authoritative DB 경계를 분리한다.
- `postgres` profile의 현재 durable 경계는 assignment와 command idempotency이며,
  plan/generation/inbox/outbox는 bounded in-memory seam으로 남겨 restart durability를
  주장하지 않는다.

## 검증 영수증

- `./gradlew :optimization-shift-coverage:cleanTest :optimization-shift-coverage:test --no-build-cache --no-daemon --max-workers=1 --console=plain`
  — 67개 테스트 통과. planner, canonical digest, approval/CAS, swap race,
  idempotency restart, inbox retry, generation/event stale, DST boundary, outbox
  fencing/queue, Java 25 lifecycle, callback canonical preflight, MockMvc error
  matrix, stable error contract, restart generation sweep, role/redaction, bounded
  metrics cardinality, PostgreSQL assignment/idempotency wiring, route/CORS golden matrix와 browser static
  contract를 포함한다.
- `ShiftCoverageMockMvcRouteMatrixTest` + `ShiftCoverageCallbackPreflightTest` +
  `ShiftCoverageSpringWiringTest` — 9개 targeted 테스트 통과. manager/worker
  query·command·approval·swap, worker redaction, callback/operator no-write,
  hostile Origin, console redirect와 CSP/safe-DOM/202·409·413·429 문자열을 고정한다.
- Playwright demo smoke — `/shift-coverage/` response `200`, CSP와 empty-state row,
  replan accepted 상태와 1초 후 refresh, rendered row, browser console error `0`을
  확인했다. loopback demo process는 검증 후 종료했다.
- `./gradlew :optimization-shift-coverage:build --no-build-cache --max-workers=1 --console=plain`
  — BUILD SUCCESSFUL.
- `./gradlew detekt --max-workers=1 --console=plain` — BUILD SUCCESSFUL. Root aggregate에
  등록된 detekt module의 정적 분석 gate를 통과했다.
- `colima status`, `docker context show`, `docker info` — Colima/Docker healthy;
  Testcontainers PostgreSQL 테스트를 실제 실행했다.
- `bash scripts/smoke-validate.sh optimization` — planning-contracts,
  field-service-dispatch, shift-coverage test group 통과.
- `bash scripts/smoke-validate.sh stale-check` — 127 active modules, stale ref와
  README broken image 없음.
- `actionlint .github/workflows/Examples.yml`, `bash -n scripts/smoke-validate.sh`,
  `git diff --check` — 통과.
- `bootRun` demo smoke — `Started ShiftCoverageApplicationKt` 확인 후 명시적 cleanup;
  `/actuator/health`가 `UP`, authenticated replan 202, `/actuator/prometheus`에서
  `workshop_shift_coverage_*` 8개 line을 확인했으며 loopback port 잔류 process 없음.

## 남은 범위와 rollback

- 이 module에는 별도 Gradle `detekt` task가 등록되지 않아
  `./gradlew :optimization-shift-coverage:detekt`는 `task 'detekt' not found`로
  실행되지 않는다. 이는 Java 25 workflow에서 detekt를 제외하고 optimization sibling
  module에도 plugin을 적용하지 않는 workspace 정책과 일치한다. 따라서 module-local
  결과는 `N/A (repository policy)`로 기록하며 module source lint를 했다고 주장하지
  않는다. root aggregate `./gradlew detekt` PASS가 현재 저장소 정적 분석 gate다. 향후
  optimization module detekt 정책이 생기면 이 gap을 다시 검토한다.
- metrics 장시간 외부 부하와 external Timefold callback integration은 이 demo 범위에
  포함하지 않았다. route/status/CORS/browser golden matrix, restart generation sweep,
  bounded cardinality는 이번 보강에서 검증했다.
- #527/#528/#529, PR 생성·push·merge는 이 변경의 scope가 아니며 #526 DoD 이후
  순서대로 진행한다. 구현을 되돌릴 때는 이 feature branch의 Lore commit만
  revert하고 `develop`과 sibling worktree는 건드리지 않는다.

## 문서 작성 게이트

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | lesson의 대상 구현, live evidence, root/module detekt 사실과 잔여 범위를 고정했다. |
| SPW-02 | PASS | 결과·재사용·경계·검증 영수증·rollback/후속 guard를 포함했다. |
| SPW-03 | PASS | Korean naturalness checklist(KO-01..KO-07)를 적용해 기술 용어와 정확한 오류를 보존했다. |
| SPW-04 | PASS | 계획·리뷰·root detekt 정책과 fresh command 결과를 대조했다. |
| SPW-05 | PASS | 최종 Markdown read-back으로 claim 범위와 residual risk를 확인했다. |
