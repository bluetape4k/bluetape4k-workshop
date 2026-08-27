# Issue #577 FastFory BOM 게이트 교훈

## 결정

`bluetape4k-dependencies` BOM의 최신 live metadata와 로컬 Gradle 해석 결과를
먼저 대조한 뒤, 현재 노출된 API 범위 안에서만 Redis 예제의 serializer와
Redisson codec을 `LZ4FastFory` 계열로 전환했다. 개별 Bluetape 모듈 버전을
고정하지 않고 consumer 프로젝트의 BOM 규칙을 그대로 유지했다.

확인된 해석 결과는 BOM `1.4.0`과 `bluetape4k-redis`, `bluetape4k-redisson`,
`bluetape4k-spring-boot-redis` `1.12.1`이다. 해당 버전에서 확인한 표면은
`RedisBinarySerializers.LZ4FastFory`, `RedissonCodecs.LZ4FastFory`,
`LZ4FastForyComposite`이며, 이 표면에 없는 별도 codec 이름은 추가하지 않았다.

## 사용 경계

FastFory는 `SCHEMA_CONSISTENT` 계약을 전제로 한다. 기존 기본 Fory wire
형식과 호환된다고 가정하지 않으며, durable/shared persisted data나 장기간
보존 캐시의 포맷을 교체하는 용도로 확대하지 않는다. 이번 변경은 volatile
cache와 예제 범위에 한정하고, 장기 저장 데이터는 명시적인 migration/version
계약 없이는 FastFory로 읽거나 쓰지 않는다.

## 검증 영수증

- Maven metadata에서 BOM 최신/release를 확인하고, `dependencies` configuration
  의존성 그래프로 실제 resolved version을 확인했다.
- 다음 컴파일 게이트가 통과했다.

  ```text
  ./gradlew :spring-data-redis-examples:compileKotlin \\
    :spring-data-redis-examples:compileTestKotlin \\
    :redis-redisson-examples:compileTestKotlin \\
    --max-workers=1 --no-build-cache --console=plain
  BUILD SUCCESSFUL
  ```

- Redis application, Redisson test/config/example, README 양쪽의 해당 참조를
  같은 FastFory 경계로 맞췄다.

## 미래 guard

- `bluetape4k-dependencies` BOM을 우회해 개별 Bluetape 버전을 추가하지 않는다.
- FastFory를 durable/shared cache에 적용하기 전에는 schema migration, dual-read,
  rollback 계약과 wire-compatibility 근거를 별도로 작성한다.
- BOM/API가 변경되면 compileClasspath와 실제 serializer/codec symbol을 다시
  확인하고, 이름만 비슷한 codec을 추측해 추가하지 않는다.
