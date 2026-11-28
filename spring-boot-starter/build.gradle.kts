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
val springBoot4 = "org.springframework.boot:spring-boot-dependencies:4.2.0"

dependencies {
    api(project(":java-api"))
    compileOnly(platform(springBoot))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("org.springframework.boot:spring-boot-health:4.2.0") { isTransitive = false }

    testImplementation(platform(springBoot))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("io.micrometer:micrometer-core")
}

tasks.test {
    useJUnitPlatform()
}

testing {
    suites {
        register<JvmTestSuite>("boot4Test") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(platform(springBoot4))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-health")
                implementation("org.springframework.boot:spring-boot-micrometer-metrics")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("boot4Test"))
}
