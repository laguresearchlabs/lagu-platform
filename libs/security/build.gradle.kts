plugins {
    `java-library`
}

dependencies {
    api(project(":libs:common"))
    api(rootProject.libs.spring.boot.security)
    api(rootProject.libs.spring.boot.web)
    api(rootProject.libs.aspectjweaver)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)

    testImplementation(rootProject.libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
