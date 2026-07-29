# Profile image moderation example

## 배경

profile-image upload, pending blurred preview, asynchronous moderation, approved public image,
rejected/default fallback을 다루는 새 `image-processing/profile-image-moderation` workshop
example을 추가했다.

## 결정

example은 기본적으로 local 및 deterministic하게 유지한다. S3-compatible object semantic에는
`ImageStorage`, state에는 in-memory repository, moderation에는 configurable 1초 delay가 있는
fake moderation provider, metric에는 low-cardinality tag가 있는 Micrometer를 사용한다.

## 결과

example은 bilingual README file에 scenario를 문서화하고, architecture/sequence diagram을
포함하며, root documentation, smoke validation, CI path filter/artifact에 module을 등록한다.

## 검증

- RED: 구현 전 targeted service test는 unresolved profile-image symbol로 실패했다.
- GREEN:
  `./gradlew :image-processing-profile-image-moderation:test --rerun-tasks --console=plain`은
  14개 test를 실행했다.
- Diagram QA: full-size PNG inspection 이후 explicit
  `node scripts/validate-readme-diagram-qa.mjs ...architecture...svg ...sequence...svg`가
  `targets=2`, `weak_reference_rows=0`으로 통과했다.
- Repo checks: `./gradlew projects`, `./scripts/smoke-validate.sh stale-check`,
  `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml`,
  `git diff --check`, `./scripts/smoke-validate.sh all-smoke`가 통과했다.

## 향후 참고

README diagram은 post-review polish step이 아니라 hard gate로 다룬다. workflow step 완료를
주장하기 전에 explicit diagram QA wrapper를 실행하고, 변경된 모든 PNG를 full size로 연다.
