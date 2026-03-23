# docker-compose-plugin example

이 예제는 [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin) 을 사용하여 testcontainers 없이도
gradle build script
만으로 docker-compose 를 실행하는 방법을 보여줍니다.

```mermaid
flowchart TD
    subgraph Gradle["Gradle 빌드"]
        Plugin["avast/gradle-docker-compose-plugin"]
        ComposeUp["composeUp 태스크"]
        Test["test 태스크"]
        ComposeDown["composeDown 태스크"]
    end

    subgraph 컴포즈파일["docker-compose 파일"]
        Single["docker-compose.yml\n(단일 서비스)"]
        Multi["docker-compose-multiple.yml\n(다중 서비스)"]
        PGFile["docker-compose-postgres.yml"]
        RedisFile["docker-compose-redis.yml"]
    end

    subgraph 컨테이너["실행 컨테이너"]
        ES["Elasticsearch 7\n(포트 9200)"]
        ES2["Elasticsearch 8\n(포트 9200)"]
        PG["PostgreSQL\n(포트 5432)"]
        Redis["Redis\n(포트 6379)"]
    end

    Plugin --> ComposeUp
    ComposeUp -->|"테스트 전 자동 실행"| Test
    Test -->|"테스트 후 자동 정리"| ComposeDown

    Single --> ES
    Multi --> ES2
    PGFile --> PG
    RedisFile --> Redis
```

Custom Server를 구성하고, 테스트 할 때에는 이 방식이 가장 편리합니다.

## Docker Compose Yaml 파일 사전 검증

yml 파일 설정이 제대로 되었는지 확인 합니다.

```shell
$ docker compose -f docker-compose.yml config
$ docker compose -f docker-compose-multiple.yml config
```

다음으로 실제로 dockerized 서비스를 실행해 봅니다.

```shell
$ docker compose -f docker-compose.yml up
```

```shell
$ docker compose -f docker-compose-multiple.yml up
```

## 참고

* [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin)
* [Docker with Gradle: Getting started with Docker Compose](https://bmuschko.com/blog/gradle-docker-compose/)
