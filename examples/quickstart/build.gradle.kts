plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":java-api"))
}

application {
    mainClass.set("example.Quickstart")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
}
