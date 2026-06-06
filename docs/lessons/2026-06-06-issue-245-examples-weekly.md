# Issue 245 Examples Weekly Gate

## Context

`bluetape4k-workshop` had CI and Nightly workflows, but no dedicated weekly
Examples workflow for representative consumer-facing backend scenarios. Issue
#245 requested a separate gate linked to the `bluetape4k-exposed` downstream
examples epic.

## Decision

- Add a weekly `Examples` workflow with manual dispatch and path-filtered PR/push
  triggers.
- Keep the selected module list explicit in the workflow instead of reusing
  broad smoke groups.
- Split the gate into H2/default smoke examples and one sequential
  Testcontainers lane to avoid Docker contention.

## Outcome

The selected matrix covers data access, Exposed/R2DBC, Spring Boot cache,
Redis cache, Kafka messaging, Jackson serialization, and Resilience4j coroutine
examples without duplicating the full CI or Nightly suite.

## Verification

- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## Future Guidance

Add new consumer examples to this workflow only when they are representative and
stable enough for weekly validation. Keep heavy infrastructure examples in the
sequential container lane or Nightly.
