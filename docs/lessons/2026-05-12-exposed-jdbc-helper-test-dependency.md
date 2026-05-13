# exposed-domain JDBC helper test dependency

## Context

`exposed-domain` tests import `io.bluetape4k.exposed.jdbc.selectImplicitAll` while
also depending on `bluetape4k-exposed-jdbc-tests`.

## Decision or Finding

The `*-jdbc-tests` artifact provides shared test contracts and fixtures, but it
does not replace the main `bluetape4k-exposed-jdbc` artifact that owns JDBC
helper extensions.

## Outcome

The version catalog now exposes `bluetape4k-exposed-jdbc`, and
`exposed-domain` declares it as a test dependency alongside
`bluetape4k-exposed-jdbc-tests`.

## Verification

Run:

```bash
./gradlew :exposed-domain:compileTestKotlin --continue
```

## Future Guidance

When a workshop test imports production helper extensions from a bluetape4k
module, declare the production artifact explicitly. Do not assume a `*-tests`
artifact exports production helpers transitively.
