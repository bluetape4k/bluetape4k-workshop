# Event Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `graph/event-lineage`, a deterministic TinkerGraph workshop example for business event lineage and audit trail reconstruction.

**Architecture:** Add a focused graph module that models events, aggregates, actors, and decisions with explicit causal and approval edges. The service uses `GraphOperations`, bounded traversal, bluetape4k validation helpers, serializable result models, bilingual README files, and README diagrams.

**Tech Stack:** Kotlin/JVM, bluetape4k graph core, bluetape4k graph TinkerPop, TinkerGraph, JUnit 5, bluetape4k-assertions, CairoSVG, repo-local diagram QA, GitHub Actions Examples workflow.

---

## File Map

- Create `graph/event-lineage/build.gradle.kts`: new no-container graph module.
- Create `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/schema/EventLineageSchema.kt`: vertex and edge label definitions.
- Create `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/model/AuditTrail.kt`: serializable result models.
- Create `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/service/EventLineageService.kt`: graph lifecycle, vertex/edge creation, lineage queries.
- Create `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/seed/EventLineageSeed.kt`: deterministic test scenario.
- Create `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/AbstractEventLineageTest.kt`: shared behavior tests.
- Create `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/EventLineageTinkerGraphTest.kt`: TinkerGraph test binding.
- Create `graph/event-lineage/src/test/resources/junit-platform.properties` and `logback-test.xml`: test resource defaults.
- Create `graph/event-lineage/README.md` and `README.ko.md`: learner-facing bilingual docs.
- Create `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg/png` and `graph-event-lineage-readme-sequence-01.svg/png`: README diagrams.
- Modify `README.md`, `README.ko.md`, `AGENTS.md`, `scripts/smoke-validate.sh`, and `.github/workflows/Examples.yml`: module registration and CI coverage.
- Create `docs/review/2026-07-02-issue-330-event-lineage-code-review.md` and `docs/lessons/2026-07-02-issue-330-event-lineage.md`: review and lesson evidence.

## Task 1: Add Failing Behavior Tests

**Complexity:** medium  
**Skills:** `bluetape4k-code-patterns`, `test-driven-development`, `ecc-kotlin-testing`

**Files:**

- Create: `graph/event-lineage/build.gradle.kts`
- Create: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/AbstractEventLineageTest.kt`
- Create: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/EventLineageTinkerGraphTest.kt`
- Create: `graph/event-lineage/src/test/kotlin/io/bluetape4k/workshop/graph/eventlineage/seed/EventLineageSeed.kt`
- Create: `graph/event-lineage/src/test/resources/junit-platform.properties`
- Create: `graph/event-lineage/src/test/resources/logback-test.xml`

- [ ] **Step 1: Add module build file with test dependencies**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    implementation(libs.bluetape4k.logging)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
}
```

- [ ] **Step 2: Write tests against the intended public service API**

Test names must include graph construction, causal path, audit trail,
superseded chain, missing link, unknown ID, and blank validation cases. Use
`io.bluetape4k.assertions` only.

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
./gradlew :graph-event-lineage:test --tests '*EventLineageTinkerGraphTest' --console=plain
```

Expected: compilation fails because `EventLineageService`, schema, and model
types do not exist yet. This is the required TDD red proof.

## Task 2: Implement Event Lineage Domain Model And Service

**Complexity:** high  
**Skills:** `bluetape4k-code-patterns`, `ecc-kotlin-patterns`

**Files:**

- Create: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/schema/EventLineageSchema.kt`
- Create: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/model/AuditTrail.kt`
- Create: `graph/event-lineage/src/main/kotlin/io/bluetape4k/workshop/graph/eventlineage/service/EventLineageService.kt`

- [ ] **Step 1: Define schema labels**

Create `EventLabel`, `AggregateLabel`, `ActorLabel`, `DecisionLabel`,
`EmitsLabel`, `CausedByLabel`, `ApprovedByLabel`, `DecidedByLabel`, and
`SupersedesLabel`. Store timestamps and versions as strings for backend-neutral
graph properties, matching existing workshop graph examples.

- [ ] **Step 2: Define serializable result models**

Create `LineageNode`, `LineagePath`, `ApprovalEvidence`, and
`AggregateAuditTrail`. Every data class implements `Serializable` and defines
`serialVersionUID`.

- [ ] **Step 3: Implement service mutators and queries**

