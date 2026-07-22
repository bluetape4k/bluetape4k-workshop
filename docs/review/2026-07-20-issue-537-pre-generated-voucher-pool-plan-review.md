# #537 사전 생성 바우처 풀 구현 계획 리뷰

Date: 2026-07-20

Plan: `docs/superpowers/plans/2026-07-20-issue-537-pre-generated-voucher-pool-plan.md`

Approved design: `docs/superpowers/specs/2026-07-20-issue-537-pre-generated-voucher-pool-design.md`

Final reviewed plan head: `ef93a7b9c1247d7e6ec8a16df7f013754ebac6ef`

Scope: 승인된 설계를 실행 가능한 구현 계획으로 변환한 결과만 검토했다. 구현 코드는 시작하지 않았다.

## 결론

- Type A 구현 계획을 performance, stability, security, operator/Ops, developer/API,
  user/caller의 여섯 독립 관점으로 반복 검토했다.
- 최종 동일 계획 HEAD `ef93a7b9`에서 모든 관점이 `P0=0, P1=0`으로 수렴했다.
- 15개 작업이 설계 acceptance criterion을 구체적인 파일, TDD RED/GREEN 순서, PostgreSQL·Redis
  경합 검증, CI 등록, 운영 증거와 rollback 지점에 연결한다.
- 이 문서는 계획 수렴 증거이며 구현 승인을 대신하지 않는다. 별도 승인 전에는 production/test 코드를
  생성하지 않는다.

## 반복 검토 결과

### 1차 전면 검토 — 계획 HEAD `966eefc9`

모든 관점의 P0는 0건이었다. P1은 전부 후속 계획 보정에 반영했고, 구현 안전성·검증 가능성과 직접
관련된 P2도 함께 통합했다.

| 관점 | P1 | P2 | 주요 보정 요구 |
|---|---:|---:|---|
| Performance | 5 | 2 | permit/timeout 수치, 경합 profile, query-plan ceiling |
| Stability | 2 | 1 | lease/deadline, 종료·재시작 복구 증거 |
| Security | 2 | 2 | principal/tenant authority, operator preview 인증 |
| Operator/Ops | 3 | 2 | retention, backup/restore, alert·runbook 검증 |
| Developer/API | 6 | 3 | route matrix, Gradle task 실행성, Kover 계약 |
| User/Caller | 3 | 2 | reveal-loss, retry, 오류 vocabulary |

### 2차 전면 검토 — 계획 HEAD `f81bddea`

| 관점 | P0 | P1 | 결과 |
|---|---:|---:|---|
| Performance | 0 | 3 | 측정 가능한 hard assertion과 고정 profile이 추가로 필요 |
| Stability | 0 | 0 | PASS |
| Security | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | PASS |
| Developer/API | 0 | 1 | custom Test task의 source/tag/zero-test 계약 보강 필요 |
| User/Caller | 0 | 0 | PASS |

위 지적은 `c266ef20`에서 stress matrix, 고정 query-plan ceiling, winner/count/Hikari hard assertion과
custom Test task의 `src/test` 재사용, tag include/exclude, zero-test 실패, XML 확인으로 닫았다.

### 수렴 검토

- `c266ef20`의 affected-lens 검토에서 performance와 developer/API는 PASS했다.
- stability가 shutdown timeout 시 cancel, rollback, claim release를 실제로 실행하는 검증 누락 1건을
  발견했고 `bd6bb44c`에서 12초 drain과 5초 강제 종료 분기를 테스트하도록 보정했다.
- `bd6bb44c`의 동일 HEAD 여섯 관점 검토에서 developer/API가 승인 설계에 없는 public error
  `REPLACEMENT_LIMIT_REACHED` 1건을 발견했다.
- `ef93a7b9`에서 두 번째 lost-reveal도 승인된 `ALREADY_REVEALED`와
  `replacementAvailable=false`로 표현해 공개 오류 vocabulary를 설계와 일치시켰다.
- 마지막으로 `ef93a7b9c1247d7e6ec8a16df7f013754ebac6ef` 자체를 여섯 관점에서 다시 검토해
  동일 HEAD 수렴을 확인했다.

## 주요 보정 내용

### 빌드와 검증 계약

- Java 25 module toolchain, Spring Boot 4.1.0, repository Kotlin/API 계약과
  `bluetape4k-dependencies` 단일 BOM authority를 명시했다.
