# Exposed JDBC Helper Dependency Lessons

## Context

daily bug scan에서 `bluetape4k-workshop` develop 변경을 6-Tier로 재검토했다. 최근 dependency alignment는 `exposed/domain`에 `bluetape4k-exposed-dao`를 추가했지만, 기존 test source는 `io.bluetape4k.exposed.jdbc.selectImplicitAll` extension을 계속 사용하고 있었다.

## Decision or Finding

`exposed/domain:compileTestKotlin`은 `io.bluetape4k.exposed.jdbc.selectImplicitAll` import를 해석하지 못해 실패했다. `bluetape4k-exposed-jdbc-tests`는 test fixture 계약을 제공하지만, main JDBC helper extension artifact를 대신하지 않는다.

## Outcome

- `gradle/libs.versions.toml`에 `bluetape4k-exposed-jdbc` alias를 추가했다.
- `exposed/domain` test dependency에 `libs.bluetape4k.exposed.jdbc`를 추가해 `selectImplicitAll` helper를 명시적으로 가져오도록 했다.

## Verification

- 실패 재현: `./gradlew :exposed-domain:compileTestKotlin --no-configuration-cache --stacktrace`
- 수정 후 통과: `repo-test-summary -- ./gradlew :exposed-domain:compileTestKotlin :exposed-sql-web-virtualthread:testClasses --continue`

## Future Guidance

- `bluetape4k-exposed-dao`를 추가해도 JDBC DSL helper까지 자동으로 들어온다고 가정하지 않는다.
- `bluetape4k-exposed-jdbc-tests`와 `bluetape4k-exposed-jdbc`는 역할이 다르다. test source가 `io.bluetape4k.exposed.jdbc.*` main helper를 import하면 `bluetape4k-exposed-jdbc` dependency를 명시한다.
