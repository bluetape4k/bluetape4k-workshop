# docker-compose-plugin example

[English](README.md) | 한국어

## 이 모듈에서 확인할 내용

이 모듈은 Gradle
[`docker-compose-plugin`](https://github.com/avast/gradle-docker-compose-plugin)이
테스트 인프라 lifecycle을 소유하는 방식을 보여줍니다. `test` task는
`dockerCompose`에 의존하고, 모듈-local Compose 파일에서 Redis와 PostgreSQL을
시작한 뒤 mapped host/port 값을 환경 변수와 JVM system property로 노출합니다.

현재 테스트는 Redisson으로 `redis.url`을 사용합니다. `postgres.url`도 JDBC
consumer를 위해 함께 게시되지만, 이 테스트가 직접 검증하는 대상은 Redis입니다.

## 아키텍처

![docker-compose-plugin example architecture diagram](../../docs/images/readme-diagrams/docker-compose-plugin-demo-readme-architecture-01.png)

## 런타임 흐름

![docker-compose-plugin example sequence diagram](../../docs/images/readme-diagrams/docker-compose-plugin-demo-readme-sequence-01.png)

## Compose 파일

| Compose file | `dockerCompose` 사용 | Services |
|---|---:|---|
| `docker-compose.yml` | yes | Redis `6379` |
| `docker-compose-postgres.yml` | yes | PostgreSQL `5432` |
| `docker-compose-redis.yml` | no | Standalone Redis file |
| `docker-compose-multiple.yml` | no | Elasticsearch `9200` |

## 사용법

```bash
./gradlew :docker-compose-plugin-demo:test
```

## Docker Compose YAML 사전 검증

Gradle test task를 실행하기 전에 파일 문법을 확인하고 싶을 때 Docker Compose를
직접 실행합니다.

```shell
$ docker compose -f docker-compose.yml config
$ docker compose -f docker-compose-multiple.yml config
```

Gradle 밖에서 Compose 동작을 디버깅할 때는 서비스를 수동으로 띄울 수 있습니다.

```shell
$ docker compose -f docker-compose.yml up
```

```shell
$ docker compose -f docker-compose-multiple.yml up
```

## 참고 자료

* [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin)
* [Docker with Gradle: Getting started with Docker Compose](https://bmuschko.com/blog/gradle-docker-compose/)
