# Profile image moderation example

## Context

Added a new `image-processing/profile-image-moderation` workshop example for profile-image upload, pending blurred preview, asynchronous moderation, approved public image, and rejected/default fallback.

## Decision

Keep the example local and deterministic by default: use `ImageStorage` for S3-compatible object semantics, an in-memory state repository, a fake moderation provider with a configurable one-second delay, and Micrometer metrics with low-cardinality tags.

## Outcome

The example documents the scenario in bilingual README files, includes architecture and sequence diagrams, registers the module in root documentation, smoke validation, and CI path filters/artifacts.

## Verification

- RED: targeted service test failed before implementation with unresolved profile-image symbols.
- GREEN: `./gradlew :image-processing-profile-image-moderation:test --rerun-tasks --console=plain` executed 14 tests.
- Diagram QA: explicit `node scripts/validate-readme-diagram-qa.mjs ...architecture...svg ...sequence...svg` passed with `targets=2` and `weak_reference_rows=0` after full-size PNG inspection.
- Repo checks: `./gradlew projects`, `./scripts/smoke-validate.sh stale-check`, `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml`, `git diff --check`, and `./scripts/smoke-validate.sh all-smoke` passed.

## Future note

Treat README diagrams as a hard gate, not a post-review polish step. Run the explicit diagram QA wrapper and open every touched PNG at full size before claiming the workflow step is complete.
