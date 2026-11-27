package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Duration;
import org.junit.jupiter.api.Test;

public class JPyRustBridgeLifecycleTest {

    @Test
    public void processImageBeforeInitializeThrows() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        ByteBuffer buffer = ByteBuffer.allocateDirect(4);
        assertThrows(IllegalStateException.class, () -> bridge.processImage(buffer, 4, 1, 1, 3));
    }

    @Test
    public void sendTaskBeforeInitializeThrows() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalStateException.class, () -> bridge.sendTask("STATUS", "NONE"));
    }

    @Test
    public void closeWithoutInitializeIsNoOp() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertDoesNotThrow(bridge::close);
        assertDoesNotThrow(bridge::close);
    }

    @Test
    public void processImageAfterCloseThrows() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        bridge.close();
        ByteBuffer buffer = ByteBuffer.allocateDirect(4);
        assertThrows(IllegalStateException.class, () -> bridge.processImage(buffer, 4, 1, 1, 3));
    }

    @Test
    public void callBeforeInitializeThrows() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalStateException.class, () -> bridge.call("ECHO", "hi"));
    }

    @Test
    public void callRejectsArgsWithLineBreaks() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalArgumentException.class, () -> bridge.call("ECHO", new byte[0], "a\nEXIT"));
    }

    @Test
    public void configurationMethodsReturnTheBridge() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertSame(bridge, bridge.requirements(Paths.get("requirements.txt")).moduleDir(Paths.get("tasks"))
                .extras("vision").python(Paths.get("python3")));
    }

    @Test
    public void worksWithTryWithResources() {
        assertDoesNotThrow(() -> {
            try (JPyRustBridge bridge = new JPyRustBridge("lifecycle_test")) {
                assertSame(bridge, bridge.extras("ml"));
            }
        });
    }

    @Test
    public void invalidPoolAndTimeoutsAreRejected() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalArgumentException.class, () -> bridge.workers(0));
        assertThrows(IllegalArgumentException.class, () -> bridge.timeouts(Duration.ZERO, Duration.ofSeconds(1)));
        assertSame(bridge, bridge.workers(4).timeouts(Duration.ofSeconds(30), Duration.ofSeconds(5))
                .model("custom.pt", 0.3f).whisperModel("tiny"));
    }

    @Test
    public void callAsyncBeforeInitializeThrows() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalStateException.class, () -> bridge.callAsync("ECHO", new byte[0], ""));
    }

    @Test
    public void unknownExtraIsRejected() {
        JPyRustBridge bridge = new JPyRustBridge("lifecycle_test");
        assertThrows(IllegalArgumentException.class, () -> bridge.extras("vision", "nlp"));
    }
}
