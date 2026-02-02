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

            // 2. Initialize Native (if still needed for Shmem, otherwise this might be
            // optional)
            // Assuming initNative is still relevant for Shared Memory setup on Java side
            // If the native lib expects just a dir, we pass it.
            initNative(workDir, workDir); // Pass workDir as script dir too since ai_worker is there

            System.out.println("=== Initialization Complete ===");
            initialized = true;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize JPyRustBridge", e);
        }
    }

    private static void setupEmbeddedPython(Path targetDir) throws Exception {
        Path pythonDistDir = targetDir.resolve("python_dist");
        Path markerFile = pythonDistDir.resolve(".installed");

        // Check if we need to extract
        // We check for python executable or the marker
        // To be safe, checking for marker is better if we want to ensure bootstrap ran
        if (!Files.exists(markerFile)) {
            System.out.println("[Init] Extracting user embedded python to: " + pythonDistDir);

            // Extract the big zip
            // NativeLoader.extractZip expects "/python_dist.zip" to be in classpath
            NativeLoader.extractZip("/python_dist.zip", pythonDistDir);

            // Run Bootstrap
            System.out.println("[Init] Running bootstrap script...");
            Path bootstrapScript = pythonDistDir.resolve("bootstrap.py");
            Path pyExe = pythonDistDir.resolve("python.exe"); // It's at root of python_dist (from build script
                                                              // structure)

            if (!Files.exists(pyExe)) {
                // Fallback or error: maybe it's in a subdir?
                // Based on build script:
                // into(pythonDistDir) -> python.exe is at pythonDistDir root
            }

            ProcessBuilder pb = new ProcessBuilder(
                    pyExe.toString(),
                    bootstrapScript.toString());
            pb.directory(pythonDistDir.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            StringBuilder bootstrapLog = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Bootstrap] " + line);
                    bootstrapLog.append(line).append("\n");
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Bootstrap failed with exit code: " + exitCode + "\nLog:\n" + bootstrapLog.toString());
            }

            // Marker should be created by bootstrap, but we double check or create it if we
            // need to track "java side" init
        } else {
            System.out.println("[Init] Embedded Python already installed at: " + pythonDistDir);
        }

        pythonHome = pythonDistDir;
        pythonExe = pythonDistDir.resolve("python.exe");
    }

    private static native void initNative(String workDir, String sourceScriptDir);

    public byte[] processImage(String workDirectory, ByteBuffer data, int length, int width, int height, int channels) {
        return runPythonProcess(workDirectory, data, length, width, height, channels);
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        return runPythonProcess(workDir, data, length, width, height, channels);
    }

    private native byte[] runPythonProcess(String workDir, ByteBuffer data, int length, int width, int height,
            int channels);

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