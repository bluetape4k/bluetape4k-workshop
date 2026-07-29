# Issue 555 Strict Compliance Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline execution in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the usage-billing reference conform to the applicable Bluetape Kotlin, Spring, Exposed, testing, module, documentation, and diagram contracts.

**Architecture:** Preserve the five independently deployable service boundaries and their local JSON codecs. Replace repeated low-level validation with a small per-service decoder helper, make durable model serialization explicit, and add operational logging at Kafka state transitions. Replace generic staged-card visuals with source-backed architecture and sequence diagrams; SVG/PNG visual inspection remains authoritative.

**Tech Stack:** Kotlin 2.4, Spring Boot 4, Kafka, PostgreSQL, JetBrains Exposed, `bluetape4k-exposed-jdbc`, Bluetape assertions/logging/Testcontainers, CairoSVG.

---

## File map

- Modify: five service `domain/`, `integration/`, `messaging/`, `application/`, and `config/` Kotlin sources under `commerce/usage-billing-*-service/`.
- Modify: service and composition tests under `commerce/usage-billing-*-service/src/test/` and `commerce/usage-billing-microservices-composition-tests/src/test/`.
- Modify: `docs/images/readme-diagrams/usage-billing-microservices-*.svg/png`, `scripts/generate-usage-billing-microservices-diagrams.mjs`, and both composition READMEs.
- Modify: `docs/review/2026-07-23-issue-555-usage-billing-microservices-review.md`, lesson, and PR body after fresh evidence.

### Task 1: Lock strict Kotlin-pattern regressions

- [x] Add architecture tests that require serializable durable contracts, KDoc on public envelopes, Bluetape validation helpers in decoders, and `KLogging` at consumer/quarantine transitions.
- [x] Run the added tests and observe failures against the current source.
- [x] Implement the smallest source changes: `Serializable` plus `serialVersionUID`, named validated values, `KLogging`, and explicit terminal/replay logs without payloads or credentials.
- [x] Replace `runCatching` exception assertions and bare `check` polling assertions with Bluetape assertion APIs.
- [x] Run each affected service test and the composition default test.

### Task 2: Preserve Exposed/Spring/module boundaries

- [x] Confirm every concrete persistence repository still implements `ExposedJdbcRepository`; retain no raw JDBC/SQL path.
- [x] Confirm Kafka listener failures retain the durable inbox/quarantine decision and do not introduce blocking/coroutine/transaction ownership drift.
- [x] Run repository architecture tests, `detekt`, `detektTest`, and the sequential composition integration matrix.

### Task 3: Replace the diagram generator with source-backed assets

- [x] Add a failing diagram manifest/audit that classifies architecture versus temporal sequence assets and rejects duplicate canonical assets.
- [x] Replace the generic `stageDiagram` template with one architecture responsibility view and four sequence-style flows containing participants, lifelines, activations, numbered messages, and branch frames where the source behavior branches.
- [x] Remove the duplicated unused `usage-billing-microservices-state-01` SVG/PNG through an explicit patch; retain the canonical `outbox-inbox-state` asset.
- [x] Render and inspect every changed PNG individually at full size; record per-asset dimensions, marker/connector counts, source paths, and observations.
- [x] Run XML, CairoSVG, text, marker, geometry, endpoint, connector, mixed-corner, and sequence-style audits for each relevant asset.

### Task 4: Restore evidence, documentation, and PR truthfulness

- [x] Update English/Korean README embeds and prose to match the corrected source and diagram set.
- [x] Replace the incorrect diagram ledger with per-asset checklist evidence and list every inspection.
- [x] Update the lesson with the failure mode: mechanical SVG checks do not prove diagram-kind or PNG readability.
- [x] Run README validator, workflow/module stale checks, actionlint, `git diff --check`, and a six-lens inline review.
- [x] Commit the repaired scope with Lore trailers, push the exact head, and refresh PR #557’s final `## DoD Status`; keep it draft until live CI and review converge.

## Validation order

1. Targeted RED/GREEN unit tests for the strict-contract guards.
2. Five service test tasks and composition default tests.
3. Sequential Testcontainers composition integration tests.
4. Detekt and architecture/raw-access scans.
5. Per-asset SVG/PNG audit ledger plus full-size visual inspection.
6. README/workflow checks, `actionlint`, `git diff --check`, live PR exact-head verification.

## Risk and rollback

- Serialization is a marker contract in this example; no Java serialization transport is introduced. Revert only the marker/UID additions if they change a framework binding unexpectedly.
- Kafka logging must retain event ID, tenant, event type, and stable outcome only; never log payloads. Revert a log field if it could disclose data.
- Diagram replacement keeps canonical filenames, so README links remain stable. Regenerate PNG only from the paired SVG and reject any SVG/PNG mismatch.
