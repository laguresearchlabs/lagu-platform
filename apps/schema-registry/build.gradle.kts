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
    implementation(rootProject.libs.spring.boot.data.redis)
    implementation(rootProject.libs.spring.boot.kafka)
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

    testImplementation(rootProject.libs.spring.boot.test)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
}
