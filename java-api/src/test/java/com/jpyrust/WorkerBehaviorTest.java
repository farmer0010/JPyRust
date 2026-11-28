package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "JPYRUST_PROVISION_TEST", matches = "true")
public class WorkerBehaviorTest {

    private static final String TASKS = String.join("\n",
            "import faulthandler",
            "import os",
            "import time",
            "from jpyrust import task",
            "",
            "",
            "@task(\"ECHO\")",
            "def echo(data, args):",
            "    return data",
            "",
            "",
            "@task(\"PID\")",
            "def pid(data, args):",
            "    return str(os.getpid())",
            "",
            "",
            "@task(\"SLEEP\")",
            "def sleep(data, args):",
            "    time.sleep(float(args))",
            "    return str(os.getpid())",
            "",
            "",
            "@task(\"FAIL\")",
            "def fail(data, args):",
            "    raise ValueError(\"bad input\")",
            "",
            "",
            "@task(\"EXIT\")",
            "def hard_exit(data, args):",
            "    os._exit(3)",
            "",
            "",
            "@task(\"SEGFAULT\")",
            "def segfault(data, args):",
            "    faulthandler._sigsegv()",
            "",
            "",
            "@task(\"COUNT\")",
            "def count(data, args):",
            "    for i in range(int(args)):",
            "        yield f\"{i}:\" + \"x\" * (i * 1000)",
            "",
            "",
            "@task(\"BREAK_AT\")",
            "def break_at(data, args):",
            "    yield \"ok\"",
            "    raise ValueError(\"broken stream\")",
            "",
            "",
            "@task(\"SLOW_STREAM\")",
            "def slow_stream(data, args):",
            "    yield \"first\"",
            "    time.sleep(float(args))",
            "    yield \"late\"",
            "");

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path dir;

    static JPyRustBridge bridge;

    @BeforeAll
    static void start() throws Exception {
        Path modules = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(modules.resolve("behavior_tasks.py"), TASKS);
        bridge = new JPyRustBridge("worker_behavior")
                .requirements(Files.writeString(dir.resolve("requirements.txt"), ""))
                .moduleDir(modules)
                .timeouts(Duration.ofSeconds(120), Duration.ofSeconds(2))
                .workers(3);
        bridge.initialize(dir.resolve("work").toString());
    }

    @AfterAll
    static void stop() {
        bridge.close();
    }

    @Test
    public void largePayloadRoundTripsThroughSharedMemory() {
        byte[] input = new byte[5 * 1024 * 1024];
        new Random(7).nextBytes(input);
        assertArrayEquals(input, bridge.call("ECHO", input, ""));
        assertArrayEquals(input, bridge.call("ECHO", input, ""));
        assertArrayEquals(new byte[0], bridge.call("ECHO", new byte[0], ""));
    }

    @Test
    public void pythonExceptionIsATaskFailure() {
        TaskFailedException error = assertThrows(TaskFailedException.class, () -> bridge.call("FAIL", ""));
        assertTrue(error.getMessage().contains("ValueError: bad input"), error.getMessage());
        assertEquals("ok", bridge.call("ECHO", "ok"));
    }

    @Test
    public void crashedWorkerIsRestarted() {
        assertThrows(WorkerCrashedException.class, () -> bridge.call("EXIT", ""));
        assertThrows(WorkerCrashedException.class, () -> bridge.call("SEGFAULT", ""));
        assertEquals("still alive", bridge.call("ECHO", "still alive"));
    }

    @Test
    public void hungWorkerTimesOutAndIsRestarted() {
        WorkerTimeoutException error = assertThrows(WorkerTimeoutException.class, () -> bridge.call("SLEEP", new byte[0], "30"));
        assertTrue(error.getMessage().contains("Timed out"), error.getMessage());
        assertEquals("after timeout", bridge.call("ECHO", "after timeout"));
    }

    @Test
    public void workersRunCallsInParallel() {
        long started = System.nanoTime();
        List<CompletableFuture<byte[]>> calls = IntStream.range(0, 3)
                .mapToObj(i -> bridge.callAsync("SLEEP", new byte[0], "1"))
                .toList();
        Set<String> pids = new HashSet<>();
        for (CompletableFuture<byte[]> call : calls) {
            pids.add(new String(call.join(), StandardCharsets.UTF_8));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(3, pids.size(), pids.toString());
        assertTrue(elapsedMs < 2500, "three 1s calls took " + elapsedMs + "ms");
        assertNotEquals(String.valueOf(ProcessHandle.current().pid()), bridge.call("PID", ""));
    }

    @Test
    @Timeout(60)
    public void streamDeliversEveryChunkInOrder() {
        List<String> chunks;
        try (Stream<byte[]> stream = bridge.callStream("COUNT", new byte[0], "50")) {
            chunks = stream.map(chunk -> new String(chunk, StandardCharsets.UTF_8)).toList();
        }
        assertEquals(50, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + ":" + "x".repeat(i * 1000), chunks.get(i));
        }
        assertEquals(List.of("plain"), bridge.callStream("ECHO", "plain").toList());
    }

    @Test
    @Timeout(60)
    public void closingAStreamEarlyReturnsTheWorker() {
        for (int i = 0; i < 10; i++) {
            try (Stream<String> stream = bridge.callStream("COUNT", new byte[0], "1000000")
                    .map(chunk -> new String(chunk, StandardCharsets.UTF_8))) {
                assertEquals(List.of("0:", "1:" + "x".repeat(1000)), stream.limit(2).toList());
            }
        }
        List<CompletableFuture<byte[]>> calls = IntStream.range(0, 3)
                .mapToObj(i -> bridge.callAsync("ECHO", new byte[] {(byte) i}, ""))
                .toList();
        calls.forEach(CompletableFuture::join);
    }

    @Test
    @Timeout(60)
    public void failureInsideAStreamIsATaskFailure() {
        try (Stream<byte[]> stream = bridge.callStream("BREAK_AT", new byte[0], "")) {
            Iterator<byte[]> chunks = stream.iterator();
            assertEquals("ok", new String(chunks.next(), StandardCharsets.UTF_8));
            TaskFailedException error = assertThrows(TaskFailedException.class, chunks::hasNext);
            assertTrue(error.getMessage().contains("broken stream"), error.getMessage());
        }
        assertThrows(TaskFailedException.class, () -> bridge.call("COUNT", new byte[0], "3"));
        assertEquals("after", bridge.call("ECHO", "after"));
    }

    @Test
    @Timeout(60)
    public void slowChunkTimesOutAndRestartsTheWorker() {
        try (Stream<byte[]> stream = bridge.callStream("SLOW_STREAM", new byte[0], "30")) {
            Iterator<byte[]> chunks = stream.iterator();
            assertEquals("first", new String(chunks.next(), StandardCharsets.UTF_8));
            assertThrows(WorkerTimeoutException.class, chunks::hasNext);
        }
        assertEquals("recovered", bridge.call("ECHO", "recovered"));
    }
}
