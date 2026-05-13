# README Hero And WIP Refresh

## Context

The workshop repository needed a visual root README entrypoint and a current WIP
snapshot from assigned GitHub issues.

## Decision

Store the generated workshop workbench image in `docs/assets/workshop-workbench.png`
and refresh `WIP.md` from the current issue queue.

## Outcome

The README now surfaces the repository purpose before the module map, and WIP
shows the six assigned open issues.

## Verification

- Confirmed the generated asset exists as a PNG under `docs/assets`.
- Verified README references the shared image path.
