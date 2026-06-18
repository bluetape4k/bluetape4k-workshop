# Workshop README diagram refresh

## Context

The workshop README refresh replaced generated or template-like README content
with source-backed module explanations and diagrams. The work covered Spring,
AWS, Exposed, graph, Vert.x, virtual-thread, and related example modules.

## Decision

- Keep README images under `docs/images/readme-diagrams/` and remove obsolete
  Graphviz `.dot`, `.plain`, and `*-graphviz.*` outputs.
- Prefer reader-facing headings such as `Architecture`, `Request Flow`,
  `Module Guide`, and domain-specific seed-data names over generated headings
  such as `Example Scenario` and `Sequence Diagram`.
- Render every changed SVG to PNG and inspect the PNG before committing.
- Use readable card layouts with `Architects Daughter` labels, restrained
  arrows, same-color arrowheads, and enough image size to avoid card/text/line
  overlap.
- Do not keep generators that can reintroduce old README templates after the
  hand-reviewed module documents have been corrected.

## Outcome

The branch now has module-level commits for the README and diagram refresh. The
remaining virtual-thread modules were completed with source-backed diagrams for
the usage rules, Spring MVC on Tomcat, and Spring WebFlux dispatcher comparison.
Residual generated section names were removed from root/group READMEs, and the
legacy `README_KO.md` plus stale README generator were deleted.

## Verification

- Rendered changed SVG assets to PNG with CairoSVG.
- Visually inspected the newly rendered diagrams before committing.
- Verified global README image links.
- Verified all `docs/images/readme-diagrams/*.svg` files parse with `xmllint`.
- Verified no README-facing Graphviz artifacts remain in `docs/images/readme-diagrams`.
- Verified stale template strings outside historical lessons no longer appear.
- Ran `git diff --check`.

## Next agents

For future README diagram work, do not rely on SVG source inspection alone.
Render the PNG and check it visually for label overflow, card crowding,
arrowhead size, connector/card contact, line-label overlap, and unequal visual
margins inside labeled layers. If a diagram still looks like a PPT mockup, use
the better existing repository examples as the baseline before editing more
labels.
