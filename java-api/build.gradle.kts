plugins {
    `java-library`
    `maven-publish`
    signing
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

val jitpack = System.getenv("JITPACK") == "true"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = if (jitpack) "com.github.farmer0010" else "io.github.farmer0010"
            artifactId = if (jitpack) "JPyRust" else "jpyrust"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("JPyRust")
                description.set("Call CPython from Java through a JVM-managed, crash-isolated worker pool, with Python provisioned automatically.")
            }
        }
    }
}


repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val requirementsFile = file("../requirements.txt")
val pythonCoreDir = file("../python-core")

val stageCommonPythonResources = tasks.register<Copy>("stageCommonPythonResources") {
    group = "python"
    description = "Copies ai_worker.py and requirements.txt as plain resources for the Python bootstrap"

    from(pythonCoreDir) { include("ai_worker.py", "jpyrust.py", "extras/*.py", "extras/*.txt") }
    from(requirementsFile.parentFile) { include("requirements.txt") }
    into(generatedResourcesDir.map { it.dir("common") })
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(stageCommonPythonResources)

    exclude("python_dist/**")

    from("../docs/THIRD_PARTY_NOTICES.md") { into("META-INF") }

    from(stageCommonPythonResources.map { it.destinationDir }) {
        include("ai_worker.py", "jpyrust.py", "requirements.txt", "extras/**")
    }
}
