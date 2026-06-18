# Gateway README Diagram Refresh

## Context

The gateway root README described a generic workshop slice and linked a child
module scenario image instead of explaining the actual gateway system boundary.
Legacy Graphviz artifacts also remained beside the README image.

## Decision

Treat the root `gateway` README as a runtime overview for the three runnable
applications. Show only the public gateway, downstream WebFlux services, and
Redis-backed Bucket4j support path. Keep controller and module-specific details
for the child module READMEs.

## Outcome

The README now documents the real ports and route prefixes from
`application.yml`: `8080` gateway, `8081` customer service, `8082` order
service, `/customer-service/**`, `/order-service/**`, Swagger UI, and the
optional `/echo` route.

## Verification

- Read gateway, customer, and order application resources and controllers.
- Rendered the SVG diagram to PNG with CairoSVG.
- Visually inspected the rendered PNG and fixed footer overlap before commit.
- Checked README image links, Graphviz references, SVG XML, connector endpoints,
  and `git diff --check`.

## Future Guidance

Do not make a parent README reuse a child module scenario diagram. Parent
diagrams should explain orchestration and runtime boundaries; child diagrams
should explain service internals.
