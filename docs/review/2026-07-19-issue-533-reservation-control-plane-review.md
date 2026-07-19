# #533 Reservation Control Plane 검토

Date: 2026-07-19
Module: `:commerce-reservation-control-plane`
Scope: `commerce/reservation-control-plane`
Branch: `feature/issue-533-reservation-control-plane`

## 결론

- 최종 독립 재검토: P0 0건, P1 0건
- PostgreSQL row lock, revision, idempotency record가 예약 correctness의 최종 권위다.
- Redis/Lettuce와 leader 기능은 advisory coordination으로만 사용하며 장애 시 PostgreSQL 경계를 약화하지 않는다.
- 모든 operational class는 `bluetape4k-logging`을 사용하고 raw key, payload, credential을 기록하지 않는다.
- API와 테스트는 Java 25 virtual thread, live Tomcat, bounded HikariCP 설정을 함께 검증한다.

## 핵심 검토 결과

| 관점 | 결과 | 근거 |
|---|---|---|
| Performance | PASS | virtual thread 요청 수용과 HikariCP DB admission을 분리하고 Redis hint로 중복 작업을 줄임 |
| Stability | PASS | Redis unavailable bootstrap, node-local fallback, outbox retry, PostgreSQL transaction rollback 테스트 |
| Security | PASS | credential digest 저장, operator role/key 검증, security headers, logging redaction 테스트 |
| Operator/Ops | PASS | request/fallback/sweeper/outbox lifecycle logging과 local operator snapshot/action 경계 |
| Developer/API | PASS | `bluetape4k-exposed-jdbc` repository, typed domain policy, English KDoc, live HTTP contract |
| User/Caller | PASS | bilingual scenario README, architecture/sequence diagrams, deterministic conflict/error response |

## 동시성 수정 검증

초기 검토에서 만료된 hold batch가 더 오래된 offer를 계속 밀어낼 수 있는 P1을 발견했다.
`ReservationResourceTransactionService`가 hold와 offer 후보를 `expiresAt`, `resourceId`로 전역
정렬한 다음 resource별 중복 제거와 `limit`을 적용하도록 수정했다. 32개의 늦게 만료된 hold와
하나의 더 오래된 offer를 구성한 회귀 테스트가 32개 제한에서도 offer resource를 첫 후보로
선택함을 검증한다.

## Ecosystem 및 dependency 확인

- 단일 BOM: `io.github.bluetape4k:bluetape4k-dependencies:1.3.1`
- Exposed 버전: central dependencies BOM 기준
- `bluetape4k-exposed-jdbc`와 `bluetape4k-exposed-jdbc-tests`
- `bluetape4k-testcontainers`의 `PostgreSQLServer`
- `bluetape4k-virtualthread-api`와 `bluetape4k-virtualthread-jdk25`
- Redis coordination: Lettuce
- 모든 operational logging: `bluetape4k-logging`

## 검증 결과

- `:commerce-reservation-control-plane:test --rerun-tasks`: PASS, 58 tests
- `:commerce-reservation-control-plane:build`: PASS
- `ktlint` module main/test: PASS
- root `detekt`: PASS (`NO-SOURCE`; module-local detekt task 없음)
- `git diff --check`: PASS
- production Kotlin KDoc: 43/43 files, 83 KDoc blocks
- comment-only audit: executable line changes 0
- architecture/sequence diagram QA와 원본 PNG inspection: PASS

## 비차단 항목

- 전역 README validator는 변경 범위 밖 `image-processing/profile-image-moderation/README.md`의
  기존 language switch/한글 표기 문제를 계속 보고한다. #533이 변경한 README 쌍에는 같은 문제가 없다.
- 장시간 부하에서 PostgreSQL pool wait와 Redis reconnect 분포를 측정하는 benchmark는 예제의
  correctness acceptance가 아니므로 별도 성능 작업으로 남긴다.
