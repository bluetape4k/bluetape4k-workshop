# Dependencies 1.1.4 Sync

## Context

`bluetape4k-dependencies` 1.2.0 release preparation found workshop catalog and
Dependabot ignore drift during the central BOM `develop` CI gate.

## Decision

Keep this consumer repository on the latest published
`bluetape4k-dependencies:1.1.4` baseline and align centrally governed shared
versions and Dependabot ignores from the dependencies source of truth.

## Outcome

The workshop catalog no longer blocks the central dependencies release-train
preflight. `1.2.0` should be adopted only after it is published.

## Verification

Validated from `bluetape4k-dependencies` with `sync-shared-versions.py` and
`sync-dependabot-ignores.py` using `--workspace /Users/debop/work/bluetape4k
--write --check --summary`.
