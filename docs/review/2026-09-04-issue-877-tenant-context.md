# Issue #877 TenantContext carrier 재사용 경계 검토

## 검토 범위

- 기존 `spring-boot/multi-tenant-data-isolation` invoice 격리 예제의 실행 경계
- `bluetape4k-dependencies:2.0.0` BOM 아래 `bluetape4k-tenant`와 Reactor alias 사용
- ThreadLocal 중첩/cleanup, virtual-thread ScopedValue, Reactor scheduler hop/cancellation

## 결정

예제는 blocking 요청에 `ThreadLocalTenantContext`, JDK 25 virtual thread에
`ScopedValueTenantContext`, Reactor publisher에 immutable `ReactorTenantContext`를
연결한다. 중첩 scope는 이전 값을 복원하고 실패·취소 뒤 binding을 남기지 않는다.
누락 tenant는 `MissingTenantContextException`으로 거부하며 metric에는 raw tenant
대신 bounded fingerprint만 노출한다. 실제 인증/filter 연동과 분산 transaction/schema
정책은 범위 밖으로 남긴다.

## 검증 증거

- module targeted/full tests와 root build/Detekt 통과
- tenant dependency insight가 `2.0.0`을 해석하고 SNAPSHOT을 포함하지 않음
- README parity/language, stale-check, actionlint, `git diff --check` 통과
- PR #928이 `develop`을 대상으로 하며 이 scope의 expected head ref를 사용
