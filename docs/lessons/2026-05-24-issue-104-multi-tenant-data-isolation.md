# Issue #104 Multi-Tenant Data Isolation

## 배경

GitHub issue #104는 data access, cache key, lock, rate limit, metric 전반에서 tenant isolation을 증명하는 advanced workshop example을 요청했다.

## 결정

`spring-boot/multi-tenant-data-isolation`를 deterministic Spring Boot + H2 example로 추가한다. safe repository path에는 Bluetape4k Exposed JDBC `LongJdbcRepository`를 사용하고, lock/rate-limit example은 memory에 유지해서 lesson이 external infrastructure보다 tenant-key design에 집중하게 한다.

## 결과

이 모듈은 safe/unsafe repository/cache path, tenant-prefixed key helper, per-key lock, tenant-keyed rate limiting, tenant-tagged Micrometer counter, English/Korean README 파일을 포함한다.

## 검증

- `./gradlew :spring-boot-multi-tenant-data-isolation:test` 통과.
- `./gradlew projects`에 `:spring-boot-multi-tenant-data-isolation` 포함.
- `git diff --check` 통과.

## 향후 지침

`spring-boot/` 아래에 Spring Boot workshop module을 추가할 때 Gradle project name은 예를 들어 `:spring-boot-<module>`처럼 base directory prefix를 포함한다.
