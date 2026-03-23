Gatling plugin for Gradle - Kotlin demo project
===============================================

A simple showcase of a Gradle project using the Gatling plugin for Gradle. Refer to the plugin documentation
[on the Gatling website](https://gatling.io/docs/current/extensions/gradle_plugin/) for usage.

```mermaid
flowchart TD
    subgraph 시뮬레이션["ComputerDatabaseSimulation"]
        Users["시나리오: Users\n(search → browse)"]
        Admins["시나리오: Admins\n(search → browse → edit)"]
    end

    subgraph 시나리오단계["시나리오 단계"]
        Search["search\nHome → Feed → Select"]
        Browse["browse\nPage 0~3 반복"]
        Edit["edit\nForm → Post (tryMax 2)"]
    end

    subgraph 주입["부하 주입 (rampUsers)"]
        U10["Users 10명 / 10초"]
        A2["Admins 2명 / 10초"]
    end

    Target["대상 서버\ncomputer-database.gatling.io"]
    Report["결과 리포트\nbuild/reports/gatling"]

    Users --> Search
    Users --> Browse
    Admins --> Search
    Admins --> Browse
    Admins --> Edit

    U10 --> Users
    A2 --> Admins

    시뮬레이션 --> Target
    Target --> Report
```

This project is written in Kotlin, others are available
for [Java](https://github.com/gatling/gatling-gradle-plugin-demo-java)
and [Scala](https://github.com/gatling/gatling-gradle-plugin-demo-scala).

It includes:

* Gradle Wrapper, so you don't need to install Gradle (a JDK must be installed and $JAVA_HOME configured)
* minimal `build.gradle.kts` leveraging Gradle wrapper
* latest version of `io.gatling.gradle` plugin applied
* sample [Simulation](https://gatling.io/docs/gatling/reference/current/general/concepts/#simulation) class,
  demonstrating sufficient Gatling functionality
* proper source file layout

## Results

The simulation generates a report in the `build/reports/gatling` directory. The report is an HTML file that can be

![Computer Database Simulation](doc/ComputerDatabaseSimulation.png)
![Computer Database Simulation RPS](doc/ComputerDatabaseSimulation_rps.png)
