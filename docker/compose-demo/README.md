# docker compose-demo

[한국어](README.ko.md) | English

## What This Module Shows

This module demonstrates Testcontainers' `DockerComposeContainer` against
module-local Compose files. Each test loads a `docker/docker-compose-*.yml`
file, declares the service name and port with `withExposedService`, waits for the
service to listen, then checks the mapped host port or client behavior.

Use `docker-compose-plugin` for new Compose-driven tests when you need the
maintained Docker Compose workflow. This module remains useful for understanding
the legacy `DockerComposeContainer` contract and its local troubleshooting
points.

## Architecture

![docker compose-demo architecture diagram](../../docs/images/readme-diagrams/docker-compose-demo-readme-architecture-01.png)

The active service set is source-backed by the three Compose files:

| Test | Compose file | Active services |
|---|---|---|
| `DockerComposeRedisTest` | `docker-compose-redis.yml` | Redis `6379` |
| `DockerComposePostgresTest` | `docker-compose-postgres.yml` | PostgreSQL `5432` |
| `MultipleServiceTest` | `docker-compose-multiple.yml` | Elasticsearch `9200`, PostgreSQL `5432` |

`docker-compose-multiple.yml` contains a commented Redis service, and the Redis
test path in `MultipleServiceTest` is disabled. Keep that distinction visible
when changing the example.

## Runtime Flow

![docker compose-demo sequence diagram](../../docs/images/readme-diagrams/docker-compose-demo-readme-sequence-01.png)

## Usage

```bash
./gradlew :docker-compose-demo:test
```

## Troubleshooting

### Q. `Container startup failed for image alpine/socat:1.7.4.3-r0` When an exception occurs

This can happen when the Docker Compose module uses an old `alpine/socat` image
without an arm64 platform. Add a newer socat image to
`~/.testcontainers.properties`.

```shell
$ grep socat ~/.testcontainers.properties
socat.container.image=alpine/socat:latest
```

### Q. When using an image that is only supported by linux/amd64 architecture

Keep the JNA dependencies in `build.gradle.kts` when running amd64-only Docker
images on Apple Silicon.

```kotlin
testImplementation(Libs.jna)
testImplementation(Libs.jna_platform)
```

## References

* [Docker Compose Module](https://www.testcontainers.org/modules/docker_compose/)
* [How to run Docker Compose with Testcontainers](https://codeal.medium.com/how-to-run-docker-compose-with-testcontainers-7d1ba73afeeb)
* [Simple and Powerful Integration Tests with Gradle and Docker-Compose](https://codeal.medium.com/guide-simple-and-powerful-integration-tests-with-gradle-and-docker-compose-7a27bd06a0cd)
