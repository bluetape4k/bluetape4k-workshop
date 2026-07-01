# Issue #323 Spring Modulith Module Boundaries

- Context: Added `spring-modulith/module-boundaries` as a focused Spring
  Modulith boundary-verification workshop example for milestone `1.3.1`.
- Decision: Use an in-memory four-module graph instead of H2/PostgreSQL. The
  lesson is about named interfaces, allowed dependencies, and rejected internal
  imports, not persistence behavior.
- Outcome: `catalog :: api`, `ordering :: events`, `payment`, and
  `notification` form a valid module graph, while a test-only
  `payment -> ordering.internal` fixture fails with Spring Modulith
  `Violations`.
- Diagram lesson: Keep architecture diagrams layered with a visible legend and
  keep sequence diagrams on the best-practices style: numbered labels above
  call lines, matching arrowhead/line colors, and transparent grouped regions.
- Verification evidence:
  - `./gradlew :spring-modulith-module-boundaries:test --console=plain --max-workers=1 --rerun-tasks` passed.
  - `./gradlew projects --console=plain` reported 93 active modules and
    `:spring-modulith-module-boundaries`.
  - `node scripts/validate-readme-diagram-qa.mjs ...module-boundaries...` passed
    with 2 targets, 0 weak reference rows, architecture connector/card checks,
    and sequence style checks.
  - README language/parity validators, `smoke-validate.sh stale-check`,
    `actionlint .github/workflows/Examples.yml`, and `git diff --check` passed.
- Future agents: For boundary-verification examples, add both the positive graph
  and a self-contained invalid test fixture. A positive-only
  `ApplicationModules.verify()` test is too weak because it cannot prove that
  the rule catches the intended leak.
