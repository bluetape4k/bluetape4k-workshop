# Issue 287 Graph IO Pipeline Lessons

## Context

Issue #287 added a graph-io import/export workshop module that teaches CSV import, Jackson 3 NDJSON export/import, and GraphML export/import on TinkerGraph without containers.

## Decisions

- Keep the module consumer-scoped: use versionless aliases governed by `bluetape4k-dependencies`; do not import a graph-specific BOM.
- Import CSV into a scratch `TinkerGraphOperations` first. Copy into the target graph only after `GraphIoStatus.COMPLETED`, so failed imports do not leave partial learner-visible state.
- Keep graph-io examples small and deterministic: 3 vertices, 2 edges, no Testcontainers, and all generated round-trip files under `@TempDir`.
- Use explicit legacy diagram allowlists. New or changed diagram SVGs must satisfy the current validator structure instead of relying on git cleanliness.

## Outcome

The new `graph/io-pipeline` module has README/README.ko, PNG/SVG diagrams, CSV fixtures, fail-closed GraphML tests, smoke wiring, and Examples workflow coverage.

## Verification

- `./gradlew :graph-io-pipeline:test --rerun-tasks --console=plain --no-daemon`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh stale-check`
- README parity/language validators
- architecture and sequence diagram validators
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## Future Guidance

- If a graph-io example teaches reports, every README snippet should check `GraphIoStatus.COMPLETED` and empty `failures` before using exported files.
- If graph-io imports write to a live target graph, test failure paths for target graph mutation, not only returned report counts.
- When preserving old diagram assets, list exact legacy slugs and keep all new assets on the strict validator path.
