# Chaos Monkey + Spring Boot 4 Demo

[한국어](README.ko.md) | English

This module runs a small Student CRUD API with Chaos Monkey for Spring Boot enabled. It is useful for seeing which Spring beans are watched and how latency, exception, or kill assaults affect ordinary controller/service/repository calls.

## Architecture

![Chaos Monkey architecture](../../docs/images/readme-diagrams/spring-boot-chaos-monkey-readme-architecture-01.png)

The application enables the `chaos-monkey` profile in `application.properties`. Watchers are enabled for controller, REST controller, service, and repository beans. The student data path is intentionally simple: `StudentController` → `StudentService` → `StudentJdbcRepository` → H2 `student` table.

## Assault Flow

![Chaos Monkey assault sequence](../../docs/images/readme-diagrams/spring-boot-chaos-monkey-readme-assault-sequence-01.png)

Chaos Monkey can delay or fail watched method calls before the normal student API finishes. The default configuration enables latency assaults with a 10-15 second range and exposes the actuator endpoints for runtime inspection and changes.

## Main Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/students` | List students from H2 |
| `GET` | `/students/{id}` | Load one student |
| `POST` | `/students` | Insert a student |
| `PUT` | `/students/{id}` | Update a student |
| `DELETE` | `/students/{id}` | Delete a student |
| `GET` | `/actuator/chaosmonkey` | Inspect Chaos Monkey state |
| `POST` | `/actuator/chaosmonkey/enable` | Enable assaults |
| `POST` | `/actuator/chaosmonkey/disable` | Disable assaults |
| `GET` | `/actuator/chaosmonkey/assaults` | Inspect assault settings |

## Configuration Highlights

```properties
spring.profiles.active=chaos-monkey
chaos.monkey.enabled=true
chaos.monkey.assaults.level=5
chaos.monkey.assaults.latencyActive=true
chaos.monkey.assaults.latencyRangeStart=10000
chaos.monkey.assaults.latencyRangeEnd=15000
chaos.monkey.watcher.controller=true
chaos.monkey.watcher.restController=true
chaos.monkey.watcher.service=true
chaos.monkey.watcher.repository=true
management.endpoints.web.exposure.include=*
```

## Run

```bash
./gradlew :spring-boot:chaos-monkey:bootRun
curl http://localhost:8080/students
curl http://localhost:8080/actuator/chaosmonkey
```

Run the focused controller tests:

```bash
./gradlew :spring-boot:chaos-monkey:test
```

## References

- [Chaos Monkey for Spring Boot](https://codecentric.github.io/chaos-monkey-spring-boot/)
- [Principles of Chaos Engineering](https://principlesofchaos.org/)
