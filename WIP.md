# WIP - bluetape4k-workshop

Snapshot: 2026-05-10 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 6 issues.

## Current Direction

Workshop issues should consume stable library APIs. Avoid building graph or
leader runnable examples before the owning library repo has settled the core API
or semantics. Graph batch insert is still merge-waiting in PR #78.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P3 | [#14](https://github.com/bluetape4k/bluetape4k-workshop/issues/14) Ktor-first workshop example | M | Independent enough to start, but lower strategic leverage. |
| P3 | [#9](https://github.com/bluetape4k/bluetape4k-workshop/issues/9) bluetape4k-graph examples epic | L | Track runnable workshop side only; graph library examples belong in `bluetape4k-graph #10`. |
| P3 | [#11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11) knowledge-graph example | M | Depends on graph example/core API stability. |
| P3 | [#12](https://github.com/bluetape4k/bluetape4k-workshop/issues/12) fraud-detection example | M | Depends on graph example/core API stability. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-workshop/issues/13) recommendation example | M | Depends on graph example/core API stability. |
| P3 | [#10](https://github.com/bluetape4k/bluetape4k-workshop/issues/10) bluetape4k-leader examples epic | L | Wait for leader lease/state semantics; keep boundary distinct from `bluetape4k-leader #36`. |

## Dependency Map

```text
bluetape4k-graph #10 and core graph APIs
  -> #9 graph workshop epic
      -> #11 knowledge graph
      -> #12 fraud detection
      -> #13 recommendation

bluetape4k-leader #36 and lease/state semantics
  -> #10 leader workshop epic

#14 Ktor-first workshop
  -> independent, but not a blocker
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Independent workshop | 1 | `#14` if examples are desired now. |
| Graph examples | 0 until graph core settles | `#9/#11/#12/#13` wait. |
| Leader examples | 0 until leader semantics settle | `#10` waits. |
