package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "JPYRUST_PROVISION_TEST", matches = "true")
public class PythonProvisioningTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDir;

    @Test
    @EnabledIfEnvironmentVariable(named = "JPYRUST_FULL_STACK_TEST", matches = "true")
    public void firstInitializeProvisionsPythonAndAnswersStatus() throws Exception {
        JPyRustBridge bridge = new JPyRustBridge("provision_test").extras("vision", "audio", "ml");
        try {
            bridge.initialize(workDir.toString());
            String status = bridge.sendTask("STATUS", "NONE");
            assertTrue(status.contains("\"status\": \"UP\""), status);
            String sentiment = bridge.processNlp("This is great");
            assertTrue(sentiment.startsWith("POSITIVE"), sentiment);
        } finally {
            bridge.close();
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path python = isWindows ? workDir.resolve("python_dist/python.exe") : workDir.resolve("venv/bin/python3");
        assertTrue(Files.exists(python), python.toString());

        Process imports = new ProcessBuilder(python.toString(), "-c", "import torch, ultralytics, whisper, sklearn, textblob")
                .inheritIO()
                .start();
        assertEquals(0, imports.waitFor());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JPYRUST_TEST_PYTHON", matches = ".+")
    public void customInterpreterBuildsTheVenv(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path requirements = Files.writeString(dir.resolve("requirements.txt"), "");
        Path modules = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(modules.resolve("version_tasks.py"),
                "import sys\nfrom jpyrust import task\n\n\n@task(\"PREFIX\")\ndef prefix(data, args):\n"
                        + "    return sys.prefix\n");
        Path work = dir.resolve("work");

        try (JPyRustBridge bridge = new JPyRustBridge("provision_custom_python")
                .python(Path.of(System.getenv("JPYRUST_TEST_PYTHON")))
                .requirements(requirements)
                .moduleDir(modules)) {
            bridge.initialize(work.toString());
            String prefix = bridge.call("PREFIX", "");
            assertEquals(work.resolve("venv").toRealPath(), Path.of(prefix).toRealPath());
        }
        assertTrue(Files.notExists(work.resolve("python_dist")));
    }

    @Test
    public void userModuleRunsWithUserRequirements(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path requirements = Files.writeString(dir.resolve("requirements.txt"), "");
        Path modules = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(modules.resolve("echo_tasks.py"),
                "from jpyrust import task\n\n\n@task(\"ECHO\")\ndef echo(data, args):\n"
                        + "    return {\"text\": data.decode(\"utf-8\"), \"args\": args}\n");

        JPyRustBridge bridge = new JPyRustBridge("provision_user_module").requirements(requirements).moduleDir(modules);
        try {
            bridge.initialize(dir.resolve("work").toString());
            String echoed = new String(bridge.call("ECHO", "안녕".getBytes(StandardCharsets.UTF_8), "x y"),
                    StandardCharsets.UTF_8);
            assertEquals("{\"text\": \"\uc548\ub155\", \"args\": \"x y\"}", echoed);
            RuntimeException error = assertThrows(RuntimeException.class, () -> bridge.call("REGRESSION", "[]"));
            assertTrue(error.getMessage().contains("requires extras(\"ml\")"), error.getMessage());
        } finally {
            bridge.close();
        }
    }
}
