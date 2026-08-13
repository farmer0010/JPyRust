package com.jpyrust;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class LatencyBenchmark {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Benchmark: Subprocess vs JNI+Rust FFI (Shared Memory)");
        System.out.println("Initializing JPyRustBridge...");

        JPyRustBridge bridge = new JPyRustBridge("benchmark_cam");
        bridge.initialize();

        byte[] dummyData = Files.readAllBytes(Paths.get("sample.png"));
        int length = dummyData.length;
        int width = 100;
        int height = 100;
        int channels = 3;

        ByteBuffer directBuffer = ByteBuffer.allocateDirect(length);
        directBuffer.put(dummyData);
        directBuffer.flip();
        
        int warmup = 2;
        int iterations = 10;
        
        System.out.println("Warming up...");
        for (int i = 0; i < warmup; i++) {
            bridge.processImage(directBuffer, length, width, height, channels);
        }
        
        System.out.println("\n[1] Testing Native JNI + Rust FFI (Shared Memory)");
        long jniTotal = 0;
        for (int i = 0; i < iterations; i++) {
            directBuffer.position(0);
            long start = System.nanoTime();
            bridge.processImage(directBuffer, length, width, height, channels);
            long end = System.nanoTime();
            long durationMs = (end - start) / 1000000;
            jniTotal += durationMs;
            System.out.printf("  Call %d: %d ms\n", (i+1), durationMs);
        }
        long jniAvg = jniTotal / iterations;
        
        System.out.println("\n[2] Testing Legacy Subprocess (ProcessBuilder)");
        long subTotal = 0;
        for (int i = 0; i < iterations; i++) {
            directBuffer.clear();
            directBuffer.put(dummyData);
            directBuffer.flip();
            long start = System.nanoTime();
            bridge.runPythonRaw(directBuffer, length, width, height, channels);
            long end = System.nanoTime();
            long durationMs = (end - start) / 1000000;
            subTotal += durationMs;
            System.out.printf("  Call %d: %d ms\n", (i+1), durationMs);
        }
        long subAvg = subTotal / iterations;
        
        System.out.println("\n================ BENCHMARK RESULTS ================");
        System.out.printf("Legacy Subprocess Average : %d ms\n", subAvg);
        System.out.printf("JPyRust (JNI+SHMEM) Avg   : %d ms\n", jniAvg);
        System.out.printf("Performance Improvement   : %.2fx faster\n", (double)subAvg / Math.max(1, jniAvg));
        System.out.println("===================================================");
        
        bridge.close();
    }
}
