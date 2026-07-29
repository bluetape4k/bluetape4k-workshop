# #534 프로모션 바우처 캠페인 검토

Date: 2026-07-20
Module: `:commerce-promotion-voucher-campaign`
Scope: `commerce/promotion-voucher-campaign`
Branch: `feature/issue-534-promotion-voucher-campaign`

## 결론

- 최종 여섯 관점 재검토: P0 0건, P1 0건.
- PostgreSQL transaction, row lock, revision, idempotency descriptor가 campaign capacity와 claim
  transition의 유일한 correctness authority다.
- Redis/Lettuce, Bloom signal, leader scheduling은 advisory/coordination 경계이며 장애가
  PostgreSQL invariant를 우회하지 않는다.
- Java 25 virtual thread의 HTTP concurrency와 HikariCP 최대 16 connection, 12/1/3 database
  permit lane을 분리했다.
- live `WebTestClient`, migration compatibility, backup/restore, restart, contention stress가
  사용자·운영 경계를 실제 Tomcat/PostgreSQL 위에서 검증한다.

## 여섯 관점 검토

| 관점 | 결과 | 근거 |
|---|---|---|
| Performance | PASS | Tomcat admission과 Hikari 16/permit 12+1+3을 분리하고, 두 차례 64/128 concurrency stress에서 pool/permit 상한을 검증 |
| Stability | PASS | PostgreSQL authority, Redis timeout fallback, lost-response replay, restart, migration/backup recovery, leader-bounded reconciliation |
| Security | PASS | loopback operator guard, one-time code 조회 분리, versioned key retention, raw tenant/principal/code/key/secret/body logging 금지 |
| Operator/Ops | PASS | PostgreSQL/Redis/leader/worker/SSE/key별 signal, threshold, action, recovery runbook과 stable error/retry catalog |
| Developer/API | PASS | `bluetape4k-exposed-jdbc` repository, English KDoc, closed response descriptor, live HTTP contract, deterministic fixtures |
| User/Caller | PASS | bilingual curl/browser cookbook, code acknowledgement, review/reconciliation, SSE reconnect/polling fallback, architecture/sequence diagrams |

## Resolved dependency evidence

버전은 module에서 직접 고정하지 않고 `bluetape4k-dependencies:1.3.1` 하나로 관리한다. 2026-07-20
`runtimeClasspath` resolution 결과는 다음과 같다.

- `bluetape4k-logging`, `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25`: `1.11.0`
- `bluetape4k-exposed-jdbc`, `bluetape4k-exposed-spring-modulith`: `1.11.0`
- Exposed core/JDBC: `1.3.0`
- `bluetape4k-leader-*`: `0.4.0`
- Spring Boot starter web `4.1.0`, Spring Modulith `2.1.0`
- PostgreSQL JDBC `42.7.11`, HikariCP `7.1.0`, Lettuce `7.5.2.RELEASE`, Bucket4j `8.19.0`
- test scope는 `bluetape4k-exposed-jdbc-tests`와 `bluetape4k-testcontainers`의
  `PostgreSQLServer`/`RedisServer`를 사용한다.

## 검증 결과

- module test suite: PASS, 182 tests.
- migration compatibility: PASS; clean/warm/previous-schema/checksum/partial-DDL/previous-binary 경계.
- stress run-1/run-2: 각각 4 profile PASS, 총 8 profile execution과 profile별 JSON/JFR/manifest.
- stress correctness: allocation/redemption 500/500, resource leak 0, Hikari active 최대 12/16,
  DB permit 최대 12/16, Redis command evidence와 stable 409/429/503 포함.
- dependency scan: JDK25 provider/Exposed JDBC/Lettuce/Bucket4j/leader/logging 포함, JDK21 provider 없음.
- production/test forbidden scan: `MockMvc`, JDK21 provider, `println`, `printStackTrace` 사용 없음.
- module-local `detekt`/`detektTest` task는 등록되어 있지 않다. 동일 범위는 compile, tests,
  forbidden-pattern scan, repository validators로 대체 검증했다.
- README/diagram/runbook/workflow/stale-check/Commerce lane/root compile 검증: 최종 실행 결과를 아래
  exact command evidence로 유지한다.

## 최종 검증에서 발견한 test isolation 결함

첫 `--rerun-tasks` module suite는 170개 중 21개가 `500 INTERNAL_SERVER_ERROR`로 실패했다.
실패 원문은 `relation "voucher_campaigns" does not exist`와
`relation "voucher_http_idempotency" does not exist`였다. Test timestamp를 비교하면
`VoucherRepositoryTest`와 `HttpIdempotencyRepositoryTest`가
`withTables(TestDB.POSTGRESQL, ...)`를 완료한 직후 web integration class 전체가 실패했다.

원인은 Exposed test fixture가 shared `public` schema의 production 이름 table을 정리한 반면,
application migration history는 남아 후속 context가 schema를 이미 적용된 것으로 판단한 것이다.
`VoucherTestSchema`가 application-context/restart test용 Base58 schema를 생성하고 datasource
`currentSchema`를 고정하도록 수정했다. 같은 suite의 clean rerun은 179/179 PASS했고, 이어서
migration compatibility와 두 stress run도 통과했다.

## 독립 구현 리뷰에서 보강한 경계

- allocation과 redemption review signal을 operation별 one-shot 경계로 분리해 redemption이 실제
  `REDEMPTION_REVIEW_REQUIRED` HTTP 응답으로 진입하도록 했다.
- fixture 요청 replay는 저장된 descriptor만 반환하고 signal이나 event side effect를 다시 arm하지
  않도록 고정했다.
