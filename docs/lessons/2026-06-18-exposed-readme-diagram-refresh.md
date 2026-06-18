# Exposed README diagram refresh

## Context

The exposed root README still described a generic Graphviz-era architecture and
listed only three modules, while the source tree contains four runnable Exposed
modules: `javers-audit`, `mvc-jdbc`, `mvc-virtualthread`, and
`webflux-r2dbc`.

## Decision

Use a module-selection architecture diagram instead of a generic layered graph.
Keep one vertical column per module so the source-backed relationships remain
straight: module entrypoint, transaction boundary, and persistence runtime.
Add a compact ERD because the root README's domain section otherwise forces the
reader to infer FK ownership from ASCII arrows and source files.

## Verification

- Source checked for `@Transactional`, `virtualFuture { transaction(db) }`,
  `suspendTransaction`, and JaVers commit/upsert behavior.
- ERD columns checked against Exposed table definitions for MVC JDBC, WebFlux
  R2DBC, and the JaVers audit product table.
- Diagram rendered from SVG to PNG with CairoSVG and visually inspected.
- README image links were checked for both English and Korean files.

## Future guidance

For root workshop READMEs, explain how a reader should choose a submodule.
Avoid reusing a submodule sequence diagram as the root visual unless the root
README is specifically about that scenario.
