# Issue 305 Flow Race/Fallback Plan

## Tasks

- [x] Create `kotlin/flow-extensions-race-fallback` module.
- [x] Add domain DTOs and `RaceFallbackCatalog` operator facade.
- [x] Add tests for `race`, `concat`, `concatArrayEager`, `concatMapEager`, `merge`, `materialize`, and `dematerialize`.
- [x] Add English and Korean README files.
- [x] Add Scenario, Architecture, ERD, Class, and Sequence diagrams.
- [x] Register the module in root README files.
- [ ] Run module tests.
- [ ] Run module registration and diff checks.
- [ ] Run diagram audits and full-size PNG visual QA.
- [ ] Commit, push, and open PR.

## Risks

- Eager operators start source jobs immediately but still emit in source order; tests must prove both facts.
- `race` winner is first emitted value, not first source to start.
- Merge output order is arrival-based, so tests must assert sets instead of exact order.
