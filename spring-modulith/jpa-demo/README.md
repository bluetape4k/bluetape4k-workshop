# Modulith with Spring Boot Demo Project [![Twitter](https://img.shields.io/twitter/follow/piotr_minkowski.svg?style=social&logo=twitter&label=Follow%20Me)](https://twitter.com/piotr_minkowski)

원본: [sample-spring-modulith](https://github.com/piomin/sample-spring-modulith )

## 참고 자료

- [Spring Modulith 공식 문서](https://docs.spring.io/spring-modulith/reference/index.html)
- [Understanding Spring Modulith: A Detailed Guide](https://getlearntech.com/spring-modulith/)
- [Spring Modulith at VLog](https://velog.io/@gehwan96/Spring-Modulith)

## TODO

- [ ] `spring-modulith-events-jdbc`, `spring-modulith-events-jpa` 를 대신해 `spring-modulith-events-exposed`
  제작하기 ([Spring Modulith 를 Exposed 기반으로 사용하기 위한 모듈 제작 #25](https://github.com/bluetape4k/bluetape4k-projects/issues/25))

[![CircleCI](https://circleci.com/gh/piomin/sample-spring-modulith.svg?style=svg)](https://circleci.com/gh/piomin/sample-spring-modulith)

[![SonarCloud](https://sonarcloud.io/images/project_badges/sonarcloud-black.svg)](https://sonarcloud.io/dashboard?id=piomin_sample-spring-modulith)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=piomin_sample-spring-modulith&metric=bugs)](https://sonarcloud.io/dashboard?id=piomin_sample-spring-modulith)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=piomin_sample-spring-modulith&metric=coverage)](https://sonarcloud.io/dashboard?id=piomin_sample-spring-modulith)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=piomin_sample-spring-modulith&metric=ncloc)](https://sonarcloud.io/dashboard?id=piomin_sample-spring-modulith)

In this project, I'm demonstrating how to implement the modulith app using Spring support. Here are
the [docs](https://docs.spring.io/spring-modulith) about the project.

1. How to organize the SpringBoot in modular way and use Spring Modulith to simplify and verify the app
   structure: [Guide to Modulith with Spring Boot](https://piotrminkowski.com/2023/10/13/guide-to-modulith-with-spring-boot/)

## Architecture

Our sample app is divided into 4 logical modules:

- **department** - manage `Department` entity
- **employee** - manage `Employee` entity
- **gateway** - expose internal modules over REST API
- **organization** - manage `Organization` entity

The following picture illustrates the architecture described above.

<img src="https://i0.wp.com/piotrminkowski.com/wp-content/uploads/2023/10/Screenshot-2023-10-11-at-13.33.13.png" title="Architecture"><br/>

## Running

You need to have JDK17+ and Maven. Also run Docker on your machine to enable Zipkin container.
Then just run the app with the following command:

```shell
$ mvn spring-boot:run
```
