# 중앙 의존성 거버넌스 동기화

## Context

downstream Dependabot PR이 공유 의존성 버전을 저장소별로 따로 갱신하면서
bluetape4k 조직 전체에 버전 드리프트가 생기고 있었다.

## Decision

공유 의존성 버전은 먼저 `bluetape4k-dependencies`에서 변경한 뒤
`sync-shared-versions.py`로 이 저장소에 반영해야 한다. 또한 이 저장소는
중앙에서 관리하는 의존성 이름을 Dependabot에서 ignore하여, 이후 PR이 중앙
source of truth를 거치도록 한다.

## Outcome

로컬 version catalog와 `.github/dependabot.yml`은 이제 중앙 의존성 거버넌스
정책을 따른다.

## Verification

- 이 저장소에서 `sync-shared-versions.py --write --check --summary`
- 이 저장소에서 `sync-dependabot-ignores.py --write --check --summary`
- `git diff --check`

## Future Guard

중앙에서 관리하는 의존성에 대한 repo-local Dependabot PR은 merge하지 않는다.
`bluetape4k-dependencies`를 갱신한 다음 이 저장소를 동기화한다.
