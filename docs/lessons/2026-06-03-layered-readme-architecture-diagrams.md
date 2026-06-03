# Layered README architecture diagrams

## Context

README diagram standardization had drifted into generic horizontal flow diagrams and duplicate
Architecture/Flow sections. The rendered PNGs were technically generated, but several assets did not
explain the layered structure of the examples and some README pairs diverged between English and Korean.

## Decision

Use the graph examples as the visual baseline for README architecture diagrams: layered bands,
source-derived cards, visible orthogonal connectors, and the `Architects Daughter` / `Comic Mono`
font roles. Keep Graphviz `.dot`, `.plain`, and sketch assets as evidence, but render the committed
README SVG/PNG with a layered layout instead of the raw Graphviz horizontal pipeline.

## Outcome

- Regenerated 91 README architecture SVG/PNG assets with layered band structure.
- Widened architecture canvases to 1320px so same-layer connectors have enough route space.
- Added domain-specific layer and card labels for AWS, Exposed, messaging, graph, observability,
  Redis, rate limit, Spring Data, Spring Boot, security, and virtual-thread examples.
- Removed visible architecture edge labels; semantic labels remain as SVG `data-label` metadata.
- Restored the four existing `graph/` architecture assets and excluded them from the generic
  generator/validator because those module-specific diagrams explain the graph examples better.
- Removed generated Flow sections from README files.
- Removed duplicate Architecture sections and duplicate image targets.
- Kept README parity between `README.md` and `README.ko.md`.

## Verification

- `node scripts/validate-readme-language.mjs`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-sequence-diagrams.mjs`
- `node scripts/validate-readme-architecture-diagrams.mjs` for 87 generated architecture SVGs,
  excluding the four preserved `graph/` assets by explicit filename
- architecture asset gate: 91 SVGs, missing pairs 0, bad font families 0
- architecture route gate: orthogonal paths, boundary endpoints, endpoint angles, and non-endpoint
  node-interior/clearance checks for all 91 SVGs
- README image link gate: duplicate targets 0, SVG links 0, missing files 0
- `xmllint --noout` for all `*-readme-architecture-01.svg`
- `git diff --check`
- visual contact sheet: `.omx/diagram-review/readme-architecture-contact-sheet-domain-specific.png`
- individual PNG checks: `aws`, `messaging/kafka`, `messaging/kafka-reply`,
  `observability/micrometer-tracing-coroutines`
- individual PNG rechecks after route fix: `exposed-mvc-jdbc`, `aws`, `messaging/kafka`

## Future Rule

Do not add generic README Flow sections unless there is a domain-specific flow asset. Architecture
diagrams should default to layered structure and must be visually checked in rendered PNG form,
especially when same-layer connector labels are present.

Repeated connector complaints must be promoted into generator validation immediately. Same-layer
card pairs need enough canvas width, enough horizontal gap, side-to-side routing when their centers
align, and no visible edge-label boxes over connector paths. Do not overwrite module-specific
architecture assets that are already more explanatory than the generic generator output.
