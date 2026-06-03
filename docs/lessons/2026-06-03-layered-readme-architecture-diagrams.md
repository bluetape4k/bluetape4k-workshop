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
- Removed generated Flow sections from README files.
- Removed duplicate Architecture sections and duplicate image targets.
- Kept README parity between `README.md` and `README.ko.md`.

## Verification

- `node scripts/validate-readme-language.mjs`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-sequence-diagrams.mjs`
- architecture asset gate: 91 SVGs, missing pairs 0, bad font families 0
- README image link gate: duplicate targets 0, SVG links 0, missing files 0
- `xmllint --noout` for all `*-readme-architecture-01.svg`
- `git diff --check`
- visual contact sheet: `.omx/diagram-review/readme-architecture-contact-sheet-layered.png`
- individual PNG checks: `aws`, `messaging/kafka`, `messaging/kafka-reply`,
  `observability/micrometer-tracing-coroutines`

## Future Rule

Do not add generic README Flow sections unless there is a domain-specific flow asset. Architecture
diagrams should default to layered structure and must be visually checked in rendered PNG form,
especially when same-layer connector labels are present.
