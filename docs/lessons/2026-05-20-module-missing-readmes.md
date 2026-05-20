# Module Missing README Fix

## Context

Several example modules had source code but no module-level `README.md` and `README.ko.md`.
The requested scope covered gateway customer/order services, Spring Modulith event deep dive,
and three Spring Security examples.

## Decision

Add concise bilingual README files that cite only source-verified behavior, and place matching
infographic-style SVG plus rendered PNG assets under `docs/images/readme-diagrams/`.

## Outcome

The new READMEs document module purpose, architecture image, runnable Gradle task, key endpoints,
and source map. Diagrams keep visible text in English, use `Architects Daughter` for large labels,
and render to 1378x526 PNGs.

## Verification

- Source evidence inspected with `ctx_batch_execute`: controller mappings, security configuration,
  application YAML, Spring Modulith event packages, and sibling README patterns.
- Link/asset validator passed for 12 README files, 6 SVG files, and 6 PNG files.
- `git diff --check` passed for the touched README and diagram files.
- Gradle help verification passed for all six documented module paths.

## Future Guidance

When fixing module-missing-readme findings, generate the architecture image near the top of both
localized READMEs and verify all relative image links from the README location, not from repo root.
