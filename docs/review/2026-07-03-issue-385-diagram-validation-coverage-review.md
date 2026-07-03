# Issue 385 Diagram Validation Coverage Review

## Scope

- Issue: #385 `Reduce legacy README diagram validation skip coverage`.
- Work type: documentation/diagram QA governance.
- Diff scope: validator scripts only; no SVG or PNG assets were edited.
- Baseline local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 36s`.

## Coverage Change

| Validator | Before | After | Result |
|---|---:|---:|---|
| Architecture `legacySkipped` | 92 | 91 | Removed one already-valid legacy skip. |
| Architecture `validated` | implicit | 22 | Output now separates true validation coverage. |
| Sequence `legacySkipped` | 62 | 2 | Removed 60 already-valid legacy skips. |
| Sequence `validated` | implicit | 86 | Output now separates true validation coverage. |

## Remaining Documented Exceptions

Architecture exceptions remain broad and are now emitted as `exceptionSlugs` in
`scripts/validate-readme-architecture-diagrams.mjs` output. The first reduced
batch removed `aws-s3-spring-cloud-readme-architecture-01.svg` because it passes
the architecture validator without the legacy guard.

Sequence exceptions are now limited to:

- `kotlin-flow-extensions-race-fallback-readme-sequence-01.svg`
- `observability-micrometer-observation-readme-sequence-01.svg`

## Diagram QA Evidence Ledger

| Scope | Gate | Evidence | Result |
|---|---|---|---|
| Repo | Baseline local build | `BUILD SUCCESSFUL in 1m 36s`; log `/tmp/issue385-baseline-build.log` | PASS |
| Repo | Post-work local build | `BUILD SUCCESSFUL in 1m 48s`; log `/tmp/issue385-full-build-2.log` | PASS |
| Repo | JS syntax | `node --check` for both validator scripts | PASS |
| Architecture validator | Coverage output | `checked=113 validated=22 legacySkipped=91 documentedExceptions=91 failures=0` | PASS |
| Sequence validator | Coverage output | `checked=88 validated=86 legacySkipped=2 documentedExceptions=2 failures=0` | PASS |
| Diagram QA wrapper | Changed asset scope | `diagram QA wrapper: PASS targets=0` | PASS |
| Repo | Whitespace | `git diff --check` | PASS |
| SVG/PNG pairing | Asset changes | No SVG/PNG assets touched in this governance pass; not a diagram asset completion claim. | N/A |
| Full-size PNG eye check | Asset changes | No SVG/PNG assets touched or remade; not applicable for this validator-only change. | N/A |

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | No runtime or secret-handling paths changed. |
| Stability | PASS | Validators still fail on validation failures and now expose explicit coverage counts. |
| Performance | PASS | Removed skip entries only; validator work remains linear over existing diagram files. |
| Operator/Ops | PASS | Existing `legacySkipped` field is preserved for compatibility while adding `validated` and `documentedExceptions`. |
| Developer/API | PASS | Output now distinguishes real validation coverage from documented exceptions. |
| User/Reader | PASS | No README diagram assets or reader-facing content changed. |
| Evidence | PASS | Baseline build, diagram validators, diagram QA wrapper, diff check, and post-work full build passed. |

## Findings

- P0/P1: 0.
- P2: Architecture exceptions remain large and should be remediated in smaller asset batches because only one legacy architecture asset already passed the current validator.
- P3: The two remaining sequence exceptions should be handled as targeted diagram remediation issues if their current assets cannot satisfy best-practices sequence styling without redraw.
