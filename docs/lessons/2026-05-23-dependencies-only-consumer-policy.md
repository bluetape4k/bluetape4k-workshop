# Dependencies-Only Consumer Policy

## Context

The workshop repository previously carried direct bluetape4k artifact version
aliases while also importing the ecosystem BOM. That made release upgrades
harder because the BOM and local aliases could drift independently.

## Decision

Use `bluetape4k-dependencies` as the only bluetape4k version source in the
version catalog. Keep bluetape4k artifact aliases versionless so dependency
management resolves them from the BOM.

## Outcome

The catalog now removes direct bluetape4k version refs, imports
`bluetape4k-dependencies`, and uses the current BOM-managed Spring Boot core
artifact coordinate.

## Verification

Ran forbidden-reference grep, `git diff --check`, and
`./gradlew :redis-redisson-examples:compileKotlin --no-daemon --no-configuration-cache`.

## Future Guidance

For release-upgrade PRs, update only the `bluetape4k-dependencies` version for
bluetape4k ecosystem artifacts unless a module intentionally consumes a
non-BOM artifact.
