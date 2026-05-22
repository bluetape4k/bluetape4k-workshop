# Issue #78 — Archive Low-Value Modules

## Context

Epic #76 issue #77 audit scored all active workshop modules by Bluetape4k value.
Five modules scored LOW (bt-ref ≤ 2 or only infrastructure-level BT usage with
no domain learning outcome). These were removed to keep the workshop focused on
first-party Bluetape4k examples.

## Decision

Delete module directories and update `settings.gradle.kts`:

| Module | Reason |
|--------|--------|
| `spring-boot/async-logging` | bt-ref=2; only logging infra; no domain BT value |
| `kotlin/workshop` | bt-ref=3; 4 test files; no clear learning outcome |
| `reactive/mutiny` | bt-ref=2; Quarkus-adjacent; `quarkus/` domain already disabled |
| `gatling/gradle-plugin-demo` | bt-ref=0; zero source; Gradle config demo only |
| `mapping/mapstruct` | bt-ref=1; MapStruct is not a Bluetape4k feature |

`reactive/` and `mapping/` became empty after removal, so their
`includeModules(...)` lines were commented out with an `#78` reference comment.

## Outcome

- 5 module directories deleted
- `settings.gradle.kts`: 2 domain lines commented out (`reactive`, `mapping`)
- Module count: 57 → 52 active modules
- `./gradlew build -x test` passes cleanly (44 s)

## Verification

```bash
./gradlew build -x test --no-daemon
# BUILD SUCCESSFUL in 44s
```

## Future Guidance

- Use the scoring criteria in `docs/superpowers/specs/2026-05-22-issue-77-module-audit-criteria.md`
  before adding new modules to avoid accumulating low-value examples again.
- If a domain directory becomes empty after removal, comment out its
  `includeModules(...)` line immediately to prevent Gradle configuration errors.
- `includeModules` silently skips empty directories but the comment serves as
  an explicit audit trail.