- Kover 0.9.8은 module-local report-only XML로 제한하고 hard threshold나 Codecov uploader를 새로
  도입하지 않는다. 로컬과 Examples CI에서 XML artifact 존재를 확인한다.
- `migrationCompatibilityTest`와 `stressTest`는 `src/test`를 재사용하되 명시 tag만 실행하며,
  기본 `test`에서는 해당 tag를 제외한다. 선택된 테스트가 0개면 실패하도록 했다.

### API와 보안 경계

- customer `/api/v1` route, operator route, request/response/error DTO와 HTTP status를 작업별 RED/GREEN
  테스트에 연결했다.
- tenant와 principal은 신뢰 가능한 resolver에서만 취득하고, operator preview는 loopback/Host/Origin
  검증과 별도 token을 요구한다.
- raw voucher code, digest, idempotency key와 secret은 최초 reveal 응답 외의 log, metric, audit,
  descriptor와 exception message에 노출하지 않는다.
- 공개 오류는 승인 설계 vocabulary만 사용하고, replacement 소진은 새 error code 대신
  `ALREADY_REVEALED`의 안전한 descriptor로 표현한다.

### Concurrency와 lifecycle

- database permit lane을 foreground 12, worker 1, SSE 3으로 고정하고 대기 한도를 각각 250ms,
  1초, 1초로 명시했다.
- Hikari acquisition 2초, transaction 5초, worker chunk 10초와 worker lease/deadline/backoff를
  acceptance assertion으로 만들었다.
- shutdown은 12초 drain 후 미완료 작업을 cancel하고 5초 안에 rollback과 claim release를 확인하며,
  전체 lifecycle은 45초 이내로 제한한다. 정상 drain과 timeout 강제 종료를 모두 테스트한다.
- worker claim, cursor/checkpoint CAS, poison/cancel, restart recovery와 canonical lock order를 실제
  PostgreSQL transaction barrier로 검증한다.

### 성능과 운영 증거

- 4개 고정 stress profile에 participant, operation, permit, timeout 값을 명시하고 winner 수, DB row
  delta, duplicate effect 0, Hikari active/pending upper bound를 hard assertion으로 둔다.
- 주요 query는 고정 dataset에서 `EXPLAIN (ANALYZE, BUFFERS)` ceiling과 index 사용을 검증한다.
- retention dependency order, key inventory, backup/restore, tombstone replay fence와 restore smoke를
  같은 운영 검증 흐름에 넣었다.
- readiness degradation, pool exhaustion, worker stall, key/ciphertext 문제, purge lag와 restore failure를
  low-cardinality metric, alert, runbook과 연결했다.

## 최종 동일 HEAD 판정

| 관점 | P0 | P1 | 최종 확인 범위 |
|---|---:|---:|---|
| Performance | 0 | 0 | permit/Hikari 예산, 4-profile stress, query-plan ceiling |
| Stability | 0 | 0 | lease/restart, shutdown drain·cancel·rollback, claim release |
| Security | 0 | 0 | tenant/principal authority, operator guard, secret/code 비노출 |
| Operator/Ops | 0 | 0 | retention, backup/restore, alert, diagnostics와 runbook |
| Developer/API | 0 | 0 | route/error 계약, Gradle task, Kover와 zero-test guard |
| User/Caller | 0 | 0 | one-time reveal, lost-reveal replacement, retry와 terminal error |

## 정적 증거

- 계획의 placeholder·미결정 표식 스캔
- Markdown code fence 균형 검사
- `git diff --check`와 staged diff 검사
- guarded receipt run `20260720T132322Z-ad242335`의 여섯 최종 lane 완료 및 checksum 검증
- 최종 검토 대상 계획 HEAD: `ef93a7b9c1247d7e6ec8a16df7f013754ebac6ef`

## 다음 게이트

사용자가 이 구현 계획을 명시적으로 승인한 뒤에만 Task 1부터 TDD 순서로 구현한다. 구현 단계에서는
계획에 적힌 targeted test를 먼저 실행하고, module test/build, detekt, Kover XML, Examples/Nightly
workflow와 repository validation matrix를 순차 검증한다. PR 생성, push와 merge는 각각 해당 작업의
별도 승인·게이트를 따른다.
