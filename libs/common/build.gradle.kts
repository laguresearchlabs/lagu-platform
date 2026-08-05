plugins {
    `java-library`
}

dependencies {
    api(rootProject.libs.jackson.databind)
    api(rootProject.libs.jackson.datatype.jsr310)
    api(rootProject.libs.spring.boot.jackson2)
    api(rootProject.libs.spring.boot.validation)
    api(rootProject.libs.spring.boot.web)
    api(rootProject.libs.spring.boot.data.jpa)
    // compileOnly: only services that actually cache need Redis at runtime (record-service,
    // schema-registry, search-service — all of which declare it themselves). As an `api`
    // dependency it landed on every consumer's runtime classpath, which auto-configured a
    // Redis health indicator in services with no Redis at all; those defaulted to
    // localhost:6379 and reported /actuator/health DOWN. JacksonRedisSerializer still
    // compiles here; consumers that use it must declare data.redis themselves.
    compileOnly(rootProject.libs.spring.boot.data.redis)
    // compileOnly: only services that enable platform.outbox need Kafka at runtime, and
    // all of them already declare it; this keeps kafka off the other services' classpaths.
    compileOnly(rootProject.libs.spring.boot.kafka)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)

    testImplementation(project(":libs:events"))
    testImplementation(rootProject.libs.spring.boot.test)
    testImplementation(rootProject.libs.spring.boot.kafka)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
