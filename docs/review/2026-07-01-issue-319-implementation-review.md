# Issue #319 Implementation Review

- 날짜: 2026-07-01
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/319
- Spec: `docs/superpowers/specs/2026-07-01-issue-319-ktor-exposed-rest-design.md`
- Plan: `docs/superpowers/plans/2026-07-01-issue-319-ktor-exposed-rest-plan.md`
- 리뷰 게이트: Step 6-R

## 판정

| 심각도 | 건수 | 상태 |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 0 | PASS |
| P3 | 2 | 문서화된 잔여 위험 |

P0=0, P1=0이므로 Step 6-R은 진행할 수 있다.

## 관점별 발견사항

### 1. Transaction And Data Consistency

- P0/P1: 없음.
- 근거: 예제는 H2 substitute 대신 `bluetape4k-exposed-ktor` transaction helper와 PostgreSQL 기반 `bluetape4k-testcontainers` 테스트를 사용한다.
- 근거: rollback endpoint는 transaction 안에서 row를 생성한 뒤 exception을 던진다. 테스트는 error response와 rollback 이후 PostgreSQL result set이 비어 있음을 검증한다.
- 근거: request validation은 Exposed transaction에 들어가기 전에 수행되므로, 일반적인 invalid input이 Exposed transaction failure로 잘못 분류되지 않는다.

### 2. Stability And Cancellation

- P0/P1: 없음.
- 근거: `/api/failures/cancelled`는 cancellation이 `EXPOSED_TRANSACTION_FAILED` 또는 `EXPOSED_DATABASE_UNAVAILABLE`로 변환되지 않는다는 점을 검증한다.
- 근거: `KtorExposedRestResources`는 Hikari datasource와 JDBC dispatcher를 소유하며 `ApplicationStopped`에서 둘 다 닫는다.

### 3. Security And Error Redaction

- P0/P1: 없음.
- 근거: SQL failure 테스트는 fake JDBC URL, user, password, SQL text를 포함한다. response는 해당 민감 문자열을 생략한다고 assert한다.
- 근거: Transaction rollback response는 실패한 title과 PostgreSQL connection detail을 생략한다고 assert한다.

### 4. Operator And CI

- P0/P1: 없음.
- 근거: `.github/workflows/Examples.yml`은 module path filter, container-backed Gradle task, test artifact path를 포함한다.
- 근거: `scripts/smoke-validate.sh data-access-full`은 새 `:ktor-exposed-rest:test` task를 포함하며, 기존 Exposed/Javers 예제에는 실제 project path를 사용하도록 수정되었다.
- 근거: `./gradlew projects --console=plain`은 `:ktor-exposed-rest`를 보고했고 성공적으로 완료되었다.

### 5. Developer/API

- P0/P1: 없음.
- 근거: module은 root catalog alias와 versionless bluetape4k coordinate를 사용한다. local bluetape4k version을 pin하지 않는다.
- 근거: test resource는 repo module convention과 맞게 `junit-platform.properties`와 `logback-test.xml`을 포함한다.
- 근거: public-facing README artifact는 `README.md`와 `README.ko.md` pair로 구성된다.

### 6. User/Caller

- P0/P1: 없음.
- 근거: README pair는 dependency boundary, architecture, transaction flow, route example, local PostgreSQL Testcontainers validation, manual PostgreSQL environment variable, rollback behavior, error redaction contract를 설명한다.
- 근거: Root README table은 English와 Korean 양쪽에 focused test command와 함께 새 data-access example을 포함한다.

## Diagram Verification

수정된 diagram:

- `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.png`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.png`

근거:

- `xmllint --noout`: 두 SVG 파일 모두 PASS.
- `cairosvg -s 2`: 두 PNG render output 모두 PASS.
- `node scripts/validate-readme-diagram-qa.mjs
  docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg
  docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`: PASS,
  `targets=2`, `weak_reference_rows=0`.
- Architecture marker audit: PASS, `markers_checked=3`, `marker_failures=0`; flow arrowhead는 `marker-end` 대신 direct polygon head를 사용한다.
- Architecture connector audit: PASS, `connectors=7`, `cards=7`, `intrusions=0`, `crossings=0`.
- Architecture mixed-corner audit: PASS, `paths=4`, `q_bends=0`, `failures=0`.
- Architecture direct-head invariant: PASS, `flow_connectors=4`, `direct_arrowheads=4`, `flow_marker_end=0`, `flow_q_segments=0`, `flow_diagonal_candidates=0`.
- Sequence marker audit: PASS, `markers_checked=8`, `marker_failures=0`.
- Sequence fallback audit: PASS, `labels=8`, `numbers=8`, `monotonic=true`, `alt_fill_failures=0`.
- Sequence style reference audit: PASS, `sequence_files=1`.
- Full-size PNG eye inspection: 최종 connector, marker, text alignment, transparent `alt`, palette check 이후 생성된 두 PNG 파일 모두 PASS.

Icon 근거:

- `testcontainers.postgresql`: `bluetape4k-wiki`의 `docs/icons/testcontainers/database/postgresql.svg`에 있는 official PostgreSQL icon이며, architecture SVG에 catalog metadata가 embedded되어 있다.

## Validation Evidence

- `./gradlew :ktor-exposed-rest:compileKotlin`: PASS.
- `./gradlew :ktor-exposed-rest:cleanTest :ktor-exposed-rest:test
  --rerun-tasks --warning-mode all --console=plain --max-workers=1`: PASS,
  `SUCCESS: Executed 6 tests in 3s`.
- `./scripts/smoke-validate.sh data-access-full`: PASS,
  `BUILD SUCCESSFUL in 2m 8s`.
- `./scripts/smoke-validate.sh stale-check`: PASS, active modules
  `91 (expected: 91)`, stale refs `0`, broken image links `0`.
- `./gradlew projects --console=plain`: PASS, new project present.
- `actionlint .github/workflows/Examples.yml`: PASS.
- `git diff --check`: PASS.

## 메모와 잔여 위험

- P3: 이 세션에서는 IDE diagnostic을 사용할 수 없었다. fallback은 fresh Gradle compilation, focused Testcontainers test, smoke validation이었다.
- P3: Gradle 9.6에서 root Gradle deprecation warning이 계속 보이지만, 이는 기존 root build logic에서 발생하며 이 issue가 건드린 code 범위 밖이다.

## 열린 질문

없음.
