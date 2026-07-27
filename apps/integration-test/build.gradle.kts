import java.time.Duration

/**
 * Cross-service runtime integration tests: boot real app processes (each in its own container,
 * from the actual bootJar) against real Postgres/Redis/Kafka/OpenSearch containers, and drive
 * them purely over HTTP + Kafka — the same way they talk to each other in production.
 * PlatformEndToEndIT covers schema-registry/record-service/workflow-service/search-service/
 * listing-service; BookingFlowEndToEndIT covers schema-registry/listing-service/booking-service.
 * See PlatformEndToEndIT's class Javadoc for why this isn't a @SpringBootTest: combining multiple
 * apps' main sourceSets on one classpath would collide their classpath:application.yml and
 * classpath:db/migration resources.
 */
dependencies {
    testImplementation(project(":libs:common"))
    testImplementation(project(":libs:events"))

    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testImplementation(rootProject.libs.testcontainers.redis)
    testImplementation(rootProject.libs.testcontainers.kafka)
    testImplementation("org.apache.kafka:kafka-clients")

    testImplementation(rootProject.libs.awaitility)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun jarDir(path: String) = project(path).layout.buildDirectory.dir("libs").get().asFile.absolutePath

tasks.named<Test>("test") {
    dependsOn(
        ":apps:schema-registry:bootJar",
        ":apps:record-service:bootJar",
        ":apps:search-service:bootJar",
        ":apps:workflow-service:bootJar",
        ":apps:listing-service:bootJar",
        ":apps:booking-service:bootJar",
    )
    // Pointing at the libs/ dir (rather than a specific task output file) sidesteps having to
    // reach into another project's task graph — bootJar's default archive name convention is
    // enough, and PlatformEndToEndIT picks the one non "-plain.jar" jar in each directory.
    systemProperty("it.schemaRegistryJarDir", jarDir(":apps:schema-registry"))
    systemProperty("it.recordServiceJarDir", jarDir(":apps:record-service"))
    systemProperty("it.searchServiceJarDir", jarDir(":apps:search-service"))
    systemProperty("it.workflowServiceJarDir", jarDir(":apps:workflow-service"))
    systemProperty("it.listingServiceJarDir", jarDir(":apps:listing-service"))
    systemProperty("it.bookingServiceJarDir", jarDir(":apps:booking-service"))
    // The jars are consumed at runtime via the system properties above, which Gradle can't see —
    // declare them as inputs so a rebuilt service jar invalidates this task's up-to-date check
    // (otherwise the E2E silently does NOT re-run after service code changes).
    inputs.dir(jarDir(":apps:schema-registry")).withPropertyName("schemaRegistryJars")
    inputs.dir(jarDir(":apps:record-service")).withPropertyName("recordServiceJars")
    inputs.dir(jarDir(":apps:search-service")).withPropertyName("searchServiceJars")
    inputs.dir(jarDir(":apps:workflow-service")).withPropertyName("workflowServiceJars")
    inputs.dir(jarDir(":apps:listing-service")).withPropertyName("listingServiceJars")
    inputs.dir(jarDir(":apps:booking-service")).withPropertyName("bookingServiceJars")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Cold JVM starts for up to 3 app containers + 4 infra containers comfortably exceed JUnit's
    // usual test timeouts.
    timeout.set(Duration.ofMinutes(15))
}
