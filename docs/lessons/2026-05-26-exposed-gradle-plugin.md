## 배경

main source에서 Exposed table을 정의하는 workshop module에 JetBrains Exposed Gradle plugin을 도입했다.

## 결정

workshop repository는 managed `bt4k` catalog와 독립적으로 유지한다. Gradle plugin에는 repo-local Exposed version alias를 사용하고, `bluetape4k-dependencies`는 계속 BOM으로 소비한다.

## 결과

주요 Exposed workshop module은 이제 module-local table package와 H2 migration database setting으로 `generateMigrations`를 노출한다.

## 검증

`git diff --check`, `./gradlew -q help`, `:exposed-mvc-jdbc:tasks --all`를 실행했다.

## 향후 보호 장치

workshop repository가 의도적으로 managed library repo로 승격되는 경우가 아니라면 `bluetape4kDependenciesCatalogRef`를 추가하지 않는다.
