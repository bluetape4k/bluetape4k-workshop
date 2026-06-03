# docker-compose-plugin example

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **docker-compose-plugin example** as a runnable Docker Compose test infrastructure workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![docker-compose-plugin example Graphviz architecture diagram](../../docs/images/readme-diagrams/docker-compose-plugin-demo-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.docker` as the source of truth when comparing this README with the code.

## Sequence Diagram

This example uses [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin) to do without testcontainers.
gradle build script
It shows how to run docker-compose with just that.

This method is most convenient when configuring and testing a custom server.

## Docker Compose Yaml file pre-validation

Check whether the yml file settings are correct.

```shell
$ docker compose -f docker-compose.yml config
$ docker compose -f docker-compose-multiple.yml config
```

Next, let's actually run the dockerized service.

```shell
$ docker compose -f docker-compose.yml up
```

```shell
$ docker compose -f docker-compose-multiple.yml up
```

## reference

* [Gradle docker-compose-plugin](https://github.com/avast/gradle-docker-compose-plugin)
* [Docker with Gradle: Getting started with Docker Compose](https://bmuschko.com/blog/gradle-docker-compose/)
