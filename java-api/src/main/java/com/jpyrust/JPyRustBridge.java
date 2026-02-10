package com.jpyrust;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JPyRustBridge {

    private static String workDir = "C:/jpyrust_temp";
    private static String sourceScriptDir = "d:/JPyRust/python-core";

    private static final int HEADER_SIZE = 4;
    private static boolean initialized = false;

    static {
        System.out.println("DEBUG: JPyRustBridge static init started.");
        try {
            System.out.println("DEBUG: Calling NativeLoader.load('jpyrust')...");
            NativeLoader.load("jpyrust");
            System.out.println("[JPyRustBridge] Native library loaded successfully via NativeLoader");
        } catch (Throwable e) {
            System.out.println("DEBUG: Failed to load native library!");
            e.printStackTrace(System.out); // Print to stdout to ensure visibility
            throw new RuntimeException("Fatal: Native library load failed", e);
        }
    }

    private static Path pythonHome;
    private static Path pythonExe;

    public synchronized static void initialize() {
        // Default to user home directory
        String userHome = System.getProperty("user.home");
        Path defaultWorkDir = Paths.get(userHome, ".jpyrust");
        initialize(defaultWorkDir.toString(), null);
    }

    public synchronized static void initialize(String workDirectory, String ignoredSourceScript) {
        // Generate a unique session key for Shared Memory
        String memoryKey = "JPyRust_" + java.util.UUID.randomUUID().toString();
        System.out.println("[JPyRust] Generated Session Key (Default): " + memoryKey);
        initialize(workDirectory, ignoredSourceScript, memoryKey);
    }

    private static void setupEmbeddedPython(Path targetDir) throws Exception {
        Path pythonDistDir = targetDir.resolve("python_dist");
        Path markerFile = pythonDistDir.resolve(".installed");

        if (!Files.exists(markerFile)) {
            // Robustness: Check if it's actually installed but marker is missing (e.g.
            // crash during last boot)
            // We check for a key package like 'ultralytics' or 'torch'
            Path sitePackages = pythonDistDir.resolve("Lib/site-packages"); // Standard for embedded layout usually, or
                                                                            // just Lib/site-packages
            if (Files.exists(pythonDistDir.resolve("python.exe")) &&
                    (Files.exists(sitePackages.resolve("ultralytics"))
                            || Files.exists(sitePackages.resolve("torch")))) {
                System.out.println("[Init] Markers missing but packages found. Skipping re-install.");
                Files.createFile(markerFile);
                return;
            }

            System.out.println("[Init] Extracting user embedded python to: " + pythonDistDir);
            NativeLoader.extractZip("/python_dist.zip", pythonDistDir);

            // --- [Patched] Auto-fix for pip support ---
            Path pthFile = pythonDistDir.resolve("python311._pth");
            if (Files.exists(pthFile)) {
                System.out.println("[Init] Patching python311._pth to enable 'import site'...");
                Files.write(pthFile, "python311.zip\n.\nimport site".getBytes());
            }

            // --- [Patched] Manual Dependency Installation ---
            System.out.println("[Init] Installing dependencies via pip...");
            Path pyExe = pythonDistDir.resolve("python.exe");
            Path requirements = targetDir.resolve("requirements.txt");

            // Generate requirements.txt if missing (fallback)
            if (!Files.exists(requirements)) {
                // Copy from classpath or creating a minimal one if needed,
                // but for now we assume it exists or the user provided it.
                // Actually, let's copy the one from project root if we can or skip.
                // Better: JPyRustBridge is usually run where requirements.txt is available or
                // provided.
            }

            // We need to assume requirements.txt is in the targetDir or provided.
            // The previous logic didn't explicitly move it, but our manual steps did.
            // Let's ensure requirements.txt is present.
            // Since we can't easily access the project root from here dynamically in all
            // cases,
            // we will rely on the fact that if it exists, we install.

            if (Files.exists(requirements)) {
                Path wheelsDir = pythonDistDir.resolve("../wheels"); // Based on our manual copy structure
                // Adjust logic: The wheels were in build/python_staging/wheels.
                // We need to be careful. If wheels are not there, try online install or skip?
                // For now, let's just try to run pip.

                ProcessBuilder pipPb = new ProcessBuilder(
                        pyExe.toString(),
                        "-m", "pip", "install",
                        "--no-index",
                        "--find-links=wheels", // Relative to working dir? No, let's use absolute if possible or
                                               // relative
                        "-r", requirements.toString());
                // We assume wheels are in targetDir/wheels
                pipPb.directory(targetDir.toFile());
                pipPb.redirectErrorStream(true);
                Process pipProc = pipPb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pipProc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Pip] " + line);
                    }
                }
                pipProc.waitFor();
            }

            // Create marker
            Files.createFile(markerFile);

        } else {
            System.out.println("[Init] Embedded Python already installed at: " + pythonDistDir);
        }

        pythonHome = pythonDistDir;
        pythonExe = pythonDistDir.resolve("python.exe");
    }

    private static native void initNative(String workDir, String sourceScriptDir, String modelPath, float confidence,
            String memoryKey);

    // 4-param initialize overload for AIImageController compatibility
    public static void initialize(String workDirectory, String sourceScript, String modelPath, float confidence) {
        System.out.println("[JPyRust] Init with model: " + modelPath + ", confidence: " + confidence);

        // Generate a unique session key for Shared Memory
        String memoryKey = "JPyRust_" + java.util.UUID.randomUUID().toString();
        System.out.println("[JPyRust] Generated Session Key: " + memoryKey);

        initialize(workDirectory, sourceScript, memoryKey);
    }

    // Internal initialize with key
    private synchronized static void initialize(String workDirectory, String ignoredSourceScript, String memoryKey) {
        if (initialized) {
            return;
        }

        workDir = workDirectory;
        // sourceScriptDir is no longer needed as we use the embedded one

        System.out.println("=== JPyRust IPC Initialization ===");
        System.out.println("[Init] Work Directory: " + workDir);

        try {
            Path workPath = Paths.get(workDir);
            if (!Files.exists(workPath)) {
                Files.createDirectories(workPath);
            }

            // 1. Setup Embedded Python
            setupEmbeddedPython(workPath);

            // 2. Initialize Native
            initNative(workDir, workDir, "yolov8n.pt", 0.5f, memoryKey);

            System.out.println("=== Initialization Complete ===");
            initialized = true;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize JPyRustBridge", e);
        }
    }

    // Native executeTask declaration matching Rust signature
    private native byte[] executeTask(String workDir, String taskType, String requestId, String metadata,
            ByteBuffer data, int length);

    public byte[] processImage(String workDirectory, ByteBuffer data, int length, int width, int height, int channels) {
        String requestId = java.util.UUID.randomUUID().toString();
        // Construct metadata directly as simple string "width height channels"
        String metadata = width + " " + height + " " + channels;
        return executeTask(workDirectory, "YOLO", requestId, metadata, data, length);
    }

    // 7-param processImage overload for AIImageController compatibility
    public byte[] processImage(String workDirectory, ByteBuffer data, int length, int width, int height, int channels,
            String requestId) {
        System.out.println("[JPyRust] Processing request: " + requestId);
        String metadata = width + " " + height + " " + channels;
        return executeTask(workDirectory, "YOLO", requestId, metadata, data, length);
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        return processImage(workDir, data, length, width, height, channels);
    }

    // Edge detection implementation
    public byte[] processEdgeDetection(byte[] imageData, int width, int height, int channels) {
        System.out.println("[JPyRust] Edge detection called (Native)");
        try {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(imageData.length);
            directBuffer.put(imageData);
            directBuffer.flip();

            String metadata = width + " " + height + " " + channels;
            String requestId = java.util.UUID.randomUUID().toString();

            byte[] result = executeTask(workDir, "EDGE_DETECT", requestId, metadata, directBuffer, imageData.length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // NLP processing implementation
    public String processNlp(String text) {
        System.out.println("[JPyRust] NLP processing: " + text);
        try {
            byte[] textBytes = text.getBytes("UTF-8");
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(textBytes.length);
            directBuffer.put(textBytes);
            directBuffer.flip();

            String requestId = java.util.UUID.randomUUID().toString();
            // Metadata empty for NLP, or specialized if needed
            String metadata = "TEXT";

            byte[] resultBytes = executeTask(workDir, "NLP_TEXTBLOB", requestId, metadata, directBuffer,
                    textBytes.length);

            if (resultBytes == null)
                return "{\"error\": \"Native execution failed\"}";
            return new String(resultBytes, "UTF-8");

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // Regression processing implementation
    public String processRegression(String jsonPoints) {
        System.out.println("[JPyRust] Regression processing: " + jsonPoints);
        try {
            byte[] jsonBytes = jsonPoints.getBytes("UTF-8");
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(jsonBytes.length);
            directBuffer.put(jsonBytes);
            directBuffer.flip();

            String requestId = java.util.UUID.randomUUID().toString();
            String metadata = "JSON";

            byte[] resultBytes = executeTask(workDir, "REGRESSION", requestId, metadata, directBuffer,
                    jsonBytes.length);

            if (resultBytes == null)
                return "{\"error\": \"Native execution failed\"}";
            return new String(resultBytes, "UTF-8");

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // Legacy runPythonProcess declaration removed/replaced by executeTask usage
    /*
     * private native byte[] runPythonProcess(String workDir, ByteBuffer data, int
     * length, int width, int height,
     * int channels, String requestId);
     */

    public String runPythonRaw(ByteBuffer data, int length, int width, int height, int channels) {
        String inputFilePath = workDir + "/input_image.dat";
        String outputFilePath = workDir + "/output_image.dat";
        String pythonScriptPath = workDir + "/ai_worker.py";

        System.out.println("[JPyRust] Writing to INPUT file...");

        try {
            byte[] inputBuffer = new byte[length];
            data.position(0);
            data.get(inputBuffer);

            try (FileOutputStream fos = new FileOutputStream(inputFilePath)) {
                fos.write((length >> 24) & 0xFF);
                fos.write((length >> 16) & 0xFF);
                fos.write((length >> 8) & 0xFF);
                fos.write(length & 0xFF);
                fos.write(inputBuffer);
                fos.flush();
            }

            System.out.println("[JPyRust] Input written: " + length + " bytes. Spawning Python...");

            File outputFile = new File(outputFilePath);
            if (outputFile.exists()) {
                outputFile.delete();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExe.toString(),
                    pythonHome.resolve("ai_worker.py").toString(),
                    String.valueOf(width),
                    String.valueOf(height),
                    String.valueOf(channels));

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    System.out.println("[Python STDOUT] " + line);
                }
            }

            int exitCode = process.waitFor();
            System.out.println("[JPyRust] Python exited with code: " + exitCode);

            System.out.println("[JPyRust] Reading from OUTPUT file...");

            if (!outputFile.exists()) {
                System.err.println("[JPyRust] ERROR: Output file not found!");
                return "Error: Python did not create output file";
            }

            byte[] outputBuffer;
            try (FileInputStream fis = new FileInputStream(outputFilePath)) {
                int b1 = fis.read();
                int b2 = fis.read();
                int b3 = fis.read();
                int b4 = fis.read();
                int outputLength = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;

                System.out.println("[JPyRust] Output file header says: " + outputLength + " bytes");

                outputBuffer = new byte[outputLength];
                int totalRead = 0;
                while (totalRead < outputLength) {
                    int read = fis.read(outputBuffer, totalRead, outputLength - totalRead);
                    if (read == -1)
                        break;
                    totalRead += read;
                }

                System.out.println("[JPyRust] Actually read: " + totalRead + " bytes");
            }

            data.position(0);
            data.put(outputBuffer);
            data.flip();

            System.out.println("[JPyRust] Data copied back to ByteBuffer successfully!");

            return output.toString().trim();

        } catch (Exception e) {
            e.printStackTrace();
            return "Error interacting with Python: " + e.getMessage();
        }
    }
}