Use `GraphOperations` and existing patterns from `RecommendationService`:
`initialize`, idempotent `addEvent`/`addAggregate`/`addActor`/`addDecision`,
edge mutators, `eventsForAggregate`, `causalPath`, `auditTrailForAggregate`,
`supersededChain`, and `missingCausalLinks`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
./gradlew :graph-event-lineage:test --tests '*EventLineageTinkerGraphTest' --console=plain
```

Expected: tests compile and pass.

## Task 3: Add Learner Documentation And Diagrams

**Complexity:** high  
**Skills:** `bluetape4k-blog`, `bluetape4k-diagram`

**Files:**

- Create: `graph/event-lineage/README.md`
- Create: `graph/event-lineage/README.ko.md`
- Create: `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg`
- Create: `docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.png`
- Create: `docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.svg`
- Create: `docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.png`

- [ ] **Step 1: Write README pair**

README files include language switch, overview, when graph lineage is useful,
when ordinary audit tables or JaVers are better, domain model table, core query
table, Kotlin usage snippet, verification commands, and See Also links.

- [ ] **Step 2: Draw architecture diagram**

Use a current best-practices architecture reference, layered static ownership
view, clear layer labels, consistent card text alignment, rounded orthogonal
connectors, and legend if line styles differ.

- [ ] **Step 3: Draw sequence diagram**

Use current best-practices sequence references, numbered labels, muted palette,
transparent `alt` frame bodies, branch-specific colors, and marker arrowheads
matching call-line colors.

- [ ] **Step 4: Render and inspect PNG assets**

Run:

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg -o docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.png -s 2
~/.local/bin/cairosvg docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.svg -o docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.png -s 2
```

Expected: SVG parse succeeds, PNGs render, and full-size visual inspection finds
no connector, label, marker, card alignment, or sequence-style defects.

## Task 4: Register Module In Repository Surfaces

**Complexity:** medium  
**Skills:** `bluetape4k-code-patterns`

**Files:**

- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `AGENTS.md`
- Modify: `scripts/smoke-validate.sh`
- Modify: `.github/workflows/Examples.yml`

- [ ] **Step 1: Add root README graph catalog rows**

Add `graph-event-lineage` as an Advanced graph module with in-memory infra and
event-lineage/audit-trail learning outcome.

- [ ] **Step 2: Add smoke validation coverage**

Add `:graph-event-lineage:test` to `all-smoke`; update stale-check expected
project count from `99` to `100`.

- [ ] **Step 3: Add Examples workflow path and smoke job coverage**

Add `graph/event-lineage/**` to push/PR paths, include
`:graph-event-lineage:test` in `smoke-examples`, and upload its test-result
artifact paths.

- [ ] **Step 4: Validate workflow YAML**

Run:

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

Expected: actionlint passes and escaped GitHub-expression quotes are absent.

## Task 5: Verification, Review, Lessons, And PR

**Complexity:** high  
**Skills:** `verification-before-completion`, `bluetape4k-diagram`, `bluetape4k-code-patterns`

**Files:**

- Create: `docs/review/2026-07-02-issue-330-event-lineage-code-review.md`
- Create: `docs/lessons/2026-07-02-issue-330-event-lineage.md`

- [ ] **Step 1: Run local verification**

Run:

```bash
./gradlew :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain
./gradlew :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain
./gradlew projects --console=plain
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
git diff --check
```

- [ ] **Step 2: Run Step 6-R review**

Review performance, stability, security, operator/Ops, developer/API, and
user/caller lenses. Record `P0=0`, `P1=0`, and any P2/P3 decisions in
`docs/review/2026-07-02-issue-330-event-lineage-code-review.md`.

- [ ] **Step 3: Commit lessons before PR**

Create `docs/lessons/2026-07-02-issue-330-event-lineage.md` with context,
decision, outcome, verification, diagram QA evidence, and future guard.

- [ ] **Step 4: Commit, push, create PR, and verify PR metadata**

Commit with Lore trailers, push the feature branch, create an English PR that
closes #330, set milestone `1.3.1`, assignee `debop`, mirror issue labels, and
verify live PR body final section is `## DoD Status`.

- [ ] **Step 5: Run post-PR review and CI gate**

Run PR diff review, wait for CI, update PR DoD, and report Step 9 evidence to
the user. Merge only after the user requests merge.
