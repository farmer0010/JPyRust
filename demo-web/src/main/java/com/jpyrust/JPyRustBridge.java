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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jpyrust.NativeLoader;

public class JPyRustBridge {

    private static final Logger logger = LoggerFactory.getLogger(JPyRustBridge.class);

    private static String workDir = "C:/jpyrust_temp";
    private static String sourceScriptDir = "d:/JPyRust/python-core";
    private static final int HEADER_SIZE = 4;

    private static boolean initialized = false;

    static {
        try {
            System.loadLibrary("jpyrust");
            System.out.println("[JPyRustBridge] Native library loaded successfully");

        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JPyRustBridge] Failed to load native library: " + e.getMessage());

            // Removed specific path loading logic
            throw e; // Re-throw if library cannot be loaded
        }
    }

    private static native void initNative(String workDir, String sourceScriptDir, String modelPath, float confidence);

    private native byte[] executeTask(String workDir, String taskType, String requestId,
            String metadata, ByteBuffer inputData, int inputLength);

    private native byte[] runPythonProcess(String workDir, ByteBuffer data, int length,
            int width, int height, int channels, String requestId);

    public synchronized static void initialize(String workDirectory, String sourceScript, String modelPath,
            float confidence) {
        if (initialized)
            return;

        workDir = workDirectory;
        sourceScriptDir = sourceScript;

        System.out.println("=== JPyRust Universal Bridge ===");
        System.out.println("[Init] Work Dir: " + workDir);
        System.out.println("[Init] Model Path: " + modelPath);
        System.out.println("[Init] Confidence: " + confidence);

        try {
            File tempDir = new File(workDir);
            if (!tempDir.exists())
                tempDir.mkdirs();

            try {
                System.out.println("[Init] Checking for embedded Python distribution...");
                NativeLoader.extractZip("/python_dist.zip", Paths.get(workDir));
                System.out.println("[Init] Embedded Python extracted successfully.");
            } catch (Exception e) {
                System.out.println("[Init] Embedded Python not found (Dev Mode?): " + e.getMessage());
            }

            Path src = Paths.get(sourceScriptDir, "ai_worker.py");
            Path dst = Paths.get(workDir, "ai_worker.py");
            if (Files.exists(src)) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }

            initNative(workDir, sourceScriptDir, modelPath, confidence);
            initialized = true;
            System.out.println("=== Initialization Complete ===");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized static void initialize() {
        initialize(workDir, sourceScriptDir, "yolov8n.pt", 0.5f);
    }

    public byte[] execute(String taskType, String metadata, byte[] inputData) {
        String requestId = UUID.randomUUID().toString();
        System.out.println("[Bridge] Execute: " + taskType + " | ID: " + requestId.substring(0, 8));

        ByteBuffer buffer = ByteBuffer.allocateDirect(inputData.length);
        buffer.order(ByteOrder.nativeOrder());
        buffer.put(inputData);
        buffer.flip();

        return executeTask(workDir, taskType, requestId, metadata, buffer, inputData.length);
    }

    public static void log(String level, String msg) {
        try {
            if ("ERROR".equalsIgnoreCase(level))
                logger.error("[Native] {}", msg);
            else if ("WARN".equalsIgnoreCase(level))
                logger.warn("[Native] {}", msg);
            else
                logger.info("[Native] {}", msg);
        } catch (Throwable t) {
            System.err.println("[JPyRustBridge-Java] Logging failed: " + t.getMessage());
            System.out.println("[Native-Fallback] [" + level + "] " + msg);
        }
    }

    public byte[] processImage(String workDirectory, ByteBuffer data, int length,
            int width, int height, int channels, String requestId) {
        System.out.println("[Bridge] YOLO | ID: " + requestId.substring(0, 8));
        data.position(0);
        return runPythonProcess(workDirectory, data, length, width, height, channels, requestId);
    }

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

    public byte[] processImage(String workDirectory, ByteBuffer data, int length,
            int width, int height, int channels) {
        return processImage(workDirectory, data, length, width, height, channels,
                UUID.randomUUID().toString());
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        return processImage(workDir, data, length, width, height, channels);
    }
}