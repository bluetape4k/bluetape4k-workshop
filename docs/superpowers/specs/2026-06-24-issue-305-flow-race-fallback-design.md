# Issue 305 Flow Race/Fallback Design

## Goal

Add a workshop module that explains multi-source Flow selection without manual async/select plumbing.

## Decisions

- Use `RaceFallbackCatalog` as a thin operator-selection facade.
- Keep the domain in-memory and deterministic: `CatalogItem`, `CatalogSource`, `SourceResult`, `SourceQuality`.
- Treat `race` as `amb`: first emitted value wins and losing source jobs are cancelled.
- Use `concat` for strict priority fallback.
- Use `concatArrayEager` and `concatMapEager` to show eager source startup with ordered output.
- Use `merge` when every source contributes partial data.
- Use `materialize` / `dematerialize` to distinguish terminal errors from error-as-value explanations.

## Diagrams

README embeds Scenario, Architecture, ERD, Class, and Sequence diagrams as generated PNG assets with SVG sources.

## Verification target

- Module tests cover race cancellation, ordered fallback, eager fallback, dynamic eager mapping, merge, materialize, and dematerialize.
- Diagram XML, geometry, endpoint, rendered PNG visual checks pass.
