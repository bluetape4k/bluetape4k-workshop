# dependencies-only consumer 정책

## 배경

workshop 저장소는 이전에 ecosystem BOM을 import하면서도 직접 bluetape4k artifact version
alias를 함께 가지고 있었다. BOM과 local alias가 독립적으로 드리프트할 수 있었기 때문에 release upgrade가 더 어려워졌다.

## 결정

version catalog에서 `bluetape4k-dependencies`만 bluetape4k version source로 사용한다.
bluetape4k artifact alias는 versionless로 유지해서 dependency management가 BOM에서 version을 해석하게 한다.

## 결과

catalog는 이제 직접 bluetape4k version ref를 제거하고, `bluetape4k-dependencies`를 import하며,
현재 BOM-managed Spring Boot core artifact coordinate를 사용한다.

## 검증

forbidden-reference grep, `git diff --check`, 그리고
`./gradlew :redis-redisson-examples:compileKotlin --no-daemon --no-configuration-cache`.
를 실행했다.

## 향후 지침

release-upgrade PR에서는 모듈이 의도적으로 non-BOM artifact를 소비하는 경우가 아니라면,
bluetape4k ecosystem artifact에 대해 `bluetape4k-dependencies` version만 갱신한다.
