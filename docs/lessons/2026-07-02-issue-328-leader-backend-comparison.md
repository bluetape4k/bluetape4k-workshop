# Issue #328 Leader Backend Comparison Lab

## 배경

Issue #328에는 기존 real backend module을 중복하지 않으면서 Redis, ZooKeeper,
Kubernetes Lease leader-election backend를 비교하는 workshop example이 필요했다.

## 결정

`leader/backend-comparison-lab`을 deterministic comparison module로 만든다.

- `LeaderBackendCatalog` stores source-backed backend profiles.
- `LeaderFailoverLab` models learner-visible scenario reports.
- Real backend practice remains in `leader-election`, `leader-zookeeper`, and
  `k8s-lease-micrometer`.
- default test는 infrastructure-free로 유지한다.

## 결과

module은 이제 backend choice, failover trigger difference, skip behavior,
action-failure recovery, 검사할 metrics/events를 가르친다. README와 README.ko는
source-equivalent이며 architecture 및 sequence diagram을 포함한다.

## 검증

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

## 향후 지침

이 lab을 hidden integration-test matrix로 바꾸지 않는다. backend-heavy practice는
backend-specific module에 추가하거나, 향후 이슈가 명시적으로 필요로 할 때 default test task
밖으로 tag한다. diagram 변경은 전체 bluetape4k diagram checklist 아래에서 유지하고, SVG뿐
아니라 rendered PNG도 검사한다.
