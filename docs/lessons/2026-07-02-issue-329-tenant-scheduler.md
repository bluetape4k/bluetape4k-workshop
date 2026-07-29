# Issue #329 Tenant Scheduler Lab

## 배경

Issue #329에는 tenant-scoped leader scheduling workshop example이 필요했다. 각 tenant에는
독립 lock name, fair scheduling, stale handoff behavior, bounded report, metric tag
cardinality guidance가 필요하다.

## 결정

`leader/tenant-scheduler`를 deterministic Spring Boot 4 lab으로 만든다.

- `TenantSchedulePolicy` owns the finite tenant/job/tick policy.
- `TenantLockNamePlanner` delegates lock-name construction to
  `TenantLockNamespace`.
- `TenantSchedulerLab` models active owner skip, stale handoff, action failure,
  fairness rotation, and bounded event history without starting infrastructure.
- `TenantMetricTagPolicy`는 작은 example을 per-tenant로 유지하고, 큰 set은
  `tenant=bounded`로 degrade한다.

## 결과

module은 learner가 Redis, ZooKeeper, Kubernetes Lease practice module로 이동하기 전에
tenant-safe scheduling을 가르친다. README와 README.ko는 source-equivalent이며,
architecture/sequence diagram을 포함하고, `TenantSchedulerReadmeSnippetTest`를 통해 snippet을
실행 가능하게 유지한다.

## 검증

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

## 향후 지침

이 default lab을 hidden backend integration matrix로 바꾸지 않는다. real backend behavior는
backend-specific module에 추가하거나, 향후 이슈가 명시적으로 필요로 할 때 default test task
밖으로 tag한다.

sequence diagram에서는 script PASS만으로 충분하지 않다. rendered PNG에서 message label이
call line에 가깝거나 겹쳐 보이면 그 complaint를 measurable invariant로 바꾼다. 이 이슈는
final PNG를 수용하기 전에 모든 numbered call에 대해 `32px` label-bottom-to-line spacing을
사용했다.
