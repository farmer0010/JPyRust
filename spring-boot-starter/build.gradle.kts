plugins {
    `java-library`
    `maven-publish`
    signing
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "io.github.farmer0010"
            artifactId = "jpyrust-spring-boot-starter"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("JPyRust Spring Boot Starter")
                description.set("Spring Boot auto-configuration, Micrometer timers and an Actuator health check for JPyRust.")
            }
        }
    }
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val springBoot = "org.springframework.boot:spring-boot-dependencies:3.2.1"

dependencies {
    api(project(":java-api"))
    compileOnly(platform(springBoot))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation(platform(springBoot))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("io.micrometer:micrometer-core")
}

tasks.test {
    useJUnitPlatform()
}
