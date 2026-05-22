# Issue #77 — Module Audit and Basic/Advanced Classification

## Context

Epic #76 targets rebuilding `bluetape4k-workshop` as a curated first-party
Bluetape4k learning path. Before any module deletion, conversion, or new
example work, a baseline audit was needed to score every active module by
Bluetape4k value and assign Basic/Advanced level.

## Decision

Scored 57 active modules across four dimensions: BT-ref count in
`build.gradle.kts`, high-value BT lib specificity, `src/main` production
file count, and `src/test` coverage count.

Classification criteria:
- **Basic**: one primary BT library, single runnable command, shows removed boilerplate.
- **Advanced**: two+ BT libraries, production concern (transactions/concurrency/
  observability/failure/performance/distributed), runnable Spring entrypoint + API + tests.

## Outcome

| Verdict | Count |
|---------|------:|
| KEEP    | 40    |
| CONVERT | 6     |
| ARCHIVE | 6     |
| REWRITE (→ #97) | 5 |

Archive candidates: `spring-boot/async-logging`, `kotlin/workshop`,
`reactive/mutiny`, `gatling/gradle-plugin-demo`, `mapping/mapstruct`.
(Plus `quarkus/` already disabled in `settings.gradle.kts`.)

Rewrite candidates: all five `exposed/` modules → three production-shaped apps
tracked by #97.

## Verification

- Module list derived from `settings.gradle.kts` `includeModules()` calls.
- BT-ref counts from `rg 'bluetape4k' build.gradle.kts` per module.
- Specific BT lib list from `rg 'libs\.bluetape4k\.[a-z.]+'` per module.
- Source/test counts from `find src/main -name '*.kt'` / `find src/test -name '*.kt'`.

## Future Guidance

- Re-run this audit after each Epic #76 wave to update verdicts.
- ARCHIVE verdict requires a PR that removes the module from
  `settings.gradle.kts` and deletes or moves the directory (tracked by #78).
- CONVERT verdict is tracked individually by domain epic issues (#79–#88).
- Do not add new modules without assigning a Basic/Advanced level and a
  `Used Bluetape4k features` table in the README.
