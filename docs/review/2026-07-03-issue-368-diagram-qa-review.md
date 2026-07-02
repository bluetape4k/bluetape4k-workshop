# Issue 368 Diagram QA Review

Date: 2026-07-03
Scope: milestone 1.3.1 README diagram QA hardening for architecture, sequence, and state SVG/PNG assets.

## 7-Tier Findings

P0/P1: none remaining.

Resolved before PR:

- Several generated SVGs passed visually but lacked audit-detectable connector/card metadata. Added `card`, `flow`, marker, and `data-connector` metadata without changing learner-facing content.
- Sequence diagrams had inconsistent label-pill classes and marker styling. Normalized numbered labels, transparent alt regions, branch-specific call colors, and marker/path color parity.
- Kotlin Flow event aggregation sequence was rebuilt into the current best-practices layout with framed title/subtitle, participant headers, activation bars, numbered call labels, and light label pills.
- Kafka fallback sequence had black call-label pills because the rendered class did not match the CSS rule. Added explicit `labelPill` styling and re-rendered the PNG.
- Architecture connector checks were strengthened by converting sharp mixed-corner bends to rounded bends where the checklist expected them.

## Evidence

- `node scripts/validate-readme-diagram-qa.mjs $(git diff --name-only 10c6a1078..develop -- 'docs/images/readme-diagrams/*.svg')`
  - Result: PASS
  - Scope: 37 SVG targets
  - Weak reference rows: 0
  - Architecture validator: PASS, checked 113
  - Sequence validator: PASS, checked 88
  - Sequence style reference audit: PASS, sequence files 16
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-sequence-style-audit.py $(git diff --name-only 10c6a1078..develop -- 'docs/images/readme-diagrams/*sequence*.svg')`
  - Result: PASS, sequence files 16
- `git diff --check`: PASS
- Rendered PNG eye check:
  - `aws-cloudwatch-imds-observability-readme-architecture-01.png`: PASS, no broken icons, labels centered, legend visible.
  - `aws-cloudwatch-imds-observability-readme-sequence-01.png`: PASS, alt region transparent, branch labels readable, marker colors match paths.
  - `aws-s3-vectors-access-grants-readme-architecture-01.png`: PASS, vertical layer flow clear, rounded bends correct, legend visible.
  - `kafka-outbox-fallback-readme-architecture-01.png`: PASS, cards aligned, connector metadata detected, no PNG arrow mismatch.
  - `kafka-outbox-fallback-readme-sequence-01.png`: PASS after label-pill styling repair; labels no longer render as black blocks.
  - `kafka-outbox-fallback-readme-state-01.png`: PASS, lifecycle paths are legible with no sharp-bend or arrowhead defects.
  - `kotlin-flow-extensions-event-aggregation-readme-architecture-01.png`: PASS, rounded fan-out connectors and layer spacing are legible.
  - `kotlin-flow-extensions-event-aggregation-readme-sequence-01.png`: PASS, best-practices sequence layout applied.
  - `kotlin-flow-extensions-metrics-sampling-readme-architecture-01.png`: PASS, centered cards and simple vertical flow.
  - `kotlin-flow-extensions-metrics-sampling-readme-sequence-01.png`: PASS, labels, alt frames, and dashed calls are readable.
  - `kotlin-text-processing-readme-architecture-01.png`: PASS, metadata repair did not change visual layout.
  - `spring-boot-text-moderation-api-readme-architecture-01.png`: PASS, connectors are readable and aligned.
  - `spring-boot-text-moderation-api-readme-sequence-01.png`: PASS, transparent alt region and branch colors preserved.

## Residual Risk

- #368 is scoped to diagram QA/style. Kafka outbox semantic follow-up work remains tracked separately in #369.
