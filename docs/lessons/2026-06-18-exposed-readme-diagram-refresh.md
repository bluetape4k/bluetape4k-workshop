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

For `exposed/mvc-jdbc`, split the reader contract into two visuals. The
architecture diagram should stay static: MVC controllers, service transaction
ownership, inherited repositories, explicit order repositories, and PostgreSQL
tables. The lock ordering, `SELECT FOR UPDATE`, order-line insert, stock
decrement, and rollback path belong in a sequence diagram. The schema section
needs a separate ERD because column names, types, keys, and FK ownership are
reader-facing information, not prose-only metadata.

For `exposed/mvc-virtualthread`, keep the README centered on the virtual-thread
boundary rather than generic CRUD. The architecture diagram should show Tomcat,
the shared `ExecutorService`, repository `VirtualFuture<T>` methods, the
service-owned order transaction, exception unwrapping, and PostgreSQL tables.
Use a sequence diagram for `Future.get()`, `transaction(db)`, row locking,
rollback, and exception unwrap behavior. Use a separate ERD because this module
uses plain Exposed `Table` definitions without the MVC JDBC audit columns.

For `exposed/webflux-r2dbc`, keep transaction ownership explicit in the service
layer. The architecture diagram should separate suspend WebFlux controllers,
service-owned `suspendTransaction`, thin Flow repositories, R2DBC pool/database
configuration, JDBC-only schema initialization, and PostgreSQL tables. The
sequence diagram should show Flow/repository calls inside the coroutine R2DBC
transaction, not a Reactor-style or blocking-thread story.

For `exposed/javers-audit`, keep the architecture focused on the service-level
audit boundary. The useful reader contract is that `ProductAuditService` writes
JaVers snapshots first, then mutates the Exposed current row; `diff` stays
value-only and does not write to either store. A single-table ERD is still useful
when it clarifies that relational storage is only the latest `products` row and
not JaVers history.

## Verification

- Source checked for `@Transactional`, `virtualFuture { transaction(db) }`,
  `suspendTransaction`, and JaVers commit/upsert behavior.
- ERD columns checked against Exposed table definitions for MVC JDBC, WebFlux
  R2DBC, and the JaVers audit product table.
- Diagram rendered from SVG to PNG with CairoSVG and visually inspected.
- README image links were checked for both English and Korean files.
- `exposed/mvc-jdbc` Graphviz `.dot`, `.plain`, `*-graphviz.*`, and stale
  non-README architecture assets were removed after the README switched to
  `docs/images/readme-diagrams/*readme-*` diagrams.
- `exposed/mvc-jdbc` architecture connectors were adjusted so endpoints enter
  the target card edge; sequence canvas was expanded after visual review showed
  cramped right and bottom margins.
- Branch-level README diagrams were re-rendered with the same fixed-size
  arrowhead family: `15px` primary, `13.5px` return/secondary, and `12px`
  small schema links.
- `exposed/mvc-virtualthread` Graphviz artifacts were removed, README image
  links were switched to `docs/images/readme-diagrams/*readme-*`, and
  architecture, sequence, and ERD PNGs were rendered with CairoSVG and visually
  inspected as a contact sheet.
- `exposed/webflux-r2dbc` Graphviz artifacts were removed, README image links
  were switched to `docs/images/readme-diagrams/*readme-*`, and architecture,
  sequence, and ERD PNGs were rendered with CairoSVG and visually inspected as a
  contact sheet.
- `exposed/javers-audit` stale non-README architecture assets were removed, the
  architecture and sequence diagrams were redrawn from `ProductAuditService` and
  `ProductTable`, a single-table ERD was added, and the rendered contact sheet
  plus high-risk sequence/architecture PNGs were visually inspected.

## Future guidance

For root workshop READMEs, explain how a reader should choose a submodule.
Avoid reusing a submodule sequence diagram as the root visual unless the root
README is specifically about that scenario.
After a root module pass, scan direct child README files before moving to a
different top-level module; root diagrams do not replace submodule README
refreshes when each child has its own runnable artifact and Graphviz remnants.
For locking examples, do not compress the lock/rollback behavior into an
architecture box. Keep repository ownership static in the architecture diagram
and show transaction ordering in a sequence diagram with transparent branches.
For module schemas, add an ERD when the README otherwise relies on a prose table
to explain FK ownership. Keep relationship lines outside column text corridors.
When arrowhead size changes, re-check path endpoints numerically and visually.
A line can look plausible at a glance while its endpoint lands beside a card,
especially after marker `refX` or marker width changes.
If a persistence layer looks cramped, increase the canvas and the layer/card
height together instead of only shrinking text or moving the label. The layer
title area counts as real visual space when judging top and bottom margins.
If an architecture diagram needs call-order hints, prefer card text and sequence
diagrams over route chips. Remove chips that sit on top of connector lines even
when the chip text is accurate.
