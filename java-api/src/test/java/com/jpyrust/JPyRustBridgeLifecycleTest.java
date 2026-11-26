package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
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
}
