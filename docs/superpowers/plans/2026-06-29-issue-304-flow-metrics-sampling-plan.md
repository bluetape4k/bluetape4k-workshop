# Issue 304 Plan - Flow Metrics Sampling Workshop

## Scope

Implement issue #304 in branch `feat/issue-304-flow-metrics-sampling` as a new
workshop module:

`kotlin/flow-extensions-metrics-sampling`

## Step 0 - Worktree and Evidence

- Action: work from `.worktrees/feat-issue-304-flow-metrics-sampling` based on
  `origin/develop`.
- DoD: `repo-status` is clean and live `gh issue view 304` confirms OPEN,
  assignee `debop`, milestone `1.2.0`, and labels.

## Step 1 - Current-Code Research

- Action: inspect existing Flow modules, upstream `throttle`, `pairwise`,
  `zipWithNext`, `takeUntil`, and related tests.
- DoD: implementation assumptions are source-backed:
  - `throttleLeading` emits the first value per window immediately.
  - `throttleTrailing` emits the last value per window at window close.
  - `pairwise` derives adjacent pairs through `sliding(2)`.
  - `takeUntil` stops on notifier emission and does not wrap downstream cancellation.

## Step 2 - TDD RED Tests (complexity: medium, applies `$bluetape4k-code-patterns`)

Create `MetricsSamplingPipelineTest` before production code:

- leading preview emits the first sample from each throttle window.
- trailing dashboard emits the final sample from each throttle window.
- adjacent deltas preserve sample order and delta math.
- significant changes are filtered by absolute threshold and direction.
- stop signal ends collection through `takeUntil`.
- collector cancellation propagates and upstream cleanup runs.
- domain validation rejects blank/control-character names, non-finite values,
  non-positive thresholds, and public `copy` bypasses.

Verification command:

```bash
./gradlew :kotlin-flow-extensions-metrics-sampling:test --tests "io.bluetape4k.workshop.flow.metrics.sampling.MetricsSamplingPipelineTest" --console=plain
```

Expected RED: unresolved production classes/functions after test creation.

## Step 3 - Implementation (complexity: medium, applies `$bluetape4k-code-patterns`)

Add production files under
`src/main/kotlin/io/bluetape4k/workshop/flow/metrics/sampling/`:

- `MetricSamplingDomain.kt`
- `MetricsSamplingPipeline.kt`

Implementation rules:

- Validated constrained classes use private constructors plus factory methods
  instead of public data-class `copy` when validation matters.
- Serializable value classes/data classes include `serialVersionUID`.
- `MetricsSamplingPipeline` uses:
  - `samples.throttleLeading(window).log("metrics-leading-preview")`
  - `samples.throttleTrailing(window).log("metrics-dashboard")`
  - `samples.pairwise(MetricDelta::from)`
  - `deltas(...).map(...).filter { it.significant }`
  - `samples.takeUntil(stopSignal).log("metrics-lifecycle")`
- No broad `catch`, no `runCatching`, no blocking calls, no scheduler/executor.

DoD: targeted test command passes.

## Step 4 - Module Registration (complexity: low)

- Add `build.gradle.kts` with the same dependency shape as sibling Flow modules.
- Add `src/test/resources/junit-platform.properties`.
- Add `src/test/resources/logback-test.xml`.
- Confirm Gradle auto-registration through `./gradlew projects`.

DoD: `:kotlin-flow-extensions-metrics-sampling` appears in project listing.

## Step 5 - Learner Documentation and Diagrams (complexity: medium, applies `$bluetape4k-blog` and `$bluetape4k-diagram`)

Add:

- `README.md`
- `README.ko.md`
- `docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg`
- matching PNG
- `docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg`
- matching PNG
- contact sheet PNG

README requirements:

- language switch directly below title.
- scenario and learning goals.
- leading-vs-trailing comparison table.
- Before scheduler/timestamp snippet.
- After Flow extension chain snippet.
- Used Bluetape4k features table.
- test and smoke commands.

Diagram requirements:

- architecture view uses top-to-bottom layers and clear lane grouping.
- lifecycle/sequence view follows the current sequence best-practices family:
  participants, lifelines, activation bars, pill labels, and alt/stop region.
- no Redis/DB/Kafka/service icons because the cards are code/Flow responsibilities.
- SVG XML parses, PNGs render through CairoSVG, geometry/endpoint/style audits
  pass, contact sheet is inspected, and touched PNGs are opened full-size.

## Step 6 - Repo Registration (complexity: low)

- Add the new module row to `README.md`.
- Add the source-equivalent row to `README.ko.md`.
- Add Examples workflow path filters, smoke command, and artifact upload paths.
- Add the module to `scripts/smoke-validate.sh` `all-smoke` and `async`.
- Increase stale-check expected project count from `85` to `86`.

Verification:

```bash
actionlint .github/workflows/Examples.yml
./scripts/smoke-validate.sh stale-check
```

## Step 7 - Verification (complexity: medium)

Run after implementation/docs:

```bash
./gradlew :kotlin-flow-extensions-metrics-sampling:test --console=plain
./gradlew :kotlin-flow-extensions-metrics-sampling:compileKotlin :kotlin-flow-extensions-metrics-sampling:compileTestKotlin --console=plain
./scripts/smoke-validate.sh async
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
git diff --check
```

Diagram-specific verification:

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg
xmllint --noout docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-architecture-01.svg docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py docs/images/readme-diagrams/kotlin-flow-extensions-metrics-sampling-readme-sequence-01.svg
```

## Step 8 - Review, Lessons, and PR

- Create tracked review artifact:
  `docs/review/2026-06-29-issue-304-flow-metrics-sampling-code-review.md`.
- Create tracked lesson artifact:
  `docs/lessons/2026-06-29-issue-304-flow-metrics-sampling.md`.
- Commit with Lore trailers.
- Push branch.
- Create PR with:
  - `Closes #304`
  - assignee `debop`
  - milestone `1.2.0`
  - issue labels mirrored
  - final PR body section exactly `## DoD Status`
- Verify live issue/PR metadata with `gh issue view` and `gh pr view`.
- Wait for CI and stop at merge-ready report. Merge requires an explicit user
  merge request.

## Review Gate Notes

The full-feature workflow asks for six perspective reviews. If native subagent
lanes are available, run them for Step 6-R. If the session surface blocks those
lanes, record a local-equivalent six-perspective review in the tracked review
artifact and make the current session own severity normalization.
