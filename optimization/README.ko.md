# Optimization 워크숍

[English](README.md) | 한국어

`optimization/` 그룹은 애플리케이션이 소유하는 planning 및 optimization
경계를 다룹니다. 이 그룹 아래의 모든 모듈은 Java 25 toolchain을 사용합니다.
provider HTTP 호출과 JDBC 작업은 Bluetape JDK 25 virtual-thread runtime에서
실행합니다.

| 모듈 | 목적 | 인프라 |
|---|---|---|
| [`planning-contracts`](planning-contracts/) | provider-neutral planning 제출, PostgreSQL inbox/outbox 수렴, callback idempotency, 최종 aggregate version 재검증 | PostgreSQL + WireMock (Testcontainers) |

그룹 검증:

```bash
./scripts/smoke-validate.sh optimization
```

공개된 모든 Bluetape 모듈의 버전은 `bluetape4k-dependencies:1.3.1`만 관리합니다.
Optimization 예제는 개별 library BOM을 import하거나 Bluetape 모듈 버전을
직접 고정하지 않습니다.
