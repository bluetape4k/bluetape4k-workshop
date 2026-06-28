# Issue #287 - Graph IO Pipeline Workshop Plan

**Date**: 2026-06-28
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/287
**Spec**: `docs/superpowers/specs/2026-06-28-issue-287-graph-io-pipeline-design.md`
**Module**: `graph/io-pipeline` -> `:graph-io-pipeline`
**Status**: Draft for Step 3-R review

---

## 1. Decisions Encoded

- `settings.gradle.kts` already auto-discovers `graph/*`; no settings edit is expected.
- The module is smoke-safe: TinkerGraph and local files only, no Testcontainers.
- The repository dependency authority is `bluetape4k-dependencies`; no individual graph BOM is added.
- Okio is documentation/reference-only for this issue unless implementation imports `bluetape4k-graph-okio`; current plan does not import it.
- CSV fixtures preserve `_graphIoExternalId` intentionally so learners can see how external IDs map to backend IDs.
- Tests compare logical `code` properties, labels, counts, report status, and report failures; they do not compare backend-generated IDs or full exported payload snapshots.
- Generated test outputs live only under JUnit `@TempDir`.

## 2. Implementation Tasks

### T1 - Catalog and Build Scaffolding

- **Files**:
  - `gradle/libs.versions.toml`
  - `graph/io-pipeline/build.gradle.kts`
  - `graph/io-pipeline/src/test/resources/junit-platform.properties`
  - `graph/io-pipeline/src/test/resources/logback-test.xml`
- **Action**:
  - Add versionless aliases for `bluetape4k-graph-io-core`, `bluetape4k-graph-io-csv`, `bluetape4k-graph-io-graphml`, and `bluetape4k-graph-io-jackson3`.
  - Do not add `bluetape4k-graph-okio`.
  - Do not add benchmark plugins, JMH, kotlinx-benchmark, stress/load dependencies, or compression-heavy dependencies.
  - Add module dependencies on graph core, TinkerPop, graph-io core/csv/graphml/jackson3, logging, `project(":shared")`, JUnit5, assertions, and MockK only if used.
  - Add JUnit/logback test resources following existing graph module conventions.
- **DoD**:
  - `./gradlew :graph-io-pipeline:dependencies --configuration testRuntimeClasspath` resolves.
  - No local graph BOM or explicit bluetape4k graph version appears in the module.
  - No benchmark/stress dependency or plugin is introduced.

### T2 - Deterministic Fixtures

- **Files**:
  - `graph/io-pipeline/src/test/resources/graph-io-pipeline/vertices.csv`
  - `graph/io-pipeline/src/test/resources/graph-io-pipeline/edges.csv`
- **Action**:
  - Add 3 vertices and 2 directed edges with ASCII IDs, labels, `prop.code`, and text properties.
  - Use these exact rows:
    - vertices: `person-alice,Person,alice,Alice,learner`; `person-bob,Person,bob,Bob,reviewer`; `project-graphio,Project,graphio,Graph IO Pipeline,workshop`
    - edges: `edge-alice-project,CONTRIBUTES_TO,person-alice,project-graphio,alice-project,author`; `edge-bob-project,REVIEWS,person-bob,project-graphio,bob-project,reviewer`
  - Define fixture vertex labels `Person`, `Project` and edge labels `CONTRIBUTES_TO`, `REVIEWS` as constants used by tests and export options.
  - Keep each fixture under 2 KB.
  - Avoid spreadsheet formula prefixes, path-looking values, secrets-looking names, and control characters.
- **DoD**:
  - Fixture headers match graph-io CSV paired-file contract.
  - Fixtures are deterministic and contain no sensitive-looking data.

### T3 - Failing Tests First

- **Files**:
  - `graph/io-pipeline/src/test/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipelineTest.kt`
