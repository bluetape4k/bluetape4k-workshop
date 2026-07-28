# exposed-domain JDBC 헬퍼 테스트 의존성

## Context

`exposed-domain` 테스트는 `bluetape4k-exposed-jdbc-tests`에도 의존하면서
`io.bluetape4k.exposed.jdbc.selectImplicitAll`을 import한다.

## Decision or Finding

`*-jdbc-tests` 아티팩트는 공유 테스트 계약과 픽스처를 제공하지만, JDBC
헬퍼 extension을 소유한 main `bluetape4k-exposed-jdbc` 아티팩트를 대체하지
않는다.

## Outcome

version catalog는 이제 `bluetape4k-exposed-jdbc`를 노출하고,
`exposed-domain`은 `bluetape4k-exposed-jdbc-tests`와 함께 이를 테스트
의존성으로 선언한다.

## Verification

실행:

```bash
./gradlew :exposed-domain:compileTestKotlin --continue
```

## Future Guidance

workshop 테스트가 bluetape4k 모듈의 production 헬퍼 extension을 import하면
production 아티팩트를 명시적으로 선언한다. `*-tests` 아티팩트가 production
헬퍼를 transitively export한다고 가정하지 않는다.
