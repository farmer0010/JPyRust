plugins {
    `java-library`
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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// === Python Dependency Automation ===

val pythonDistDir = file("src/main/resources/python_dist")
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val requirementsFile = file("../requirements.txt")

// Task 1: Install Dependencies using pip
tasks.register<Exec>("installPythonDeps") {
    group = "python"
    description = "Installs dependencies from requirements.txt into the embedded Python runtime"

    // Only run if requirements changed or target dir missing
    inputs.file(requirementsFile)
    outputs.dir(pythonDistDir.resolve("Lib/site-packages"))

    // Initial check: if folder doesn't exist, create it
    doFirst {
        val sitePkg = pythonDistDir.resolve("Lib/site-packages")
        if (!sitePkg.exists()) {
            sitePkg.mkdirs()
        }
    }

    val pipCmd = if (System.getProperty("os.name").lowercase().contains("win")) "pip" else "pip3"
    
    commandLine(
        pipCmd, "install", 
        "-r", requirementsFile.absolutePath, 
        "--target", pythonDistDir.resolve("Lib/site-packages").absolutePath,
        "--no-user",
        "--upgrade",
        "--no-warn-script-location"
    )
}

// Task 2: Clean up cache (Optimization)
tasks.register<Delete>("cleanPythonGarbage") {
    dependsOn("installPythonDeps")
    group = "python"
    
    delete(fileTree(pythonDistDir) {
        include("**/__pycache__/**")
        include("**/*.pyc")
    })
}

// Task 3: Zip the directory (Packaging)
val zipPythonDist = tasks.register<Zip>("zipPythonDist") {
    dependsOn("cleanPythonGarbage")
    group = "python"
    description = "Archives the python_dist directory into a ZIP for embedding"

    archiveFileName.set("python_dist.zip")
    destinationDirectory.set(generatedResourcesDir)

    from(pythonDistDir)
}

// Task 4: Hook into processResources
tasks.named<ProcessResources>("processResources") {
    dependsOn(zipPythonDist)
    
    // Exclude the raw directory structure from finalized JAR
    exclude("python_dist/**")
    
    // Include the generated ZIP from build directory
    from(zipPythonDist.map { it.destinationDirectory }) {
        include("python_dist.zip")
    }
}
