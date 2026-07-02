# Issue #329 Tenant Scheduler Lab

## Context

Issue #329 needed a workshop example for tenant-scoped leader scheduling: each
tenant needs an independent lock name, fair scheduling, stale handoff behavior,
bounded reports, and metric tag cardinality guidance.

## Decision

Build `leader/tenant-scheduler` as a deterministic Spring Boot 4 lab:

- `TenantSchedulePolicy` owns the finite tenant/job/tick policy.
- `TenantLockNamePlanner` delegates lock-name construction to
  `TenantLockNamespace`.
- `TenantSchedulerLab` models active owner skip, stale handoff, action failure,
  fairness rotation, and bounded event history without starting infrastructure.
- `TenantMetricTagPolicy` keeps small examples per-tenant and degrades large
  sets to `tenant=bounded`.

## Outcome

The module now teaches tenant-safe scheduling before learners move to Redis,
ZooKeeper, or Kubernetes Lease practice modules. README and README.ko are
source-equivalent, include architecture and sequence diagrams, and keep the
snippet executable through `TenantSchedulerReadmeSnippetTest`.

## Verification

- `./gradlew --no-daemon :leader-tenant-scheduler:test --no-build-cache --rerun-tasks --console=plain`
- `./gradlew --no-daemon :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --warning-mode all --console=plain`
- `./gradlew --no-daemon projects --console=plain`
- `./scripts/smoke-validate.sh all-smoke`
- `./scripts/smoke-validate.sh stale-check`
- explicit `node scripts/validate-readme-diagram-qa.mjs` for the architecture and sequence SVGs
- full-size PNG eye inspection for both diagrams
- independent vision re-check after sequence label spacing repair
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml`
- `git diff --check`

## Future Guidance

Do not turn this default lab into a hidden backend integration matrix. Add real
backend behavior to the backend-specific modules, or tag it outside the default
test task if a future issue explicitly needs it.

For sequence diagrams, script PASS is not enough. If the rendered PNG makes
message labels look close to or on top of call lines, convert the complaint into
a measurable invariant. This issue used `32px` label-bottom-to-line spacing for
all numbered calls before accepting the final PNG.
