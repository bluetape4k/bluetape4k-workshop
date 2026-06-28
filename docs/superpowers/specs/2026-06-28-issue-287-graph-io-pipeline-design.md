# Issue #287 - Graph IO Pipeline Workshop Design

**Date**: 2026-06-28
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/287
**Milestone**: 1.2.0
**Status**: Ready for implementation planning

---

## 1. Goal

Add a `graph/io-pipeline` workshop module that teaches the `bluetape4k-graph`
`graph-io` import/export surface with deterministic, container-free tests.

The module should show a learner how to:

- import a small graph from CSV vertex/edge fixtures into TinkerGraph;
- export the imported graph as Jackson3 NDJSON and GraphML;
- import the exported files into fresh TinkerGraph instances;
- assert round-trip invariants for vertex count, edge count, labels, and key properties;
- choose between CSV, GraphML, Jackson3 NDJSON, and Okio-backed adapters.

## 2. Source Evidence

| Source | Evidence |
|--------|----------|
| GitHub issue #287 | Requires a new graph-io workshop example, deterministic fixtures, no external containers for smoke path, README/README.ko updates, and no individual graph BOM imports. |
| `settings.gradle.kts` | `includeModules("graph", false, true)` automatically registers `graph/*` modules as Gradle projects. `graph/io-pipeline` maps to `:graph-io-pipeline`. |
| Existing graph examples | `graph/social-network`, `graph/knowledge-graph`, `graph/recommendation`, and `graph/abuser-detection` provide README style, graph package shape, and TinkerGraph-first smoke patterns. |
| `bluetape4k-graph` graph-io spec | CSV, Jackson3 NDJSON, and GraphML are independent format modules over `GraphOperations`; exact JVM property type fidelity is not guaranteed across formats. |
| `bluetape4k-graph` tests | `CsvRoundTripTest`, `GraphMlRoundTripTest`, `Jackson3RoundTripTest`, and cross-format tests prove the API shape and report-based assertions. |
| Workshop repo rules | New examples need README locale parity, generated PNG/SVG diagrams, validation matrix updates, and CI/smoke coverage when the module is smoke-safe. |

## 3. Non-Goals

- Do not replace or rewrite existing graph workshop modules.
- Do not add Neo4j, Memgraph, PostgreSQL, or any Testcontainers-backed path.
- Do not import an individual `bluetape4k-graph` BOM.
- Do not benchmark large graph IO throughput in this workshop module.
- Do not add stress/load tests, kotlinx-benchmark/JMH modules, compression-heavy scenarios, or repeated round-trip loops.
- Do not add suspend, virtual-thread, cancellation, or deadline examples in this first module.
- Do not guarantee exact JVM property value classes across CSV, NDJSON, and GraphML.

## 4. Options

### Option A - Documentation-only graph-io guide

Add README prose that points to `bluetape4k-graph` graph-io modules.

**Rejected**: #287 explicitly asks for a runnable example with deterministic fixtures and round-trip tests.

### Option B - Smoke-safe TinkerGraph pipeline module

Create `graph/io-pipeline` with a small CSV fixture, import/export services,
round-trip tests, README/README.ko, and README diagrams. Use only TinkerGraph and
format adapters that run without containers.

**Adopted**: This directly satisfies the issue and fits the workshop's smoke-test model.

### Option C - Okio/compression-heavy pipeline

Build the module around Okio virtual filesystems, compressed sinks, and atomic writes.

**Deferred**: Okio belongs in the learner-facing adapter selection table, but the first example should stay focused on graph-io format contracts. Compression and fake filesystem scenarios can become a later issue if needed. If README discusses Okio only conceptually, do not add an Okio dependency.

## 5. Proposed Module

```
graph/io-pipeline/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipeline.kt
  src/test/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipelineTest.kt
  src/test/resources/graph-io-pipeline/vertices.csv
  src/test/resources/graph-io-pipeline/edges.csv

docs/images/readme-diagrams/
  graph-io-pipeline-readme-architecture-01.svg
  graph-io-pipeline-readme-architecture-01.png
  graph-io-pipeline-readme-sequence-01.svg
  graph-io-pipeline-readme-sequence-01.png
```

