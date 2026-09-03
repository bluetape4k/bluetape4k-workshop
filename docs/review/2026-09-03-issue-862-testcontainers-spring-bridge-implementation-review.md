# Issue #862 Testcontainers Spring bridge 구현 검토

## 검토 범위와 판정

- 대상: `shared` Redis 테스트 지원과 `spring-data/redis-examples` 소비자에
  `bluetape4k-testcontainers-spring:2.0.0` bridge를 적용한 구현
- 기준 head: `ca297aac707659feacdd91b9723dd6e79d088282`
- 검토일: 2026-09-03
- 최종 판정: **P0=0, P1=0. 로컬 구현·검증 완료; hosted CI delivery gate 대기**

## 검토 결과

| 관점 | 근거 | 판정 |
| --- | --- | --- |
| API 계약 | `RedisTestSupport.registerRedisProperties`가 upstream `PropertyExportingServer.registerDynamicProperties`에 위임하고, 계약 테스트가 `testcontainers.redis.{host,port,url}` 키와 lazy supplier 재평가를 검증한다. | PASS |
| 의존성 | catalog alias는 versionless이며 `bluetape4k-dependencies:2.0.0` BOM이 `bluetape4k-testcontainers-spring:2.0.0`을 선택한다. `2.1.0`/SNAPSHOT 활성 참조는 없다. | PASS |
| 테스트 경계 | Docker-free bridge 계약 11개와 source guard를 별도 selector로 실행하고, shared 전체 54개 및 Redis consumer 39개(1 skip)를 Docker 환경에서 재검증했다. | PASS |
| 운영/CI | `Examples.yml`과 `scripts/smoke-validate.sh`가 Docker-free 계약, shared 전체, Redis consumer 전체를 분리한다. ecosystem reuse checker가 PR 변경 경로를 이 scope에 결속하도록 fresh manifest scope를 추가한다. | PASS |
| 문서 | English/Korean README와 KDoc이 lazy property export, lifecycle ownership, Docker 경계를 같은 명령·경로로 설명한다. | PASS |

## 잔여 위험과 범위

- Docker-backed 검증은 로컬 Colima와 hosted CI에서 수행되며, Docker-free 계약은
  Docker daemon 없이도 실행할 수 있다.
- upstream bridge 구현 자체나 전 모듈 자동 설정으로 범위를 확장하지 않았다.
- 이 문서는 구현 범위의 독립 검토 증거이며, merge 전에는 현재 PR head의 hosted
  CI, review, mergeability를 다시 확인해야 한다.

## Hosted CI 실패와 보정

초기 hosted `Container Examples (sequential)` 실행은 기존 `shared/web` 테스트가
요구하는 `bluetape4k/mock-web-server:2.0.0` 이미지를 runner에서 찾지 못해
HTTP 404로 실패했다. 같은 3개 초기화 실패는 이전 PR head에서도 재현되어
Issue #862 bridge 구현과 무관한 CI fixture 준비 누락으로 분류했다.

Nightly workflow가 사용하는 upstream `bluetape4k-projects` checkout과 Jib
`bluetape4k-mock-web-server`/`bluetape4k-mock-webflux-server` local image build를
Container lane 앞에 추가했다. 두 image 모두 `baseVersion=2.0.0` tag를 생성하므로
consumer test가 요구하는 stable image를 별도 registry 인증 없이 사용한다.
