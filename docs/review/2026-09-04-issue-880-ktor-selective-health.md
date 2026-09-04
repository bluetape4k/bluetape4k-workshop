# Issue #880 Ktor Exposed backend-selective health/readiness 구현 리뷰

## 범위

- 대상: `ktor/exposed-rest`의 dependency graph, Ktor 설치 조합, JDBC transaction/readiness,
  core contract 테스트와 consumer 문서
- 기준: dependency `2.0.0`, 기존 PostgreSQL REST 예제의 CRUD/rollback/error/cancellation 회귀 없음

## 확인 결과

| 검토 항목 | 결과 | 근거 |
|---|---|---|
| core-only readiness deadline | 통과 | backend-neutral fake probe가 하나의 monotonic budget에서 `TIMEOUT`을 반환 |
| fixed error catalog/redaction | 통과 | transaction cause의 JDBC URL, SQL, user, password가 응답에 노출되지 않음 |
| JDBC dispatcher/transaction 경계 | 통과 | `exposedKtorJdbcReadinessProbe`와 `exposedJdbcTransaction`에 caller dispatcher 전달 |
| PostgreSQL CRUD/readiness | 통과 | 기존 Ktor Testcontainer 테스트 6개 통과 |
| selective dependency graph | 통과 | root BOM + versionless core/JDBC alias; R2DBC/cache direct dependency 없음 |
| legacy compatibility 안내 | 통과 | `bluetape4k-exposed-ktor` aggregator를 호환성 경로로 문서화하고 신규 코드는 selective alias 사용 |
| consumer 등록 | 통과 예정 | root/module README, coverage matrix, workflow, stale-check, lesson 동시 수정 |

## 리스크와 후속 조치

- R2DBC/cache를 추가하는 consumer는 backend adapter와 해당 resource lifecycle을
  별도로 소유하고, core probe 목록에 명시적으로 등록해야 한다.
- JDBC statement timeout과 interrupt 동작은 driver가 제공하는 cancellation 의미에
  의존하므로 운영 driver 조합을 별도로 확인한다.
- Testcontainers 검증은 PostgreSQL 대표 경로에 집중했으며 R2DBC/cache 실연동은 이
  JDBC 예제 범위에 포함하지 않았다.
- #878·#879가 누적된 stacked branch에서는 ecosystem reuse scope가 parent 경로까지
  포함되어야 하므로 #880에서도 fresh coordinator receipt와 exact `--pr-scope`
  checker 결과를 함께 기록한다.

## 리뷰 결론

새 backend를 추가하지 않고 기존 Ktor Exposed consumer의 dependency surface와
readiness 경계를 2.0.0 selective artifact 계약에 맞춘 Type B 변경이다. hosted CI와
exact-head 검증이 통과하면 병합 가능하다.