- **Action**:
  - Add tests before production implementation:
    - CSV import returns `COMPLETED`, empty failures, 3 vertices, 2 edges.
    - Jackson3 NDJSON export/import round-trip returns `COMPLETED`, empty failures, counts preserved, output under 10 KB.
    - GraphML export/import round-trip returns `COMPLETED`, empty failures, counts preserved, output under 10 KB.
    - Blank vertex ID CSV returns `FAILED`.
    - Missing edge endpoint CSV returns `FAILED`.
    - Unsupported GraphML such as `port`, `hyperedge`, nested graph, or undirected edge with fail-closed options returns `FAILED`.
    - Export paths normalize inside `@TempDir` and exported files exist/non-empty.
  - Add a shared graph-state assertion after every successful import and round-trip import:
    - exact vertex labels and edge labels;
    - exact `code` values;
    - `_graphIoExternalId` exact fixture values only after the first CSV import;
    - 3 vertices and 2 edges;
    - no unexpected fixture property keys.
  - For Jackson3 and GraphML round-trip imports, assert labels, counts, `code`, and edge topology; document that `_graphIoExternalId` may be backend-derived after export/import.
  - Assert export reports include the expected format, `verticesWritten = 3`, `edgesWritten = 2`, and no skipped vertices or edges.
  - Assert exported NDJSON/GraphML include only expected labels and fixture property keys, including the intentional `_graphIoExternalId`.
  - For each failure test, use a fresh `TinkerGraphOperations` and assert `status`, `failures` size, severity, phase, file role, message, created/skipped counts, and the exact post-failure graph state:
    - blank vertex ID: `0V/0E`;
    - missing edge endpoint with `FAIL`: imported valid vertices may remain, but edges must be `0E`;
    - unsupported GraphML with fail-closed options: no successful-import assumptions and no unexpected graph mutation.
  - Add a test helper that resolves `tempDir.resolve(relative).normalize()`, rejects paths outside normalized `tempDir`, and verifies temp-dir contents are only the expected `.ndjson` and `.graphml` outputs.
  - Add fixture safety assertions that no cell starts with `=`, `+`, `-`, or `@`, contains path separators/control characters, or uses token/key/password-like names.
  - Use bluetape4k assertions and JUnit `@TempDir`.
  - Avoid sleeps, `@RepeatedTest`, stress/load loops, and repeated round-trip loops; run exactly one round-trip per format over the 3-vertex/2-edge fixture.
- **DoD**:
  - Initial `./gradlew :graph-io-pipeline:test` fails because `GraphIoPipeline` is not implemented.

### T4 - GraphIoPipeline Implementation

- **Files**:
  - `graph/io-pipeline/src/main/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipeline.kt`
- **Action**:
  - Implement a public `GraphIoPipeline` class with English KDoc.
  - Add English KDoc to every public method, covering path role, returned report semantics, caller `GraphIoStatus.COMPLETED`/`failures` checks, and GraphML unsupported-element policy where relevant.
  - Constructor accepts `GraphOperations`.
  - Methods:
    - `importCsv(vertices: Path, edges: Path): GraphImportReport`
    - `exportJackson3NdJson(target: Path): GraphExportReport`
    - `importJackson3NdJson(source: Path): GraphImportReport`
    - `exportGraphMl(target: Path): GraphExportReport`
    - `importGraphMl(source: Path): GraphImportReport`
  - Use `GraphImportOptions(onDuplicateVertexId = FAIL, onMissingEdgeEndpoint = FAIL, preserveExternalIdProperty = "_graphIoExternalId")`.
  - Use fail-closed `GraphMlImportOptions(unsupportedElementPolicy = UnsupportedGraphMlElementPolicy.FAIL)`.
  - Validate source paths exist and export targets are normalized non-directory paths.
  - Do not claim production traversal protection because this API accepts only `Path`, not a trusted output root; test helpers and README examples own temp-root containment.
  - Use `GraphExportOptions(vertexLabels = setOf("Person", "Project"), edgeLabels = setOf("CONTRIBUTES_TO", "REVIEWS"))` for both Jackson3 and GraphML exports.
- **DoD**:
  - `./gradlew :graph-io-pipeline:test` passes.
  - No `!!`, no `runBlocking`, no deprecated imports.

### T5 - README, Korean README, and Root Catalog

- **Files**:
  - `graph/io-pipeline/README.md`
  - `graph/io-pipeline/README.ko.md`
  - `README.md`
  - `README.ko.md`
- **Action**:
  - Add language switches.
  - Explain architecture and CSV -> TinkerGraph -> Jackson3/GraphML flow.
  - Add the same section set in `README.md` and `README.ko.md`.
  - Add an adapter decision table with rows for CSV, Jackson3 NDJSON, GraphML, and Okio-backed streams.
  - Adapter table columns: input shape, best use case, relevant class/`GraphIoFormat` name, strengths, limitations/unsupported capabilities, dependency status, and when not to use.
  - Document concrete class/format names, CSV paired-file versus single-stream differences, no filename-extension auto-detection, CSV+Okio high-level compression/encryption helper limitations, stream ownership, atomic-write cautions, GraphML `port`/unsupported element handling, `COMPLETED`/`PARTIAL`/`FAILED` report semantics, trusted-local GraphML warning, focused test command, and BOM note.
  - Warn that GraphML and NDJSON imports are local workshop examples, not upload endpoints or sanitizers; callers must validate and sandbox untrusted files before graph-io import.
  - Document that exported NDJSON/GraphML includes graph properties and the intentional `_graphIoExternalId`.
  - Document that report failures are diagnostic data and should not be returned verbatim from public endpoints.
  - Add compile-aligned Kotlin snippets in both README files for:
    - CSV import with `GraphIoPipeline`, `TinkerGraphOperations`, `Path` inputs, `status == GraphIoStatus.COMPLETED`, and `failures.isEmpty()`;
    - Jackson3 NDJSON export/import with the same report checks;
    - GraphML export/import with the same report checks.
  - Add a “Migration from manual TinkerGraph seeds” section explaining that existing graph examples remain domain traversal examples, while graph-io pipeline is for repeatable fixture import/export.
  - Add root graph domain/module catalog entries if missing.
