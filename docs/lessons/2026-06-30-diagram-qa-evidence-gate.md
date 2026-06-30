# Diagram QA evidence gate

## Context

Issue #318 exposed a repeated diagram workflow failure: individual validators
could pass while a rendered diagram still had connector clearance, rounded-bend,
or style-parity problems. The weakest failure mode was treating generic audit
output as sufficient even when the output itself showed that a script did not
understand the diagram shape, such as `cards=0` for an architecture diagram or
`paths=0` for a rounded connector file.

## Decision

`scripts/validate-readme-diagram-qa.mjs` is now the repo-local evidence gate for
README diagram changes. It collects changed README SVG diagrams, validates XML,
rerenders PNGs, runs the existing architecture and sequence validators, runs the
`bluetape4k-diagram` reference audits, and treats weak audit rows as incomplete
unless fallback evidence is produced.

`scripts/smoke-validate.sh diagram-qa` exposes the gate through the normal smoke
runner, and the Examples workflow runs it as `Diagram QA`.

## Outcome

Diagram review reports now need concrete evidence values instead of prose such
as "checklist passed". For connector-heavy diagrams, the report should include
marker counts, geometry/endpoint/crossing results, rounded-bend fallback counts,
terminal segment lengths, rendered PNG paths, and visual inspection notes.

For the S3 Vectors Access Grants diagrams, the gate records the architecture
reference audit as weak for `cards=0` and `paths=0`, then accepts the asset only
because fallback evidence reports `connectors=7`, `bent=3`, `rounded_bent=3`,
`sharp_bent_failures=0`, and `access-grants-to-object-data:33.0`.

## Verification

- `node --check scripts/validate-readme-diagram-qa.mjs`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## Future Guidance

Do not report diagram checklist success from a single validator. Run the wrapper
and copy its evidence ledger into the PR body when diagrams change. If a row is
`WEAK`, either improve the reference audit or add a targeted fallback invariant
before claiming the diagram passes. Full-size rendered PNG inspection remains a
separate human visual gate; the wrapper proves mechanical evidence, not taste.
