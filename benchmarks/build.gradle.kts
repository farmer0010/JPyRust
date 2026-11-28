plugins {
    java
    id("me.champeau.jmh") version "0.7.2"
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
    jmh(project(":java-api"))
}

jmh {
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    warmup.set("2s")
    timeOnIteration.set("2s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
}
