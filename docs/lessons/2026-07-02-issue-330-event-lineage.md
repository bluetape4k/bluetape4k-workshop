# Issue #330 Event Lineage Workshop

## Context

Issue #330 needed an advanced graph workshop example that teaches event
lineage, aggregate audit reconstruction, approval evidence, superseding events,
and missing-cause detection without overlapping the existing graph-io import
pipeline.

## Decision

Build `graph/event-lineage` as a GraphOperations workshop module with a fast
TinkerGraph lane and a Neo4j Testcontainers integration lane:

- `EventLineageService` owns idempotent vertex creation, direct edge creation,
  bounded causal traversal, superseded chains, and aggregate audit assembly.
- `EventLineageSchema` keeps labels and property names explicit for learners.
- `EventLineageSeed` creates a deterministic order approval scenario with one
  intentional missing causal link.
- README and README.ko teach the model with architecture and sequence diagrams
  plus executable test commands.

## Outcome

The module now demonstrates how a business state can be explained from event,
decision, actor, and correction vertices. The default test lane remains
smoke-safe with TinkerGraph, and the `integrationTest` lane proves the same
service against `Neo4jServer` from `bluetape4k-testcontainers`.

## Verification

- `./gradlew --no-daemon :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain`
- `./gradlew --no-daemon :graph-event-lineage:integrationTest --no-build-cache --rerun-tasks --console=plain`
- `./gradlew --no-daemon :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain`
- `./gradlew --no-daemon projects --console=plain`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh stale-check`
- explicit `node scripts/validate-readme-diagram-qa.mjs` for the architecture and sequence SVGs
- full-size PNG eye inspection for both diagrams
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## Future Guidance

For sequence diagrams, do not rely on visual appearance alone. The direct QA
script expects numbered call labels with `num`, transparent alt bodies, matching
arrowhead colors, and activation endpoints whose terminal segment matches the
card edge. For architecture diagrams, every rendered connector path needs
`data-connector` metadata so the connector, endpoint, and rounded-corner audits
can inspect it.
