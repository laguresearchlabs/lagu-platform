# Monorepo Setup

## Target Structure

```
lagu-platform/
├── apps/
│   ├── metadata-service/
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   ├── record-service/
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   ├── workflow-service/
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   ├── search-service/
│   │   ├── src/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   └── automation-service/
│       ├── src/
│       ├── Dockerfile
│       └── build.gradle.kts
├── libs/
│   ├── common/
│   │   └── build.gradle.kts
│   ├── events/
│   │   └── build.gradle.kts
│   └── security/
│       └── build.gradle.kts
├── docker-compose.yml
├── settings.gradle.kts
├── build.gradle.kts           (root — version catalog + common config)
└── gradle/
    └── libs.versions.toml     (version catalog)
```

---

## settings.gradle.kts

```kotlin
rootProject.name = "lagu-platform"

include(
    "libs:common",
    "libs:events",
    "libs:security",
    "apps:metadata-service",
    "apps:record-service",
    "apps:workflow-service",
    "apps:search-service",
    "apps:automation-service"
)
```

---

## gradle/libs.versions.toml (Version Catalog)

```toml
[versions]
spring-boot          = "4.1.0"
spring-dependency-mgmt = "1.1.7"
spring-cloud         = "2026.0.0"          # verify latest compatible with Boot 4.x
lombok               = "1.18.38"
mapstruct            = "1.6.3"
springdoc            = "3.0.0"
logstash-logback     = "8.0"
flyway               = "11.0.0"
testcontainers       = "1.21.0"

[libraries]
spring-boot-web              = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-data-jpa         = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-security         = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-boot-validation       = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-actuator         = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-data-redis       = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-kafka            = { module = "org.springframework.kafka:spring-kafka" }
spring-cloud-eureka-client   = { module = "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client" }
postgresql                   = { module = "org.postgresql:postgresql" }
flyway-core                  = { module = "org.flywaydb:flyway-core" }
flyway-postgresql            = { module = "org.flywaydb:flyway-database-postgresql" }
lombok                       = { module = "org.projectlombok:lombok", version.ref = "lombok" }
mapstruct                    = { module = "org.mapstruct:mapstruct", version.ref = "mapstruct" }
mapstruct-processor          = { module = "org.mapstruct:mapstruct-processor", version.ref = "mapstruct" }
springdoc-openapi            = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
micrometer-prometheus        = { module = "io.micrometer:micrometer-registry-prometheus" }
logstash-logback             = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstash-logback" }
jackson-databind             = { module = "com.fasterxml.jackson.core:jackson-databind" }
jackson-datatype-jsr310      = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" }
testcontainers-junit         = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgresql    = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-kafka         = { module = "org.testcontainers:kafka", version.ref = "testcontainers" }

[plugins]
spring-boot              = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-mgmt   = { id = "io.spring.dependency-management", version.ref = "spring-dependency-mgmt" }
```

---

## Root build.gradle.kts

```kotlin
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.mgmt) apply false
}

subprojects {
    apply(plugin = "java")

    group = "com.lagu.platform"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }

    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

---

## libs/common/build.gradle.kts

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(rootProject.libs.jackson.databind)
    api(rootProject.libs.jackson.datatype.jsr310)
    api(rootProject.libs.spring.boot.validation)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
```

Provides: `PageResult<T>`, `ApiError`, `ApiResponse<T>`, base exceptions,
`SortDirection`, `FieldType` enum (mirrors `AttributeType`).

---

## libs/events/build.gradle.kts

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(rootProject.libs.jackson.databind)
    api(rootProject.libs.jackson.datatype.jsr310)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
```

Provides: Kafka event POJOs + topic name constants. No Spring dependency — keeps it
importable in any consumer without Spring on the classpath.

---

## libs/security/build.gradle.kts

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":libs:common"))
    api(rootProject.libs.spring.boot.security)
    api(rootProject.libs.spring.boot.web)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
```

Provides: `PlatformSecurityContext` (holds `userId`, `orgId`, `roles` parsed from
gateway headers), `@RequireRole` annotation, `GatewayHeaderFilter`.

---

## Template: apps/metadata-service/build.gradle.kts

```kotlin
plugins {
    alias(rootProject.libs.plugins.spring.boot)
    alias(rootProject.libs.plugins.spring.dependency.mgmt)
}

extra["springCloudVersion"] = "2026.0.0"   // verify compatibility

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:security"))
    implementation(project(":libs:events"))

    implementation(rootProject.libs.spring.boot.web)
    implementation(rootProject.libs.spring.boot.data.jpa)
    implementation(rootProject.libs.spring.boot.validation)
    implementation(rootProject.libs.spring.boot.actuator)
    implementation(rootProject.libs.spring.boot.data.redis)
    implementation(rootProject.libs.spring.cloud.eureka.client)
    implementation(rootProject.libs.springdoc.openapi)
    implementation(rootProject.libs.micrometer.prometheus)
    implementation(rootProject.libs.logstash.logback)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.flyway.postgresql)
    implementation(rootProject.libs.mapstruct)

    compileOnly(rootProject.libs.lombok)
    runtimeOnly(rootProject.libs.postgresql)
    annotationProcessor(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.mapstruct.processor)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${extra["springCloudVersion"]}")
    }
}
```

---

## Package Conventions

All services live under `com.lagu.platform.<service-name>`.

```
com.lagu.platform.metadata
    ├── api         REST controllers
    ├── domain      JPA entities, repository interfaces
    ├── service     business logic
    ├── dto         request/response records
    ├── mapper      MapStruct mappers
    ├── event       Kafka producers
    └── config      Spring @Configuration classes
```

---

## Dockerfile Template (each app)

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

For observability with OTel Java agent (consistent with existing services):

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar /otel/agent.jar
COPY build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", \
  "-javaagent:/otel/agent.jar", \
  "-jar", "app.jar"]
```

---

## First Steps Checklist

- [ ] Delete `src/` from root (the existing single-module Spring Boot app)
- [ ] Create `settings.gradle.kts` with all includes
- [ ] Create `gradle/libs.versions.toml`
- [ ] Update root `build.gradle.kts` (no Spring Boot plugin apply in root)
- [ ] Create `libs/common`, `libs/events`, `libs/security` with their build files
- [ ] Scaffold `apps/metadata-service` as the first app
- [ ] Verify Gradle syncs correctly: `./gradlew projects`
- [ ] Verify Spring Cloud version compatibility with Spring Boot 4.1.0
