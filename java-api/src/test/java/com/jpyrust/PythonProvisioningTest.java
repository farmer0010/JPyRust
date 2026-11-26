package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    public void firstInitializeProvisionsPythonAndAnswersStatus() throws Exception {
        JPyRustBridge bridge = new JPyRustBridge("provision_test");
        try {
            bridge.initialize(workDir.toString());
            String status = bridge.sendTask("STATUS", "NONE");
            assertTrue(status.contains("\"status\": \"UP\""), status);
        } finally {
            bridge.close();
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path python = isWindows ? workDir.resolve("python_dist/python.exe") : workDir.resolve("venv/bin/python3");
        assertTrue(Files.exists(python), python.toString());

        Process imports = new ProcessBuilder(python.toString(), "-c", "import torch, ultralytics, whisper")
                .inheritIO()
                .start();
        assertEquals(0, imports.waitFor());
    }
}
