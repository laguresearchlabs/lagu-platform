plugins {
    `java-library`
}

dependencies {
    api(rootProject.libs.jackson.databind)
    api(rootProject.libs.jackson.datatype.jsr310)
    api(rootProject.libs.spring.boot.validation)
    api(rootProject.libs.spring.boot.web)
    api(rootProject.libs.spring.boot.data.jpa)
    api(rootProject.libs.spring.boot.data.redis)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
