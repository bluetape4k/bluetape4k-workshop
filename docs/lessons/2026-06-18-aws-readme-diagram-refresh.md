# AWS README diagram refresh

## Context

The AWS workshop README refresh replaced Graphviz-era assets with source-backed
README diagrams for the root `aws` module, `s3-spring-cloud`, and
`storage-abstraction`.

## Decision

- Keep README diagrams under the repository's `docs/images/readme-diagrams/`
  asset directory, but remove obsolete Graphviz `.dot`, `.plain`, and
  `*-graphviz.*` assets.
- Use official AWS service icons for S3 cards and shared wiki icons for
  Testcontainers/Floci runtime cards.
- Treat sequence diagrams as behavior views, not architecture diagrams. Use the
  `leader-core-sequence-02` visual family for participant headers, lifelines,
  branch frames, numbered message labels, and restrained arrows.

## Outcome

The AWS module README now explains the two local-first S3 paths from the reader
viewpoint. The S3 sequence omits low-value log-only participants and shows the
actual bucket/object flow through `S3Client`, `S3Template`, S3, and Floci.

## Verification

- Rendered changed SVG assets to PNG with CairoSVG.
- Visually inspected the AWS overview, S3 architecture, S3 sequence, and storage
  abstraction diagrams.
- Verified README image links, SVG XML parsing, absence of Graphviz remnants,
  and `git diff --check`.

## Next agents

Before accepting a README diagram refresh, inspect the final PNG, not only the
SVG source. Icons can easily cover labels after a late layout change; resize or
move the card before committing.
