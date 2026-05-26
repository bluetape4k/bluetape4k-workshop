## Context

Adopted the JetBrains Exposed Gradle plugin for workshop modules that define Exposed tables in main sources.

## Decision

Workshop repositories stay independent from the managed `bt4k` catalog. They use their repo-local Exposed version alias for the Gradle plugin and continue to consume `bluetape4k-dependencies` as a BOM.

## Outcome

Main Exposed workshop modules now expose `generateMigrations` with module-local table package and H2 migration database settings.

## Verification

Ran `git diff --check`, `./gradlew -q help`, and `:exposed-mvc-jdbc:tasks --all`.

## Future Guard

Do not add `bluetape4kDependenciesCatalogRef` to workshop repositories unless they are intentionally promoted to managed library repos.
