# Dependabot ignore 동기화

## Context

`bluetape4k-dependencies`가 downstream Dependabot ignore block에 중앙 관리
의존성을 더 추가했다.

## Decision

생성된 ignore list를 이 저장소에 전파해서 Dependabot이 central catalog가
관리하는 의존성에 대해 repo-local PR을 열지 않게 한다.

## Outcome

로컬 `.github/dependabot.yml`은 이제 중앙에서 관리하는 새 Bouncy Castle,
ClassGraph, Tomcat coordinate를 ignore한다.

## Verification

- `git diff --check`

## Future note

central dependency wave 이후에는 central downstream CI gate를 다시 실행하기
전에 shared version sync와 함께 `sync-dependabot-ignores.py`를 실행한다.
