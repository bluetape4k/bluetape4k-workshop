# Issue 385 Diagram Validation Coverage

## Context

The README diagram validators reported `failures=0` while hiding most legacy
architecture and sequence assets behind skip allowlists. That made the output
look stronger than the real checklist coverage.

## Decision

- Keep the existing `legacySkipped` field for compatibility.
- Add `validated`, `documentedExceptions`, and `exceptionSlugs` so validator
  output separates true coverage from documented exceptions.
- Remove allowlist entries only after proving the asset passes the current
  validator without the legacy guard.
- Do not claim SVG/PNG visual QA when no diagram asset was changed.

## Outcome

- Architecture skipped count moved from `92` to `91`.
- Sequence skipped count moved from `62` to `2`.
- Remaining sequence exceptions are exact and small enough for targeted follow-up:
  `kotlin-flow-extensions-race-fallback-readme-sequence-01.svg` and
  `observability-micrometer-observation-readme-sequence-01.svg`.

## Verification

- Baseline full local build passed before edits.
- Architecture validator passed with `checked=113 validated=22 legacySkipped=91 documentedExceptions=91 failures=0`.
- Sequence validator passed with `checked=88 validated=86 legacySkipped=2 documentedExceptions=2 failures=0`.
- Diagram QA wrapper passed with `targets=0` because no SVG changed.
- Post-work full build passed with `BUILD SUCCESSFUL in 1m 48s`.

## Future Guard

When reducing legacy diagram allowlists, first classify candidates by whether
they already pass the current validator. Remove those entries immediately, then
open focused remediation issues only for the remaining exact exception slugs.
