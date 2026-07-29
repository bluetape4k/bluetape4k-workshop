# Issue #323 Spring Modulith Module Boundaries

- 배경: milestone `1.3.1`을 위한 focused Spring Modulith boundary-verification workshop
  example로 `spring-modulith/module-boundaries`를 추가했다.
- 결정: H2/PostgreSQL 대신 in-memory four-module graph를 사용한다. 이 lesson은 persistence
  behavior가 아니라 named interface, allowed dependency, rejected internal import에 관한 것이다.
- 결과: `catalog :: api`, `ordering :: events`, `payment`, `notification`은 유효한 module
  graph를 구성하고, test-only `payment -> ordering.internal` fixture는 Spring Modulith
  `Violations`로 실패한다.
- Diagram lesson: architecture diagram은 visible legend가 있는 layered 구조로 유지하고,
  sequence diagram은 call line 위 numbered label, 일치하는 arrowhead/line color, transparent
  grouped region을 사용하는 best-practices style로 유지한다.
- Visual QA lesson: architecture `Q` bend는 pre-bend point가 실제 corner를 지나치면 syntax
  check를 통과하면서도 시각적으로 뒤로 꺾일 수 있다. sibling route의 overshoot/backtracking을
  audit하고 straight connector의 fake `Q` segment를 제거한다.
- Visual QA lesson: SVG arrowhead가 올바르게 보여도 rendered PNG가 다르게 가리키면
  marker-dependent architecture arrow를 유지하지 않는다. direct head geometry를 사용하고,
  head tip이 connector endpoint와 일치하는지 검증하며, final PNG를 full size로 검사한다.
- 검증 증거:
  - `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1 --rerun-tasks` passed.
  - `./gradlew projects --console=plain` reported 93 active modules and
    `:spring-modulith-module-boundaries`.
  - `node scripts/validate-readme-diagram-qa.mjs ...module-boundaries...` passed
    with 2 targets, 0 weak reference rows, architecture connector/card checks,
    and sequence style checks.
  - README language/parity validators, `smoke-validate.sh stale-check`,
    `actionlint .github/workflows/Examples.yml`, and `git diff --check` passed.
- 향후 작업자: boundary-verification example에는 positive graph와 self-contained invalid
  test fixture를 모두 추가한다. positive-only `ApplicationModules.verify()` test는 intended
  leak를 규칙이 잡는지 증명하지 못하므로 너무 약하다.
