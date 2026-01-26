package com.jpyrust;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * JPyRust Universal Bridge - Supports multiple task types via Python daemon.
 */
public class JPyRustBridge {

    private static String workDir = "C:/jpyrust_temp";
    private static String sourceScriptDir = "d:/JPyRust/python-core";
    private static boolean initialized = false;

    static {
        try {
            System.loadLibrary("jpyrust");
            System.out.println("[JPyRustBridge] Native library loaded!");
        } catch (UnsatisfiedLinkError e) {
            String libPath = "d:/JPyRust/rust-bridge/target/release/jpyrust.dll";
            File libFile = new File(libPath);
            if (libFile.exists()) {
                System.load(libPath);
                System.out.println("[JPyRustBridge] Loaded from: " + libPath);
            } else {
                libPath = "d:/JPyRust/rust-bridge/target/debug/jpyrust.dll";
                if (new File(libPath).exists()) {
                    System.load(libPath);
                } else {
                    throw e;
                }
            }
        }
    }

    // Native methods
    private static native void initNative(String workDir, String sourceScriptDir);

    private native byte[] executeTask(String workDir, String taskType, String requestId,
            String metadata, ByteBuffer inputData, int inputLength);

    // Legacy native method (backward compatibility)
    private native byte[] runPythonProcess(String workDir, ByteBuffer data, int length,
            int width, int height, int channels, String requestId);

    /**
     * Initialize the bridge with paths.
     */
    public synchronized static void initialize(String workDirectory, String sourceScript) {
        if (initialized)
            return;

        workDir = workDirectory;
        sourceScriptDir = sourceScript;

        System.out.println("=== JPyRust Universal Bridge ===");
        System.out.println("[Init] Work Dir: " + workDir);

        try {
            File tempDir = new File(workDir);
            if (!tempDir.exists())
                tempDir.mkdirs();

            Path src = Paths.get(sourceScriptDir, "ai_worker.py");
            Path dst = Paths.get(workDir, "ai_worker.py");
            if (Files.exists(src)) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }

            initNative(workDir, sourceScriptDir);
            initialized = true;
            System.out.println("=== Initialization Complete ===");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized static void initialize() {
        initialize(workDir, sourceScriptDir);
    }

    // ============================================
    // UNIVERSAL TASK EXECUTION
    // ============================================

    /**
     * Execute any task type with the universal protocol.
     * 
     * @param taskType  Task type (e.g., "YOLO", "SENTIMENT")
     * @param metadata  Task-specific metadata string
     * @param inputData Input data as bytes
     * @return Output data as bytes
     */
    public byte[] execute(String taskType, String metadata, byte[] inputData) {
        String requestId = UUID.randomUUID().toString();
        System.out.println("[Bridge] Execute: " + taskType + " | ID: " + requestId.substring(0, 8));

        ByteBuffer buffer = ByteBuffer.allocateDirect(inputData.length);
        buffer.order(ByteOrder.nativeOrder());
        buffer.put(inputData);
        buffer.flip();

        return executeTask(workDir, taskType, requestId, metadata, buffer, inputData.length);
    }

    // ============================================
    // TYPED TASK METHODS
    // ============================================

    /**
     * Process image with YOLO object detection.
     * Returns JPEG with bounding boxes.
     */
    public byte[] processImage(String workDirectory, ByteBuffer data, int length,
            int width, int height, int channels, String requestId) {
        System.out.println("[Bridge] YOLO | ID: " + requestId.substring(0, 8));
        data.position(0);
        return runPythonProcess(workDirectory, data, length, width, height, channels, requestId);
    }

    /**
     * Analyze text sentiment.
     * Returns sentiment result string.
     */
    public String processText(String text) {
        String requestId = UUID.randomUUID().toString();
        System.out.println("[Bridge] SENTIMENT | ID: " + requestId.substring(0, 8));

        byte[] inputBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocateDirect(inputBytes.length);
        buffer.order(ByteOrder.nativeOrder());
        buffer.put(inputBytes);
        buffer.flip();

        byte[] result = executeTask(workDir, "SENTIMENT", requestId, "NONE", buffer, inputBytes.length);

        if (result == null) {
            return "ERROR: Processing failed";
        }

        return new String(result, StandardCharsets.UTF_8);
    }

    // Legacy methods for backward compatibility
    public byte[] processImage(String workDirectory, ByteBuffer data, int length,
            int width, int height, int channels) {
        return processImage(workDirectory, data, length, width, height, channels,
                UUID.randomUUID().toString());
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        return processImage(workDir, data, length, width, height, channels);
    }
}