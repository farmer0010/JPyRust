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

    // Configurable paths - set via initialize()
    private static String workDir = "C:/jpyrust_temp"; // Default fallback
    private static String sourceScriptDir = "d:/JPyRust/python-core"; // Default fallback

    private static final int HEADER_SIZE = 4; // 4 bytes for length
    private static boolean initialized = false;

    // Static native library loading
    static {
        try {
            System.loadLibrary("jpyrust_bridge");
            System.out.println("[JPyRustBridge] Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JPyRustBridge] Failed to load native library: " + e.getMessage());
        }
    }

    /**
     * Initialize with configurable paths from application.properties/yml
     */
    public synchronized static void initialize(String workDirectory, String sourceScript) {
        if (initialized) {
            return;
        }

        workDir = workDirectory;
        sourceScriptDir = sourceScript;

        System.out.println("=== JPyRust IPC Initialization ===");
        System.out.println("[Init] Work Directory: " + workDir);
        System.out.println("[Init] Source Script Dir: " + sourceScriptDir);

        try {
            // Ensure work directory exists
            File tempDir = new File(workDir);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
                System.out.println("[Init] Created work directory: " + workDir);
            }

            // Copy ai_worker.py from source to work location
            Path sourceScript2 = Paths.get(sourceScriptDir, "ai_worker.py");
            Path targetScript = Paths.get(workDir, "ai_worker.py");

            if (Files.exists(sourceScript2)) {
                Files.copy(sourceScript2, targetScript, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Init] Copied ai_worker.py to " + targetScript);
            } else {
                System.err.println("[Warning] Source python script not found at " + sourceScript2);
            }

            // Also initialize the native Rust side
            initNative(workDir, sourceScriptDir);

            System.out.println("=== Initialization Complete ===");
            initialized = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Legacy initialize method (uses defaults)
     */
    public synchronized static void initialize() {
        initialize(workDir, sourceScriptDir);
    }

    /**
     * Native initialization method
     */
    private static native void initNative(String workDir, String sourceScriptDir);

    /**
     * Process image via Rust bridge with configurable work directory
     */
    public byte[] processImage(String workDirectory, ByteBuffer data, int length, int width, int height, int channels) {
        return runPythonProcess(workDirectory, data, length, width, height, channels);
    }

    /**
     * Legacy processImage method (uses default workDir)
     */
    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        return runPythonProcess(workDir, data, length, width, height, channels);
    }

    /**
     * Native method - now accepts workDir parameter
     */
    private native byte[] runPythonProcess(String workDir, ByteBuffer data, int length, int width, int height,
            int channels);

    /**
     * Split File IPC Strategy (Pure Java fallback - kept for reference):
     * 1. Write to input_image.dat
     * 2. Python reads input, writes to output_image.dat
     * 3. Java reads from output_image.dat (fresh file, no cache)
     */
    public String runPythonRaw(ByteBuffer data, int length, int width, int height, int channels) {
        String inputFilePath = workDir + "/input_image.dat";
        String outputFilePath = workDir + "/output_image.dat";
        String pythonScriptPath = workDir + "/ai_worker.py";

        System.out.println("[JPyRust] Writing to INPUT file...");

        try {
            // 1. Write to INPUT file
            byte[] inputBuffer = new byte[length];
            data.position(0);
            data.get(inputBuffer);

            try (FileOutputStream fos = new FileOutputStream(inputFilePath)) {
                // Write 4-byte length header (Big Endian)
                fos.write((length >> 24) & 0xFF);
                fos.write((length >> 16) & 0xFF);
                fos.write((length >> 8) & 0xFF);
                fos.write(length & 0xFF);
                // Write pixel data
                fos.write(inputBuffer);
                fos.flush();
            }

            System.out.println("[JPyRust] Input written: " + length + " bytes. Spawning Python...");

            // 2. Delete output file if exists (ensure fresh read)
            File outputFile = new File(outputFilePath);
            if (outputFile.exists()) {
                outputFile.delete();
            }

            // 3. Spawn Python Process
            ProcessBuilder pb = new ProcessBuilder(
                    "python",
                    pythonScriptPath,
                    String.valueOf(width),
                    String.valueOf(height),
                    String.valueOf(channels));

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 4. Read Python Output
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

            // 5. Read from OUTPUT file (completely fresh, no cache!)
            System.out.println("[JPyRust] Reading from OUTPUT file...");

            if (!outputFile.exists()) {
                System.err.println("[JPyRust] ERROR: Output file not found!");
                return "Error: Python did not create output file";
            }

            byte[] outputBuffer;
            try (FileInputStream fis = new FileInputStream(outputFilePath)) {
                // Read 4-byte length header
                int b1 = fis.read();
                int b2 = fis.read();
                int b3 = fis.read();
                int b4 = fis.read();
                int outputLength = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;

                System.out.println("[JPyRust] Output file header says: " + outputLength + " bytes");

                // Read pixel data
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

            // 6. Write back to original ByteBuffer
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