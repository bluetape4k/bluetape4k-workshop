# Nightly Changes Can Surface Existing Compile Drift

## Context

The bluetape4k-workshop Nightly workflow was split into smoke and full lanes.
The PR exposed a pre-existing CI compile failure in Exposed workshop modules.

## Decision or Finding

When a workflow-only PR fails compile, inspect the failing source before
assuming the workflow change caused the failure. CI may reveal dependency drift
that local incremental builds have hidden.

## Outcome

Modules importing `io.bluetape4k.exposed.dao.*` helper functions now declare the
`bluetape4k-exposed-dao` dependency explicitly.

## Verification

- Local compile succeeded for:
  - `:exposed-domain:compileKotlin`
  - `:exposed-dao-web-transaction:compileKotlin`
  - `:exposed-sql-web-virtualthread:compileKotlin`
  - `:exposed-sql-webflux-coroutines:compileKotlin`
- PR #29 CI succeeded.

## Future Guidance

- Treat workflow PR failures as real repository signals.
- Check imported helper packages against declared module dependencies.
- Keep dependency aliases catalog-managed so module builds stay explicit.
