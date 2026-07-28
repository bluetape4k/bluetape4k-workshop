# 중앙 Dependabot ignore 동기화

## Context

`bluetape4k-dependencies`는 이제 BouncyCastle, ClassGraph, Tomcat line의
Dependabot alert routing을 소유한다. Workshop 저장소는 중앙에서 관리하는
해당 package에 대해 직접 Dependabot version PR을 받지 않아야 한다.

## Decision

local ignore entry를 손으로 유지하지 말고, `bluetape4k-dependencies`에서 생성한
central ignore block을 동기화한다.

## Outcome

저장소 Dependabot configuration은 이제 중앙에서 관리하는 새 의존성 이름을
ignore한다. 앞으로 version change는 `bluetape4k-dependencies`에서 시작하고,
그 sync script로 전파해야 한다.

## Verification

- `scripts/sync-dependabot-ignores.py --workspace .. --write --check --summary`
- `git diff --check`