README image links must use `../../docs/images/readme-diagrams/...`.

### Runtime dependencies

Use the workshop dependency BOM only through the existing repository convention.
Add local catalog aliases without versions when missing:

- `bluetape4k-graph-io-core`
- `bluetape4k-graph-io-csv`
- `bluetape4k-graph-io-graphml`
- `bluetape4k-graph-io-jackson3`
- `bluetape4k-graph-okio` only if source code imports Okio adapter APIs

The example module should depend on:

- `bluetape4k-graph-core`
- `bluetape4k-graph-tinkerpop`
- the selected `graph-io` format modules
- `bluetape4k-logging`
- `testImplementation(project(":shared"))`
- `testImplementation(libs.bluetape4k.junit5)`
- `testImplementation(libs.bluetape4k.assertions)`

### API contract

`GraphIoPipeline` should be a small public class with English KDoc because it is
part of the learner-facing example. It owns no files and does not create temp
directories; callers pass paths and the tests own temp-file lifecycle.

Minimum API:

- `importCsv(vertices: Path, edges: Path): GraphImportReport`
- `exportJackson3NdJson(target: Path): GraphExportReport`
- `importJackson3NdJson(source: Path): GraphImportReport`
- `exportGraphMl(target: Path): GraphExportReport`
- `importGraphMl(source: Path): GraphImportReport`

Path arguments must be validated as existing input files for import or normalized
target files for export. The methods return graph-io reports; callers must check
`GraphIoStatus.COMPLETED` and empty `failures`.

### Data set

Use deterministic CSV fixtures with stable external IDs:

- vertices: two people and one project/data set node;
- edges: contribution/review relationships from people to the project;
- properties: short text values only, avoiding numeric type ambiguity in CSV and GraphML;
- fixture size: 3 vertices, 2 edges, CSV files under 2 KB, generated NDJSON/GraphML test outputs under 10 KB;
- no full payload snapshot comparisons.

CSV headers and example shape:

```csv
id,label,prop.code,prop.name,prop.kind
person-alice,Person,alice,Alice,learner
```

```csv
id,label,from,to,prop.code,prop.role
edge-alice-project,CONTRIBUTES_TO,person-alice,project-graphio,alice-project,author
```

Import options:

- `onDuplicateVertexId = FAIL`
- `onMissingEdgeEndpoint = FAIL`
- `preserveExternalIdProperty = "_graphIoExternalId"`

Tests must assert logical text keys such as `code`, not backend-generated IDs.
They should also assert `_graphIoExternalId` is preserved intentionally and no
unexpected fixture property keys are exported.

Fixture safety rules:

- IDs, labels, property keys, and relationship codes use ASCII allow-list values.
- Values are synthetic, non-sensitive text.
- CSV cells must not begin with `=`, `+`, `-`, or `@`.
- Do not include path-looking values, control characters, token/key names, or oversized strings.

## 6. README And Diagrams

Both `README.md` and `README.ko.md` must include:

- language switch directly below the title;
- architecture diagram PNG with matching SVG source;
- sequence/pipeline diagram PNG with matching SVG source;
- adapter decision table for CSV, GraphML, Jackson3 NDJSON, and Okio-backed streams;
- concrete class names and `GraphIoFormat` names where relevant;
- CSV paired-file versus single-stream format differences;
- no filename-extension auto-detection;
- Okio as reference-only unless the module imports `bluetape4k-graph-okio`;
- CSV+Okio high-level compression/encryption helper limitations;
- stream ownership and atomic-write cautions;
- supported versus unsupported capabilities, including GraphML `port` and unsupported element handling;
- report semantics for `COMPLETED`, `PARTIAL`, and `FAILED`;
- minimal Kotlin snippets for CSV import, Jackson3 NDJSON export/import, GraphML export/import, and `GraphIoStatus.COMPLETED` checks;
- a short migration note: existing graph examples remain domain traversal examples; use graph-io pipeline only when repeatable fixture import/export is needed;
- focused test command: `./gradlew :graph-io-pipeline:test`;
- dependency note stating that bluetape4k versions are governed by `bluetape4k-dependencies`.

