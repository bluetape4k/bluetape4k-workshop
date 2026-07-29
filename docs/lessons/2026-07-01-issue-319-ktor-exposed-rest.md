# Issue #319 Ktor Exposed REST

- 배경: 실제 PostgreSQL Testcontainers database와 함께 `bluetape4k-exposed-ktor` JDBC
  transaction helper를 보여주는 Ktor REST workshop module을 추가했다.
- 결정: learner가 production-like example에서 사용할 transaction, readiness, rollback,
  SQL error behavior와 같은 동작을 보도록 H2 대신 `PostgreSQLServer.Launcher.postgres`를
  사용한다.
- 결과: focused module test는 CRUD, rollback, readiness, cancellation, validation,
  sensitive SQL error redaction을 포함한 여섯 PostgreSQL-backed scenario를 실행한다.
- Smoke lesson: `scripts/smoke-validate.sh`에 새 task를 추가하기 전
  `./gradlew projects --console=plain`로 project path를 검증한다. grouped smoke command에서는
  오래된 display name과 directory name을 혼동하기 쉽다.
- Diagram lesson: 새 uncommitted SVG asset의 경우 `smoke-validate.sh diagram-qa`는 변경된
  committed path를 발견하는 방식이라 약할 수 있다.
  `node scripts/validate-readme-diagram-qa.mjs <new-svg...>`를 명시적으로 실행하고, checklist
  완료를 주장하기 전에 rendered PNG를 full size로 검사한다.
- Architecture connector lesson: connector path가 curve 또는 crowded bend로 끝나면
  CairoSVG가 raw SVG가 암시하는 것과 다르게 marker direction을 렌더링할 수 있다.
  reader-facing architecture flow arrow에는 final coordinate 변경 후 단순 perpendicular route와
  direct polygon arrowhead를 선호하고, push 전에 PNG를 검사한다.
- 향후 작업자: sequence diagram은 현재 best-practices palette를 유지한다. line 위 numbered
  label, 일치하는 arrowhead/line color, transparent `alt` body, centered card text, repo
  validator가 marker와 connector를 audit할 수 있는 SVG metadata가 필요하다.
