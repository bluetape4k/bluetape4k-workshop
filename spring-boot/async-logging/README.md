# Async Logging Demo

Logback 으로 로그를 출력할 때, `AsyncAppender` 를 사용하여 로그를 비동기로 출력하는 방법에 대한 예제입니다.

## Console 로깅

로그를 일반적인 Console 에 출력할 때에는 다음과 같이 `ConsoleAppender` 를 사용합니다.

```xml
<!-- You can override this to have a custom pattern -->
<!-- Spring Boot3 에서 TraceId, dd.trace_id 가 다르게 나오는 것은 dd-java-agent 에 버그가 있어서이다. -->
<!-- https://github.com/DataDog/dd-trace-java/issues/6307 -->
<property name="CONSOLE_LOG_PATTERN"
          value="%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %highlight(${LOG_LEVEL_PATTERN:-%5p}) [%X{traceId}]  [%X{spanId}] DD [%X{dd.trace_id}] [%X{dd.span_id}] %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}"/>

        <!-- Appender to log to Console -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
<filter class="ch.qos.logback.classic.filter.ThresholdFilter">
    <!-- Minimum logging level to be presented in the console logs-->
    <level>DEBUG</level>
</filter>
<encoder>
    <pattern>${CONSOLE_LOG_PATTERN}</pattern>
    <charset>utf8</charset>
</encoder>
</appender>
        <!-- formatter:on -->
```

이를 비동기 방식으로 출력하기 위해서는 `AsyncAppender` 를 사용하고, `appender-ref` 를 ConsoleAppender 로 설정합니다.

```xml

<appender name="ASYNC_CONSOLE" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="CONSOLE"/>
    <queueSize>10</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <includeCallerData>true</includeCallerData>
    <neverBlock>false</neverBlock>
</appender>
```

## 파일 로깅

보통 Application 에서는 로그를 파일로 출력하도록 설정하는데, 이를 비동기로 출력하게 되면, 실제 Application 작업에 방해되지 않도록 하고,
로그 출력의 부담을 줄일 수 있게 되어, Application 성능 향상에 도움이 됩니다.

여기서는 Rolling File로 로그를 출력하여, 일 단위로 로그 파일을 교체하도록 합니다.
이를 비동기로 출력하는 하도록 `ASYNC_ROLLING_FILE` Appender 를 설정합니다.

혹시, Application 부하가 커서, 로그 출력이 지연되어서 로그를 유실할 수도 있습니다만, 이를 위해 `queueSize` 를 키우고, `discardingThreshold` 를 0 으로 설정하여, 로그를
유실하지 않도록 합니다.
또한 `neverBlock` 를 false 로 설정하여, Application이 바쁠 때에는 로그 출력을 블록되도록 합니다.

```xml
<!-- 로그 정보를 시간 단위로 파일로 저장하는 ㅁppender 입니다. -->
<appender name="ROLLING_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_PATH}/${LOG_FILE}</file>
    <!-- Log 정보를 파일로 저장 시, JSON 포맷으로 변경하기 위해 logstash 를 사용합니다. -->
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    <!-- Rolling file policy -->
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>${LOG_PATH}/app.%d{yyyy-MM-dd}.%d.log</fileNamePattern>
        <maxHistory>30</maxHistory>
        <totalSizeCap>10MB</totalSizeCap>
    </rollingPolicy>
</appender>

        <!-- 비동기로 롤링 파일에 로그를 출력하는 Appender 입니다 -->
<appender name="ASYNC_ROLLING_FILE" class="ch.qos.logback.classic.AsyncAppender">
<appender-ref ref="ROLLING_FILE"/>
<queueSize>512</queueSize>
<discardingThreshold>0</discardingThreshold>
<includeCallerData>true</includeCallerData>
<neverBlock>false</neverBlock>
</appender>
```

## Spring Boot Profile 별 설정

Spring Boot Profile 별로 Appender 지정, 로그 레벨 설정 등을 다르게 할 수 있습니다.
이렇게 하면, 개발 환경에서는 로그를 상세하게 출력하고, 운영 환경에서는 로그를 간단하게 출력하도록 설정할 수 있습니다.

```xml

<springProfile name="dev">
    <logger name="io.bluetape4k.workshop" level="TRACE"/>
    <root level="DEBUG">
        <appender-ref ref="ASYNC_CONSOLE"/>
        <appender-ref ref="ASYNC_ROLLING_FILE"/>
        <appender-ref ref="ASYNC_SLACK"/>
    </root>
</springProfile>

<springProfile name="test">
<logger name="io.bluetape4k.workshop" level="DEBUG"/>
<root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="ASYNC_ROLLING_FILE"/>
    <appender-ref ref="ASYNC_SLACK"/>
</root>
</springProfile>

<springProfile name="prod">
<root level="INFO">
    <appender-ref ref="ASYNC_ROLLING_FILE"/>
    <appender-ref ref="ASYNC_SLACK"/>
</root>
</springProfile>
```

## Spring Boot - Graceful Shutdown 설정

비동기 로깅의 가장 큰 단점은 로그를 유실할 수 있는 가능성이 높다는 것입니다.
특히 Application 이 예기치 않게 중단될 경우에는 버퍼에 쌓은 로그가 출력되지 않아, 문제 파악이 어려울 수 있습니다.

이를 대비하기 위해서는 Spring Boot Application의 경우 다음과 같이 Graceful Shutdown 을 설정하여, Application 종료 시에도 로그를 출력할 수 있도록 합니다.

`application.yml` 파일에 다음과 같이 설정합니다.

```yaml
spring:
    lifecycle:
        timeout-per-shutdown-phase: 10s     # Graceful Shutdown 시간

server:
    shutdown: graceful  # WebServer Graceful shutdown (https://www.baeldung.com/spring-boot-web-server-shutdown)
```

## 참고

- [Logback - AsyncAppender](http://logback.qos.ch/manual/appenders.html#AsyncAppender)
- [Logback - RollingFileAppender](https://dennis-xlc.gitbooks.io/the-logback-manual/content/en/chapter-4-appenders/logback-core/rollingfileappender/timebasedrollingpolicy.html)
