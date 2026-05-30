# Code scanning alerts

## Context

GitHub CodeQL reported workflow token permission alerts for CI and Nightly, plus
JavaScript alerts in sample static resources and checked-in Gatling reports.

## Decision

Declare explicit workflow-level `contents: read` permissions first, then fix or
remove static resources that CodeQL can prove unsafe.

## Outcome

Workflow token defaults are now least-privilege for checkout-based jobs. Static
resource fixes stay scoped to the alerted files.

## Verification

- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml`
- `yq` inspection of workflow permissions
- `git diff --check`

## Future guard

Do not commit generated performance reports with bundled legacy JavaScript
unless they are intentionally published artifacts and security review accepts
the static risk.
