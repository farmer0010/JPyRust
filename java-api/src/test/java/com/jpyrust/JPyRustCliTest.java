package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class JPyRustCliTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return JPyRustCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    public void printsUsage() {
        assertEquals(1, run());
        assertEquals(0, run("--help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("-Djpyrust.torch=auto"));
    }

    @Test
    public void rejectsUnknownCommandsAndOptions() {
        assertEquals(1, run("build"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown command: build"));
        assertEquals(1, run("setup", "--gpu", "yes"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown option: --gpu"));
        assertEquals(1, run("setup", "--torch", "cuda12"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown torch build"));
    }

    @Test
    public void doctorReportsTheMachine() {
        assertEquals(0, run("doctor"));
        String report = out.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("Recommended"), report);
        assertTrue(report.contains("-Djpyrust.torch="), report);
    }
}
