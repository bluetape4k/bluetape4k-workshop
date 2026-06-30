# Issue 316 Text Moderation API Lesson

## Context

Milestone 1.3.1 needed a deterministic text web-safety moderation workshop
example. The example had to stay local, use the root `bluetape4k-dependencies`
BOM, reuse existing `bluetape4k-text` aliases, and explain the learner-facing
flow with README diagrams.

## Decision

Use Spring MVC with a small `TextModerationController`, a reusable
`TextModerationService`, and singleton beans for Lingua language detection plus
the Aho-Corasick blockword matcher. Keep blockwords configurable but small so
learners can trace every response field back to configuration and code.

## Outcome

The module verifies success masking, Korean language detection, invalid input
mapping, oversized payload mapping, and bean reuse without external services.
README diagrams use top-to-bottom architecture layers and a best-practices
sequence layout with participant headers, lifelines, activation bars, pill
labels, and a separate error branch note.

## Verification

- `./gradlew :spring-boot-text-moderation-api:test --warning-mode all --console=plain`: 10 tests passed.
- `./gradlew :spring-boot-text-moderation-api:compileTestKotlin --warning-mode all --console=plain`: build passed; only pre-existing root Gradle deprecation warnings appeared.
- README language, parity, architecture, and sequence validators passed.
- Diagram geometry, connector, endpoint, mixed-corner, and sequence style audits passed.
- PNG eyes-check passed for both architecture and sequence diagrams.
- `./scripts/smoke-validate.sh stale-check`: active modules 88/88, no stale refs, no broken README image links.

## Future Notes

For the next workshop example, add the module to the Examples workflow and
`scripts/smoke-validate.sh` at the same time as the README catalog entry. Run
diagram geometry audit in addition to the repo validators; sharp orthogonal
turns can pass repo parsing but still fail the diagram skill checklist.
