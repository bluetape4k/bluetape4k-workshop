# WIP - bluetape4k-workshop

Snapshot: 2026-05-13 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 6 issues.

## Recently Completed

- CI/Nightly, Spring Boot 4.0.x alignment, Gradle 9.5.0/version-catalog migration, local OMX ignore rules, and assertion migration are merged.
- JDBC helper dependency fixes for Exposed workshop modules are merged by PR #60 and PR #61.
- Dependency governance, compatibility guards, and dependency bumps are merged through PR #33 through PR #59.

## Current Direction

Workshop issues should consume stable library APIs. Avoid building graph or leader runnable examples before the owning library repository has settled the core API or semantics.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P3 | [#14](https://github.com/bluetape4k/bluetape4k-workshop/issues/14) Ktor-first workshop example | M | Independent enough to start, but lower strategic leverage. |
| P3 | [#9](https://github.com/bluetape4k/bluetape4k-workshop/issues/9) bluetape4k-graph examples epic | L | Track runnable workshop side only; library examples belong in `bluetape4k-graph`. |
| P3 | [#11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11) knowledge-graph example | M | Depends on graph example/core API stability. |
| P3 | [#12](https://github.com/bluetape4k/bluetape4k-workshop/issues/12) fraud-detection example | M | Depends on graph example/core API stability. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-workshop/issues/13) recommendation example | M | Depends on graph example/core API stability. |
| P3 | [#10](https://github.com/bluetape4k/bluetape4k-workshop/issues/10) bluetape4k-leader examples epic | L | Wait for leader lease/state semantics; keep boundary distinct from library repo work. |

## Dependency Map

```text
bluetape4k-graph core APIs
  -> #9 graph workshop epic
      -> #11 knowledge graph
      -> #12 fraud detection
      -> #13 recommendation

bluetape4k-leader lease/state semantics
  -> #10 leader workshop epic

#14 Ktor-first workshop
  -> independent, but not a blocker
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Independent workshop | 1 | `#14` if examples are desired now. |
| Graph examples | 0 until graph core settles | `#9/#11/#12/#13` wait. |
| Leader examples | 0 until leader semantics settles | `#10` waits. |
