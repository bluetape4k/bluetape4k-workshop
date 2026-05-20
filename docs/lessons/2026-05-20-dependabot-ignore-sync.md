# Dependabot ignore sync

## Context

`bluetape4k-dependencies` added more centrally managed dependencies to the
downstream Dependabot ignore block.

## Decision

Propagate the generated ignore list to this repository so Dependabot does not
open repo-local PRs for dependencies governed by the central catalog.

## Outcome

The local `.github/dependabot.yml` now ignores the new centrally managed
Bouncy Castle, ClassGraph, and Tomcat coordinates.

## Verification

- `git diff --check`

## Future note

After central dependency waves, run `sync-dependabot-ignores.py` along with
shared version sync before rerunning the central downstream CI gate.
