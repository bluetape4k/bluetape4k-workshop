# Issue #91 — Workshop Validation Matrix

## Context

Epic #76 restructuring adds, removes, and converts modules. A validation
framework was needed to keep the workshop buildable and testable after each
change wave, and to give developers targeted smoke commands instead of running
the full suite.

## Decision

Three-tier validation model:

| Tier | Trigger | Scope |
|------|---------|-------|
| T1 Compile | Every push/PR (CI) | All 56 modules, compile only |
| T2 Smoke | Daily nightly (Mon–Sat) | 23 no-Testcontainers modules |
| T3 Full | Weekly nightly (Sunday) | All 56 modules + Testcontainers |

Deliverables:
- `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md` — full matrix with module lists and per-domain commands
- `scripts/smoke-validate.sh` — domain-group runner (`all-smoke`, `data-access`, `spring-boot`, `serialization`, `messaging`, `async`, `observability`, `redis`, `stale-check`)
- `.github/workflows/nightly.yml` — added `smoke-test` job (T2) that runs daily on no-Testcontainers modules

## Outcome

- 56 active modules confirmed (no stale Gradle includes)
- 23 smoke-safe modules identified (no Testcontainers)
- 0 stale README references to archived modules
- 0 broken README image links (fixed regex: title strings in Markdown `![](path "title")` no longer cause false positives)
- actionlint: nightly.yml OK

## Verification

```bash
./gradlew projects --no-daemon -q | grep -c "^+---"  # → 56
bash scripts/smoke-validate.sh stale-check            # → 0 stale, 0 broken
actionlint .github/workflows/nightly.yml              # → OK
```

## Future Guidance

- After each Epic #76 wave (delete/add/convert), re-run `stale-check` before PR creation.
- When adding a new Testcontainers module, add it to the T3 Full group in the
  validation matrix spec.
- When adding a new no-Testcontainers module, add it to both the T2 Smoke
  list in the spec and to `scripts/smoke-validate.sh all-smoke` + `nightly.yml smoke-test`.
- The broken-link regex must use `[^ ")\t]+` (not `[^)]+`) to avoid capturing
  optional Markdown image title strings as part of the file path.
