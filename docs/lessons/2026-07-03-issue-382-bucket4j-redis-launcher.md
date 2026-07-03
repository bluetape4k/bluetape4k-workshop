# Issue 382 Bucket4j Redis Launcher Alignment

## Context

The `bucker4j-bluetape4k-webflux` example started Redis with a direct `RedisServer(useDefaultPort = true)` construction in runtime bootstrap code. That bypassed the bluetape4k Testcontainers launcher singleton pattern and coupled local startup to a fixed Redis port.

## Decision

- Use `RedisServer.Launcher.redis` for `dev` and `test` profiles, matching the rest of the workshop Redis-backed examples.
- Remove manual `start()` and `ShutdownQueue` wiring from the module bootstrap.
- Keep Spring property wiring through `testcontainers.redis.*` so the rest of the Lettuce configuration stays unchanged.
- Update README and README.ko with the actual launcher behavior and the real Gradle project path.
- Fix adjacent trace logging that printed `$this` instead of the extracted rate-limit key.

## Outcome

The module now uses the bluetape4k ecosystem launcher for Redis, avoids fixed default-port coupling, and keeps learner-facing smoke commands aligned with the registered Gradle project name.

## Verification

- Baseline build before issue work: `/tmp/issue382-baseline-build.log` — `BUILD SUCCESSFUL in 1m 43s`.
- `:bucker4j-bluetape4k-webflux:compileKotlin`
- `:bucker4j-bluetape4k-webflux:compileTestKotlin`
- `:bucker4j-bluetape4k-webflux:test` with 6 executed tests and 2 existing skipped tests (`/tmp/issue382-targeted-test.log`)
- README parity and README language validators (`/tmp/issue382-readme-parity.log`, `/tmp/issue382-readme-language.log`)
- Full build after work: `/tmp/issue382-full-build.log` — `BUILD SUCCESSFUL in 1m 35s`.
- `git diff --check` (`/tmp/issue382-diff-check.log`)

## Future Notes

When a README command fails because the directory name differs from the Gradle project name, verify with `./gradlew projects` and update the documentation in the same PR. For Testcontainers infrastructure already wrapped by bluetape4k, use `XxxServer.Launcher` unless the test explicitly needs an isolated failure container.
