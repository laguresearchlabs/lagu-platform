plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:events"))
    implementation(project(":libs:security"))

    implementation(rootProject.libs.spring.boot.web)
    implementation(rootProject.libs.spring.boot.data.jpa)
    implementation(rootProject.libs.spring.boot.validation)
    implementation(rootProject.libs.spring.boot.actuator)
    implementation(rootProject.libs.spring.boot.kafka)
    implementation(rootProject.libs.spring.retry)
    implementation(rootProject.libs.resilience4j.cb)
    implementation(rootProject.libs.resilience4j.retry)
    implementation(rootProject.libs.resilience4j.micrometer)
    implementation(rootProject.libs.springdoc.openapi)
    implementation(rootProject.libs.micrometer.prometheus)
    implementation(rootProject.libs.logstash.logback)
    implementation(rootProject.libs.postgresql)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.flyway.postgresql)
    implementation(rootProject.libs.spring.boot.flyway)
    implementation(rootProject.libs.aspectjweaver)

    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)

    testImplementation(rootProject.libs.spring.boot.test)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testImplementation(rootProject.libs.testcontainers.redis)
    testImplementation(rootProject.libs.spring.kafka.test)
}
