plugins {
    `java-library`
}

dependencies {
    api(project(":libs:common"))    // ValidationException
    api(project(":libs:security"))  // PermissionEvaluator, PlatformSecurityContext (also brings spring-boot-web transitively)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)

    testImplementation(rootProject.libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
