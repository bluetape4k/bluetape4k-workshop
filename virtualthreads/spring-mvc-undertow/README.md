# Spring Boot MVC + Virtual Threads + Undertow Example

## Claude Somma 설명

Spring Boot에서 Tomcat 대신 Undertow를 사용하고 Virtual Threads를 활성화하는 방법을 설명해드리겠습니다. 이를 위해 다음 단계를 따르세요:

1. 의존성 변경:
   `build.gradle` (Gradle) 또는 `pom.xml` (Maven) 파일에서 다음과 같이 의존성을 변경합니다.

Gradle (`build.gradle.kts`):

```kotlin
configurations {
    // Exclude Tomcat
    all {
        exclude(module = "spring-boot-starter-tomcat")
    }
}
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-undertow")
}
```

Maven (`pom.xml`):

```xml

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <exclusions>
            <exclusion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-tomcat</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-undertow</artifactId>
    </dependency>
</dependencies>
```

2. Virtual Threads 활성화:
   `application.properties` 또는 `application.yml` 파일에 다음 설정을 추가합니다.

```properties
spring.threads.virtual.enabled=true
```

또는 YAML 형식:

```yaml
spring:
    threads:
        virtual:
            enabled: true
```

3. Java 버전 확인:
   Virtual Threads를 사용하려면 Java 21 이상이 필요합니다. `build.gradle.kts`에서 Java 버전을 설정합니다:

```kotlin
java {
    sourceCompatibility = JavaVersion.VERSION_21
}
```

Maven의 경우 `pom.xml`에 다음을 추가합니다:

```xml

<properties>
    <java.version>21</java.version>
</properties>
```

4. (선택사항) Undertow 설정 최적화:
   필요에 따라 Undertow의 추가 설정을 조정할 수 있습니다. 예를 들어:

```properties
server.undertow.io-threads=16
server.undertow.worker-threads=256
server.undertow.buffer-size=1024
server.undertow.direct-buffers=true
```

이렇게 설정하면 Spring Boot 애플리케이션이 Tomcat 대신 Undertow를 사용하고 Virtual Threads를 활성화하게 됩니다. Virtual Threads를 사용하면 대규모 동시성 처리에
유리하며, Undertow는 경량 서버로 알려져 있어 성능 향상을 기대할 수 있습니다.

주의: Virtual Threads는 Java 21의 프리뷰 기능이므로, 프로덕션 환경에서 사용할 때는 주의가 필요합니다. 또한 모든 라이브러리가 Virtual Threads와 완벽하게 호환되지 않을 수 있으므로,
철저한 테스트가 필요합니다.
