plugins {
    `java-library`
}

import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption


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

// === Python Dependency Automation (Offline Strategy) ===

val pythonDistDir = layout.buildDirectory.dir("python_staging")
val wheelsDir = pythonDistDir.map { it.dir("wheels") }
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val requirementsFile = file("../requirements.txt")
val pythonCoreDir = file("../python-core")
val localResourcesDir = file("src/main/resources/python_dist")

// 1. Download Embedded Python (Windows x64)
val downloadPython = tasks.register("downloadEmbeddedPython") {
    group = "python"
    description = "Downloads the official Python Embedded Distribution"
    
    val pythonVersion = "3.11.9"
    val pythonUrl = "https://www.python.org/ftp/python/${pythonVersion}/python-${pythonVersion}-embed-amd64.zip"
    val targetZip = layout.buildDirectory.file("tmp/python-${pythonVersion}-embed-amd64.zip")

    outputs.file(targetZip)
    
    doLast {
        val targetFile = targetZip.get().asFile
        if (!targetFile.exists()) {
            targetFile.parentFile.mkdirs()
            println("Downloading Python Embedded ${pythonVersion}...")
            val url = URI(pythonUrl).toURL()
            url.openStream().use { input: java.io.InputStream ->
                Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
    
    // Skip on JitPack to avoid unnecessary downloads
    onlyIf { System.getenv("JITPACK") == null }
}

// 2. Download Wheels
val downloadWheels = tasks.register<Exec>("downloadWheels") {
    group = "python"
    description = "Downloads wheel files for offline installation"
    
    inputs.file(requirementsFile)
    outputs.dir(wheelsDir)
    
    doFirst {
        val wDir = wheelsDir.get().asFile
        if (!wDir.exists()) wDir.mkdirs()
    }

    val pipCmd = if (System.getProperty("os.name").lowercase().contains("win")) "pip" else "pip3"
    
    // We download to a temporary location first or directly to staging? 
    // Let's download directly to staging/wheels
    commandLine(
        pipCmd, "download",
        "pip", "setuptools", "wheel", // Explicitly download bootstrap tools
        "-r", requirementsFile.absolutePath,
        "--dest", wheelsDir.get().asFile.absolutePath,
        "--platform", "win_amd64",
        "--python-version", "311",
        "--only-binary=:all:"
    )
    
    // Skip on JitPack (no pip/python access usually)
    onlyIf { System.getenv("JITPACK") == null }
}

// 3. Stage Python Environment (Assemble)
val stagePython = tasks.register<Copy>("stagePython") {
    dependsOn(downloadPython, downloadWheels)
    group = "python"
    
    val zipFile = layout.buildDirectory.file("tmp/python-3.11.9-embed-amd64.zip")
    
    from(zipTree(zipFile))

    
    from(localResourcesDir) {
        include("bootstrap.py")
    }
    
    from(pythonCoreDir) {
        include("ai_worker.py")
        include("yolov8n.pt") // Optional: if we want to bundle model
    }
    
    from(requirementsFile.parentFile) {
        include("requirements.txt")
        include("constraints.txt") // if exists
    }
    
    into(pythonDistDir)
    
    // Post-processing: Enable 'import site' in python311._pth
            pthFile.writeText(newContent)
        }
    }
    
    // Skip on JitPack
    onlyIf { System.getenv("JITPACK") == null }
}

// 4. Zip the Staged Directory
val zipPythonDist = tasks.register<Zip>("zipPythonDist") {
    dependsOn(stagePython)
    group = "python"
    description = "Archives the staged python environment into a ZIP"

    archiveFileName.set("python_dist.zip")
    destinationDirectory.set(generatedResourcesDir)
    
    
    from(pythonDistDir)
    
    // Skip on JitPack
    onlyIf { System.getenv("JITPACK") == null }
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
