# Issue 392 Test Null Assertion Cleanup

## Context

Issue #392 followed the milestone 1.3.1 audit and targeted test-side Kotlin `!!` usage after production null assertions had already been removed.

## Decisions

- Replace simple test null assertions with bluetape4k `shouldNotBeNull()` so failures show assertion intent instead of `NullPointerException`.
- Prefer capturing a non-null local value when the same nullable ID or response body is reused.
- Keep cursor-buffer and framework lookup cases for focused follow-up when nullability is part of the behavior under test.
- Document excluded modules when the source is edited but the root Gradle build does not include that module.

## Outcome

- Kotlin `!!` candidates moved from `163` to `68`.
- The cleanup touched 21 test files and removed 95 direct not-null assertions.
- The highest remaining cluster is Okio cursor buffer access, which needs a narrower helper-oriented cleanup rather than a mechanical conversion.

## Verification

- Baseline full build passed before edits.
- Affected compiles passed in three focused rounds.
- Affected-module tests passed for registered modules with `--max-workers=1`.
- `git diff --check` passed.
- Post-work full `./gradlew build --max-workers=1 --warning-mode all --console=plain` passed before PR creation.

## Future Guard

Do not replace test `!!` blindly. First classify the expression:

- response body, repository result, generated ID, query result: use `shouldNotBeNull()` and continue with the non-null value,
- repeated nullable value: capture a local non-null value once,
- test subject or framework nullability behavior: leave it explicit or add a local helper that preserves the behavior being tested.
