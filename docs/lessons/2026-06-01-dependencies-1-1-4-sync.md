# Dependencies 1.1.4 Sync

## 배경

`bluetape4k-dependencies` 1.2.0 release 준비 중 central BOM `develop` CI gate에서
workshop catalog와 Dependabot ignore drift가 발견되었다.

## 결정

이 consumer repository는 최신 published `bluetape4k-dependencies:1.1.4` baseline을
유지하고, dependencies source of truth의 centrally governed shared version과
Dependabot ignore를 맞춘다.

## 결과

workshop catalog는 더 이상 central dependencies release-train preflight를 막지 않는다.
`1.2.0`은 publish된 뒤에만 채택해야 한다.

## 검증

`bluetape4k-dependencies`에서 `sync-shared-versions.py`와
`sync-dependabot-ignores.py`를 사용해 검증했다. 옵션은
`--workspace /Users/debop/work/bluetape4k --write --check --summary`였다.
