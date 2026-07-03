# Issue 390 Raw Validation Helper Cleanup

## Context

Issue #390 cleaned up raw caller-input validation in workshop examples after the milestone 1.3.1 review found inconsistent use of bluetape4k ecosystem helpers.

## Decisions

- Convert only simple caller-input checks to bluetape4k helpers: blank strings, non-empty collections, positive/zero-positive numbers, and bounded numeric ranges.
- Keep raw `require(...)` for security predicates, parser boundary checks, domain invariants, and user-facing messages that must preserve exact detail text or avoid echoing sensitive input.
- Keep exact `BigDecimal` comparisons raw until a decimal-specific helper exists; generic numeric helpers can lose precision through numeric conversion.
- Add direct `implementation(libs.bluetape4k.core)` to modules whose production code imports `io.bluetape4k.support`.

## Outcome

- `src/main` raw `require(...)` count moved from `151` to `111`.
- Affected production-file raw `require(...)` count moved from `92` to `52`.
- The redaction pipeline blank-text check stayed raw because `requireNotBlank` includes the raw blank value in the exception message and violates the non-echoing test contract.
- The OCR controller kept the existing oversize message because HTTP error detail is covered by tests.

## Verification

- Baseline before work: full `./gradlew build --max-workers=1 --console=plain` passed on clean `develop`.
- Affected-module `compileKotlin` passed with `--max-workers=1 --warning-mode all`.
- Affected-module `test` passed with `--max-workers=1 --warning-mode all`.
- Post-work full `./gradlew build --max-workers=1 --warning-mode all --console=plain` passed after review fixes.
- `git diff --check` passed.

## Future Guard

Do not mechanically replace every raw `require(...)`. First classify the predicate:

- helper: simple caller input validation,
- explicit raw require: security, parser, domain invariant, exact public error message, or sensitive-value non-echoing contract.
