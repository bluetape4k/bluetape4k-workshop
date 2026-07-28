# Issue 245 Examples Workflow Review

## 범위

- `.github/workflows/Examples.yml`
- `docs/lessons/2026-06-06-issue-245-examples-weekly.md`

## 리뷰 결과

- P0: 0
- P1: 0
- P2: 0

## 발견 사항

blocking finding은 없다.

## 증거

- workflow에는 weekly schedule `0 22 * * 0`이 하나 있다.
- `workflow_dispatch`, push path filter, pull request path filter가 존재한다.
- 선택된 module list는 workflow comment와 Gradle command line에 명시되어 있다.
- H2/default smoke example과 Testcontainers-backed example이 분리되어 있다.
- Testcontainers-backed module은 `--max-workers=1`을 사용해 하나의 Gradle invocation에서
  실행된다.
- 두 example lane 모두 test result artifact를 upload한다.
- `actionlint .github/workflows/Examples.yml`가 통과했다.
- `git diff --check`가 통과했다.

## 잔여 위험

merge 전 PR에서 GitHub Actions check가 완료되어야 한다. container-backed lane은 PostgreSQL,
Redis, Kafka example을 순차 시작하므로 smoke lane보다 느릴 수 있다.
