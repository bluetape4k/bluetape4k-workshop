# Docker README Diagram Refresh

## 배경

`docker/compose-demo`는 source가 이미 유용한 reader contract를 명확히 보여주고
있었음에도 Graphviz 표현과 README `FIXME`를 여전히 노출하고 있었다. contract는 각
JUnit test가 Testcontainers `DockerComposeContainer`를 통해 module-local Docker Compose
file을 load한다는 것이다.

## 결정

source file에서 active Compose service를 직접 설명한다. `docker-compose-redis.yml`,
`docker-compose-postgres.yml`, `docker-compose-multiple.yml`을 서로 다른 service set으로
보여주고, `multiple.yml`의 주석 처리된 Redis branch는 single-service test의 active
Redis와 시각적으로 구분한다.

Docker/Testcontainers, Redis, PostgreSQL, Elasticsearch에는 service icon card를 사용한다.
README asset 옆에 stale alternative를 남기지 말고, module pass에서 Graphviz asset과
old non-README diagram을 제거한다.

## 결과

README는 이제 이 모듈을 generic test infrastructure slice가 아니라 Compose service
exposure example로 제시한다. sequence diagram은 실제 `DockerComposeContainer` lifecycle을
보여준다. file load, exposed service 선언, listening port 대기, mapped port resolve,
client behavior assertion 순서다.

`docker/compose-plugin-demo`의 유용한 contract는 다르다. Gradle이 `dockerCompose`를
통해 Compose lifecycle을 소유한 뒤, service host/port data를 test JVM에 노출한다.
Redis/PostgreSQL은 wired test service로 유지하고, Elasticsearch는 `useComposeFiles`의
일부가 아닐 때만 present compose file로 보여준다.

## 검증

- README, Kotlin test, Gradle dependency, 모든 compose file을 읽었다.
- SVG diagram을 CairoSVG로 PNG로 렌더링했다.
- rendered architecture와 sequence를 contact sheet로 시각 검사했다.
- README image link, Graphviz reference, SVG XML, `git diff --check`를 확인했다.

## 향후 지침

user-facing README에 `FIXME`나 work-log language를 남기지 않는다. legacy API가
신뢰하기 어렵지만 이해를 위해 여전히 문서화한다면, maintained alternative와 해당 예제가
여전히 가르치는 source-backed contract를 설명한다.
