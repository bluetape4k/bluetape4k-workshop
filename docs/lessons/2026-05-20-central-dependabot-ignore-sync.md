# Central Dependabot Ignore Sync

## Context

`bluetape4k-dependencies` now owns Dependabot alert routing for BouncyCastle,
ClassGraph, and Tomcat lines. Workshop repositories should not receive direct
Dependabot version PRs for those centrally governed packages.

## Decision

Sync the generated central ignore block from `bluetape4k-dependencies` instead
of maintaining local ignore entries by hand.

## Outcome

The repository Dependabot configuration now ignores the new centrally governed
dependency names. Future version changes should start in `bluetape4k-dependencies`
and be propagated by its sync scripts.

## Verification

- `scripts/sync-dependabot-ignores.py --workspace .. --write --check --summary`
- `git diff --check`
