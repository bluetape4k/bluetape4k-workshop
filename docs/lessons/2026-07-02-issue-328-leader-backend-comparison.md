# Issue #328 Leader Backend Comparison Lab

## Context

Issue #328 needed a workshop example that compares Redis, ZooKeeper, and
Kubernetes Lease leader-election backends without duplicating the existing real
backend modules.

## Decision

Build `leader/backend-comparison-lab` as a deterministic comparison module:

- `LeaderBackendCatalog` stores source-backed backend profiles.
- `LeaderFailoverLab` models learner-visible scenario reports.
- Real backend practice remains in `leader-election`, `leader-zookeeper`, and
  `k8s-lease-micrometer`.
- Default tests remain infrastructure-free.

## Outcome

The module now teaches backend choice, failover trigger differences, skip
behavior, action-failure recovery, and metrics/events to inspect. README and
README.ko are source-equivalent and embed architecture plus sequence diagrams.

## Verification

- `./gradlew :leader-backend-comparison-lab:compileKotlin :leader-backend-comparison-lab:compileTestKotlin --warning-mode all`
- `./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `./scripts/smoke-validate.sh stale-check`
- explicit `node scripts/validate-readme-diagram-qa.mjs` for the new architecture and sequence SVGs
- full-size PNG eye inspection for both diagrams
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml`
- `git diff --check`

## Future Guidance

Do not convert this lab into a hidden integration-test matrix. Add backend-heavy
practice to the backend-specific modules, or tag it outside the default test
task if a future issue explicitly needs it. Keep diagram changes under the full
bluetape4k diagram checklist and inspect the rendered PNG, not only the SVG.