- **DoD**:
  - English and Korean README are source-equivalent, not abbreviated.
  - Manual parity check confirms matching section headings, adapter table rows, warning callouts, commands, image links, and Kotlin snippet count/content across `README.md` and `README.ko.md`.
  - `node scripts/validate-readme-parity.mjs` and `node scripts/validate-readme-language.mjs` pass.

### T6 - README Diagrams

- **Files**:
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.png`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.png`
- **Action**:
  - Create English-label SVG assets using `Architects Daughter` for headings and `Comic Mono` for detail text.
  - Render PNGs with CairoSVG.
  - Inspect rendered PNGs before continuing.
- **DoD**:
  - `node scripts/validate-readme-architecture-diagrams.mjs` passes.
  - `node scripts/validate-sequence-diagrams.mjs` passes.
  - Full-size PNG visual inspection shows no overlap or unreadable labels.

### T7 - Smoke, Examples, and Registration Validation

- **Files**:
  - `scripts/smoke-validate.sh`
  - `.github/workflows/Examples.yml`
  - `.github/workflows/nightly.yml` read-only evidence
- **Action**:
  - Add `:graph-io-pipeline:test` to `all-smoke`.
  - Update stale-check expected project count from the current observed baseline `79` to `80`.
  - Add `graph/io-pipeline/**` to Examples push and pull request paths.
  - Add `:graph-io-pipeline:test` to the existing single H2/default Examples smoke Gradle command; do not add a new job or container lane.
  - Keep `.github/workflows/Examples.yml` `smoke-examples.timeout-minutes: 25` unchanged.
  - Add graph/io-pipeline test result artifact paths under the existing `smoke-example-test-results` upload block.
- **DoD**:
  - `./scripts/smoke-validate.sh all-smoke` passes.
  - `./gradlew projects --console=plain | rg "Project ':graph-io-pipeline'"` proves auto registration.
  - `./scripts/smoke-validate.sh stale-check` output includes `Active modules: 80 (expected: 80)`, `No stale refs found.`, and `No broken image links found.`; any `WARNING:` is a failure for this issue.
  - `actionlint .github/workflows/Examples.yml` passes.
  - `test -z "$(rg -n "\\\\'" .github/workflows || true)"` passes; any match is the failure condition.
  - Workflow diff shows no timeout increase.
  - `rg -n "smoke-validate.sh all-smoke" .github/workflows/nightly.yml` proves Nightly reaches the new module through `all-smoke`.

### T8 - Final Verification and Review Artifacts

- **Files**:
  - `docs/review/2026-06-28-issue-287-graph-io-pipeline-code-review.md`
  - `docs/lessons/2026-06-28-issue-287-graph-io-pipeline.md`
- **Action**:
  - Run targeted module tests, README validators, diagram validators, workflow lint, and `git diff --check`.
  - Reuse the T7 `all-smoke` evidence unless source, build, fixture, smoke script, or workflow files change after that run.
  - Record code review and lessons with evidence.
  - Record rollback/runbook evidence in the review or lessons artifact:
    - no runtime/data migration;
    - rollback removes `graph/io-pipeline/`;
    - rollback removes unused graph-io catalog aliases;
    - rollback removes smoke script and Examples workflow entries;
    - rollback restores stale-check count;
    - rollback removes root README entries and diagram assets.
  - Record contributor diagnostics locations:
    - `graph/io-pipeline/build/reports/tests/test/index.html`;
    - `graph/io-pipeline/build/test-results/test/*.xml`;
    - GitHub `smoke-example-test-results` artifact paths.
- **PR readiness**:
  - `gh issue view 287 --json assignees,labels,milestone` confirms live issue metadata.
  - PR body includes `Closes #287`.
  - `gh pr view <pr> --json assignees,labels,milestone,body` proves assignee, label, milestone, and body parity before reporting completion.
- **DoD**:
  - P0/P1 findings are zero after final review.
  - Commit uses Lore protocol.
  - PR mirrors issue assignee, milestone, and labels.

## 3. Verification Command Set

```bash
./gradlew :graph-io-pipeline:test
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
test -z "$(rg -n "\\\\'" .github/workflows || true)"
git diff --check
```

## 4. Risks

- `all-smoke` may expose unrelated existing failures; if so, rerun the focused module test and record the unrelated failure with evidence.
- GraphML unsupported element handling must be fail-closed for the demo path, while README explains broader default behavior.
- Root README may not currently expose graph modules; update both locales conservatively without a broad rewrite.
- Existing diagram validators may reject new assets if they do not match node/edge class conventions; build diagrams to the local validator shape.
