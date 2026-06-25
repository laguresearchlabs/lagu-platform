plugins {
    `java-library`
}

dependencies {
    api(rootProject.libs.jackson.databind)
    api(rootProject.libs.jackson.datatype.jsr310)
    compileOnly(rootProject.libs.lombok)
    annotationProcessor(rootProject.libs.lombok)
}
