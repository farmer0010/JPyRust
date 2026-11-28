package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "JPYRUST_PROVISION_TEST", matches = "true")
public class NDArrayWorkerTest {

    private static final String TASKS = String.join("\n",
            "import numpy",
            "from jpyrust import task",
            "",
            "",
            "@task(\"NORMALIZE\")",
            "def normalize(image, args):",
            "    return (image.astype(numpy.float32) / 255.0).mean(axis=2)",
            "",
            "",
            "@task(\"MATMUL\")",
            "def matmul(data, args):",
            "    return data @ data.T",
            "",
            "",
            "@task(\"SHAPE\")",
            "def shape(data, args):",
            "    return {\"shape\": list(data.shape), \"dtype\": str(data.dtype), \"writeable\": data.flags.writeable}",
            "");

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path dir;

    static JPyRustBridge bridge;

    @BeforeAll
    static void start() throws Exception {
        Path modules = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(modules.resolve("array_tasks.py"), TASKS);
        bridge = new JPyRustBridge("ndarray_worker").moduleDir(modules);
        bridge.initialize(dir.resolve("work").toString());
    }

    @AfterAll
    static void stop() {
        bridge.close();
    }

    @Test
    public void imageArrayArrivesAsNdarrayAndComesBackTyped() {
        byte[] pixels = new byte[4 * 5 * 3];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % 3 == 0 ? 255 : 0);
        }
        NDArray gray = bridge.call("NORMALIZE", NDArray.ofUInt8(pixels, 4, 5, 3), "");
        assertEquals(NDArray.DType.FLOAT32, gray.dtype());
        assertArrayEquals(new int[] {4, 5}, gray.shape());
        for (float value : gray.toFloatArray()) {
            assertEquals(1f / 3f, value, 1e-6);
        }
    }

    @Test
    public void largeFloat64MatrixRoundTrips() throws Exception {
        int rows = 512;
        int cols = 512;
        double[] values = new double[rows * cols];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % cols == i / cols) ? 2.0 : 0.0;
        }
        NDArray product = bridge.callAsync("MATMUL", NDArray.of(values, rows, cols), "").get();
        assertArrayEquals(new int[] {rows, rows}, product.shape());
        double[] out = product.toDoubleArray();
        assertEquals(4.0, out[0]);
        assertEquals(0.0, out[1]);
        assertEquals(4.0, out[rows * rows - 1]);
    }

    @Test
    public void taskSeesShapeAndDtypeAndNonArrayResultIsRejected() {
        String described = new String(bridge.call("SHAPE", NDArray.of(new long[6], 3, 2).toNpy(), ""));
        assertEquals("{\"shape\": [3, 2], \"dtype\": \"int64\", \"writeable\": false}", described);
        JPyRustException error = assertThrows(JPyRustException.class,
                () -> bridge.call("SHAPE", NDArray.of(new int[] {1}), ""));
        assertTrue(error.getMessage().contains("not an array"), error.getMessage());
    }
}
