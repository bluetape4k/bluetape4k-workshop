# Issue 305 Flow Race/Fallback Lesson

## Context

The race/fallback example sits next to the Subject bridge module and teaches source-composition decisions for multi-source reads.

## Decision

Use source lifecycle tests instead of timing-only assertions. Atomic flags verify loser cancellation and eager source startup, while result assertions verify ordered output.

## Outcome

The module documents when to choose `race`, `concat`, eager concat, `merge`, and materialized error handling.

## Future guidance

Do not describe `merge` as ordered fallback. It collects all sources by arrival order. Use `concat` or eager concat when priority order matters.