- operator가 review와 reconciliation backlog를 opaque cursor로 조회하고, demo profile에서 tenant
  domain rows와 one-shot signal만 반복 안전하게 reset할 수 있게 했다.
- unexpected exception logging은 exception class만 기록하고 throwable과 민감 message를 logger에
  전달하지 않는 계약으로 고정했다.
- browser cookbook의 11개 항목을 실제 reset/create/activate/command/verify choreography로 연결했다.
  delayed fixture는 apply, duplicate, lower-sequence event를 reconciliation 경계에 실제 제출한다.
- 각 race는 단순 fulfilled 개수가 아니라 loser의 bounded HTTP status/error code와 authoritative
  snapshot state/capacity까지 검증한다. Delayed fixture는 실제 `APPLIED`, `IGNORED`, `CONFLICT`
  acceptance evidence를 저장된 descriptor로 replay한다.
- destructive scenario/reset과 review approve/reject는 모두 confirmation dialog를 통과한다. Browser는
  open review queue와 reconciliation backlog를 operator GET으로 읽고 review 결정을 수행한다.
- Browser가 safe GET에 `Origin`을 생략하는 경우 same-origin custom header를 사용한다. Cross-origin
  browser는 preflight에서 차단되고 server는 header origin을 현재 host/port와 다시 비교한다.
- delayed fixture가 만드는 audit revision을 active campaign revision 안에 유지하고, fixture 직후 SSE
  cursor가 reset 없이 resume되는 integration test를 추가했다.
- process-local one-shot fixture signal의 configure/reset mutation은 enclosing Spring/Exposed transaction의
  `afterCommit`에서만 반영한다. Rollback이면 signal map도 이전 상태를 유지한다.

## 실제 브라우저 최종 확인

Chromium에서 최신 application을 Java 25로 `127.0.0.1:18080`에 띄우고 동일 session에서
`allocation-review` queue 조회와 approve action을 수행한 뒤 `delayed-duplicate-out-of-order`와
`capacity-race`를 연속 실행했다. 이어 `policy-change`와 `redeem-revoke-race`도 같은 browser session에서
실행했다. Review action 뒤 open queue가 비었고 backlog는 authoritative
operator GET으로 표시됐다. Delayed 시나리오는 apply,
duplicate, stale evidence를 남겼고 두 번째는 capacity 2에 대해 정확히 2 winners와 6
authoritative rejections 및 remaining capacity 0을 확인했다. Policy race는 policy version 1,
capacity/remaining capacity 12로 수렴했고 terminal race는 이 실행에서 `REVOKED`, remaining capacity
10으로 수렴했다. 각 tenant reset은 기존 SSE를 닫고 cursor/reconnect state를
비운 뒤 campaign을 재생성했으며, reset 이후 새 event stream은 모두 HTTP 200이었다. 경쟁에서
발생한 HTTP 409 여섯 건은 의도한 `CAPACITY_EXHAUSTED` 결과다.

## 최종 exact command evidence

```text
./gradlew :commerce-promotion-voucher-campaign:test --rerun-tasks --max-workers=1
  PASS, 182 tests, 0 failures
./gradlew :commerce-promotion-voucher-campaign:migrationCompatibilityTest --rerun-tasks --max-workers=1
  PASS, 1 test, 0 failures
./gradlew :commerce-promotion-voucher-campaign:stressTest -PvoucherStressRun=final-1 --rerun-tasks --max-workers=1
  PASS, 4 profiles, JSON 4, JFR 4, manifest 1
./gradlew :commerce-promotion-voucher-campaign:stressTest -PvoucherStressRun=final-2 --rerun-tasks --max-workers=1
  PASS, 4 profiles, JSON 4, JFR 4, manifest 1
./scripts/smoke-validate.sh commerce
  PASS, three sequential Commerce modules, --max-workers=1
./gradlew build -x test --parallel --continue
  PASS across the 104-project graph
EXPECTED_GRADLE_PROJECTS=104 ./scripts/smoke-validate.sh stale-check
  PASS, 104 projects, no stale refs, no broken README images
node scripts/validate-voucher-runbook.mjs
  PASS, 2 locales, 30 stable codes, 7 scenarios, 6 subsystem rows, 2 diagrams
node scripts/validate-readme-architecture-diagrams.mjs
  PASS, 0 failures
node scripts/validate-sequence-diagrams.mjs
  PASS, 0 failures
bash -n scripts/smoke-validate.sh
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
git diff --check
  PASS
```

## 비차단 범위

- voucher pool 사전 생성은 #537, event-sourced reconstruction은 #538로 분리했다.
- redemption 이후 reversal/compensation은 구현하지 않는다. 이 예제의 revoke는 terminal
  transition 경쟁 전에만 승리할 수 있다.
- workshop operator boundary는 loopback/local profile 교육용이며 production IAM, OAuth, CSRF를
  대체하지 않는다.
- stress latency/throughput은 동일 환경 비교 evidence이며 portable CI hard gate가 아니다.
- 전역 language/parity validator는 변경 범위 밖
  `image-processing/profile-image-moderation/README.md`의 기존 English language switch와 한글 문구를
  보고한다. #534 README 쌍은 전용 runbook validator의 heading/fence/image parity와 두 diagram
  validator를 모두 통과했다.
- module-local `detekt`와 `detektTest` task는 등록되어 있지 않아 계획의 해당 command는
  `task 'detekt' not found`로 종료된다. Module compile/test, root build, forbidden-pattern scan으로
  변경 범위를 검증했다.