Diagram labels stay English so the same assets can be shared by both README files.
Generate PNGs from committed SVGs with CairoSVG and inspect the rendered PNGs.
README text should state that GraphML import is for trusted local/exported
workshop files and is not an arbitrary upload sanitizer.

## 7. CI And Validation Matrix

Because the module uses only TinkerGraph and local files:

- add `:graph-io-pipeline:test` to `scripts/smoke-validate.sh all-smoke`;
- update the stale-check expected Gradle project count from `79` to `80`;
- add `graph/io-pipeline/**` to `.github/workflows/Examples.yml` push and pull request path filters;
- add `:graph-io-pipeline:test` to the `Examples.yml` `Run H2/default examples` Gradle command;
- keep the existing `smoke-examples` timeout unchanged;
- include `graph/io-pipeline/build/test-results/test/*.xml` and `graph/io-pipeline/build/reports/tests/test/` in the smoke examples artifact upload;
- update `README.md` and `README.ko.md` root module/domain catalog entries if they enumerate graph examples or project structure.

Nightly already calls `scripts/smoke-validate.sh all-smoke`, so the script update is the primary Nightly integration point.

## 8. Acceptance Criteria

- [ ] `graph/io-pipeline` is registered as `:graph-io-pipeline` through existing auto module conventions.
- [ ] The module imports CSV fixtures into TinkerGraph.
- [ ] Tests export to Jackson3 NDJSON and GraphML, then import into fresh TinkerGraph instances.
- [ ] Tests assert vertex/edge counts, selected labels, and selected text properties.
- [ ] Tests assert CSV import failure/report contracts for at least blank vertex ID and missing edge endpoint failure cases.
- [ ] Tests write generated NDJSON/GraphML only under JUnit `@TempDir`, assert normalized paths remain inside the temp dir, and assert exported files exist and are non-empty.
- [ ] GraphML import uses fail-closed unsupported element handling and asserts `COMPLETED` with empty `failures`.
- [ ] Tests avoid container, sleep, stress, repeated-loop, and benchmark behavior; workflow timeouts are not increased.
- [ ] No external containers are required for `:graph-io-pipeline:test`.
- [ ] The repo uses only the `bluetape4k-dependencies` BOM for bluetape4k versions.
- [ ] README/README.ko explain adapters, tradeoffs, and the focused Gradle test command.
- [ ] README diagrams have SVG source, rendered PNG, and visual QA evidence.
- [ ] CI/smoke validation includes the new smoke-safe module in `Examples.yml`, `smoke-validate.sh all-smoke`, and stale-check expected count.
- [ ] Contributor validation includes `./gradlew :graph-io-pipeline:test`, `./scripts/smoke-validate.sh all-smoke`, `./scripts/smoke-validate.sh stale-check`, README validators, diagram validators, and `git diff --check`.

## 9. Contributor Runbook

Run these before the issue is complete:

```bash
./gradlew :graph-io-pipeline:test
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
git diff --check
```

If `.github/workflows/Examples.yml` changes, also run:

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

## 10. Migration / Rollback

Runtime/data migration: none. This is an example-only module with deterministic
fixtures and generated README assets.

Rollback removes:

- `graph/io-pipeline/`
- graph-io catalog aliases that are unused after rollback
- `:graph-io-pipeline:test` from smoke script and Examples workflow
- stale-check count change
- root README/README.ko graph catalog entries
- `graph-io-pipeline-readme-*` SVG/PNG assets

## 11. Risks And Mitigations

| Risk | Mitigation |
|------|------------|
| CSV/GraphML property values round-trip as text or mapper-specific numeric types. | Use text fixture properties and compare logical values, not JVM numeric classes. |
| Graph backend-generated IDs differ from fixture external IDs. | Assert labels, counts, and selected properties; use graph-io external ID mapping only for edge endpoint reconstruction. |
| GraphML has a narrower supported subset than the full spec. | Keep the fixture to directed property-graph basics and document the subset. |
| New module is smoke-safe but omitted from CI helper lists. | Update `smoke-validate.sh` and `Examples.yml`; verify `./scripts/smoke-validate.sh stale-check`. |
| Diagram assets render but become unreadable in README. | Inspect rendered PNGs and run existing diagram validators before completion. |
