# Gatling Load Testing Tutorial for Kotlin

원본: [github: mdportnov/kotlin-gatling-tutorial](https://github.com/mdportnov/kotlin-gatling-tutorial)

원본에서는 MySQL 를 사용하는 데, 여기서는 편의를 위핸 Testcontainers + MongoDB 를 사용합니다.

This repository contains the code examples and resources for the
article ["Gatling Load Testing Tutorial"](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9),
which provides
an introduction to Gatling, a popular open-source load testing tool for web applications.

```mermaid
flowchart TD
    subgraph 앱["Spring Boot 애플리케이션 (포트 8080)"]
        subgraph 동기["Sync (Virtual Thread)"]
            SC["SyncTaskController\nGET /sync/{id}"]
            SS["SyncTaskService"]
        end
        subgraph 비동기["Async (Coroutine)"]
            AC["AsyncTaskController\nGET /async/{id}"]
            AS["AsyncTaskService"]
        end
    end

    subgraph 시뮬레이션["Gatling 시뮬레이션"]
        SyncSim["SyncTaskSimulation\nrampConcurrentUsers 10→20 / 10초"]
        AsyncSim["AsyncTaskSimulation\nrampConcurrentUsers 10→20 / 10초"]
    end

    Report["결과 리포트\nbuild/reports/gatling"]

    SyncSim -->|"GET /sync/1, /sync/2"| SC
    AsyncSim -->|"GET /async/1, /async/2"| AC
    SC --> SS
    AC --> AS
    시뮬레이션 --> Report
```

### Running the Examples

To run the examples in this repository, you will need to have Gradle installed on your system. Once you have Gradle
installed, you can clone this repository to your local system:

The repository includes several examples of Gatling load testing scenarios, located in the _src/gatling/kotlin_
directory. To run an example, run the following command:

`./gradlew bootRun`

then

`./gradlew gatlingRun`

This will run the Gatling simulation script and generate a report in the build/reports/gatling directory.

### Contributing

If you have suggestions or improvements for the examples in this repository, feel free to submit a pull request. Please
include a description of the changes and the rationale for the changes.

### Resources

For more information on Gatling, see the following resources:

* [Gatling official website](https://gatling.io/)
* [Gatling documentation](https://gatling.io/docs/)
* [Gatling community resources](https://gatling.io/community/)

### Simulation Results

#### Sync Task Simulation

![Sync Task Simulation Results](./doc/sync-task-simulation.png)
![Sync Task Simulation Results RPS](./doc/sync-task-simulation-rps.png)

#### Async Task Simulation

![Aync Task Simulation Results](./doc/async-task-simulation.png)
![Aync Task Simulation Results RPS](./doc/async-task-simulation-rps.png)
