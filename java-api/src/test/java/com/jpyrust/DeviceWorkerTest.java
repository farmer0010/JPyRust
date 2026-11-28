package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "JPYRUST_PROVISION_TEST", matches = "true")
public class DeviceWorkerTest {

    private static final String TASKS = String.join("\n",
            "import os",
            "import time",
            "from jpyrust import task, config",
            "",
            "",
            "@task(\"DEVICE_ENV\")",
            "def device_env(data, args):",
            "    time.sleep(0.5)",
            "    return os.environ.get(\"JPYRUST_DEVICE\", \"\") + \" \" + os.environ.get(\"CUDA_VISIBLE_DEVICES\", \"-\")"
                    + " + \" \" + config[\"device\"]",
            "");

    @TempDir(cleanup = CleanupMode.NEVER)
    Path dir;

    @Test
    public void eachWorkerSeesOnlyItsOwnDevice() throws Exception {
        Path modules = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(modules.resolve("device_tasks.py"), TASKS);
        try (JPyRustBridge bridge = new JPyRustBridge("device_workers")
                .requirements(Files.writeString(dir.resolve("requirements.txt"), ""))
                .moduleDir(modules)
                .devices("cuda:1", "cuda:3", "cpu")) {
            bridge.initialize(dir.resolve("work").toString());
            Set<String> seen = Stream.of(1, 2, 3)
                    .map(i -> bridge.callAsync("DEVICE_ENV", new byte[0], ""))
                    .toList().stream()
                    .map(CompletableFuture::join)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .collect(Collectors.toSet());
            assertEquals(Set.of("cuda:1 1 cpu", "cuda:3 3 cpu", "cpu  cpu"), seen);
            assertTrue(bridge.sendTask("STATUS", "NONE").contains("\"device\": \"cpu\""));
        }
    }
}
