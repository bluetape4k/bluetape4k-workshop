# Lessons — README, Diagrams, and Coverage Matrix Bundle

**Date**: 2026-05-24  
**Issues**: #90 (README rewrite), #89 (diagram asset convention), #92 (coverage matrix)  
**Type**: Type-E Maintenance  
**Branch**: `docs/issue-76-readme-diagrams-coverage`

---

## Root Cause / Motivation

The previous root `README.md` was 93 lines of mostly Korean, with no module-level detail
(difficulty, infra requirements, bluetape4k library mapping, run commands). New contributors
and bluetape4k users had no quick way to choose a relevant starting example or understand
coverage gaps.

Additionally:
- No convention existed for diagram naming or placement (`docs/assets/CONVENTIONS.md` absent).
- No document mapped bluetape4k library → existing example → gap, making it hard to prioritize
  new workshop modules.

---

## Decisions Made

### README structure

Chose a **6-domain catalog** (Data Access, Spring Boot Ops, Serialization/Messaging,
Async/Reactive, Observability/Performance, Architecture Extensions) because:
- Maps directly to open issues #79, #82, #83 (Basic) and future Advanced modules
- Mirrors how the bluetape4k library groups are organized
- Allows progressive learning: Basic → Advanced within each domain

Each row in the catalog table shows: module name (linked), bluetape4k libs used,
infra (TC = Testcontainers), and learning outcome. This replaces the generic
directory listing that conveyed no learning value.

### README.ko.md

Created as a full Korean counterpart (not a translation wrapper). Per workspace CLAUDE.md:
- `README.md` stays in English (contributor-facing)
- `README.ko.md` serves Korean-speaking users
- Both maintained in sync; future changes to one must mirror the other

### docs/assets/CONVENTIONS.md

Defined the `<scope>-<type>-<seq>.<ext>` naming pattern rather than ad-hoc names.
Key decision: keep source files (`.drawio`, `.puml`, `.mmd`) committed alongside exports
to prevent "diagram exists but can't be edited" technical debt.

### Coverage matrix

Tracked 30 bluetape4k libs. Result: 57% good coverage, 40% partial, 3% missing (idgenerators).
This surfaces the concrete work for issues #62, #79, #82, #83 and informs Tier 2 priorities.

---

## Outcome

| Artifact | Lines | Status |
|----------|-------|--------|
| `README.md` | 207 | ✅ Rewritten |
| `README.ko.md` | 209 | ✅ Created |
| `docs/assets/CONVENTIONS.md` | 101 | ✅ Created |
| `docs/images/readme-diagrams/root-readme-architecture.md` | 92 | ✅ Created |
| `docs/coverage-matrix.md` | 145 | ✅ Created |

No code changes; Type-E — tests/compile not required.

---

## Future Guidance

1. **Sync READMEs on every module add/remove**: when a new workshop module lands, add it to
   both `README.md` and `README.ko.md` in the same PR. The table format makes it easy to
   add one row without touching everything else.

2. **Coverage matrix is a living document**: update `docs/coverage-matrix.md` when a
   proposed scenario becomes an actual module. Move the row from "Proposed" to "Existing example"
   and update the coverage level.

3. **Diagram source files**: never commit only the PNG/SVG. Always commit the source
   (`.drawio`, `.puml`, `.mmd`) so diagrams remain editable. The CONVENTIONS.md checklist
   enforces this.

4. **README.ko.md locale discipline**: the CLAUDE.md doc language policy allows Korean
   user-facing README files. When making content changes, update both locales in the same PR
   to prevent drift.
