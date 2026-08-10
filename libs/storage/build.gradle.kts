plugins {
    `java-library`
}

dependencies {
    api(project(":libs:common"))
    api(rootProject.libs.spring.boot.web)

    // Both backends are on the compile classpath so either can be selected at runtime via
    // storage.provider; only the matching @ConditionalOnProperty configuration instantiates
    // a client, so the unused SDK is dead weight on the classpath but never initialised.
    api(rootProject.libs.aws.s3)
    api(rootProject.libs.aws.auth)
    api(rootProject.libs.gcs)

    // Registers a WebP reader with ImageIO, which ships without one. WebP is an allowed upload
    // format, so without this every WebP would silently skip dimension checks and thumbnailing.
    implementation(rootProject.libs.imageio.webp)

    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)

    testImplementation(rootProject.libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
