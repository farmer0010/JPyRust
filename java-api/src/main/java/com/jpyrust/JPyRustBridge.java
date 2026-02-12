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

    static {
        try {
            NativeLoader.load("jpyrust");
        } catch (Throwable e) {
            throw new RuntimeException("Fatal: Native library load failed", e);
        }
    }

    private final String instanceId;
    private long nativePtr = 0;
    private boolean initialized = false;
    private String workDir;
    private Path pythonHome;
    private Path pythonExe;

    public JPyRustBridge(String instanceId) {
        this.instanceId = instanceId;
    }

    public synchronized void initialize() {
        String userHome = System.getProperty("user.home");
        Path defaultWorkDir = Paths.get(userHome, ".jpyrust", instanceId);
        initialize(defaultWorkDir.toString());
    }

    public synchronized void initialize(String workDirectory) {
        String memoryKey = "JPyRust_" + instanceId + "_" + java.util.UUID.randomUUID().toString();
        initialize(workDirectory, memoryKey);
    }

    public synchronized void initialize(String workDirectory, String memoryKey) {
        if (initialized) {
            return;
        }

        this.workDir = workDirectory;

        try {
            Path workPath = Paths.get(workDir);
            if (!Files.exists(workPath)) {
                Files.createDirectories(workPath);
            }

            setupEmbeddedPython(workPath);

            initNative(workDir, workDir, "yolov8n.pt", 0.5f, memoryKey);
            initialized = true;

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JPyRustBridge", e);
        }
    }

    private void setupEmbeddedPython(Path targetDir) throws Exception {
        Path pythonDistDir = targetDir.resolve("python_dist");
        Path markerFile = pythonDistDir.resolve(".installed");

        if (!Files.exists(markerFile)) {
            Path sitePackages = pythonDistDir.resolve("Lib/site-packages");
            if (Files.exists(pythonDistDir.resolve("python.exe")) &&
                    (Files.exists(sitePackages.resolve("ultralytics"))
                            || Files.exists(sitePackages.resolve("torch")))) {
                Files.createFile(markerFile);
                return;
            }

            NativeLoader.extractZip("/python_dist.zip", pythonDistDir);

            Path pthFile = pythonDistDir.resolve("python311._pth");
            if (Files.exists(pthFile)) {
                Files.write(pthFile, "python311.zip\n.\nimport site".getBytes());
            }

            Path pyExe = pythonDistDir.resolve("python.exe");
            Path requirements = targetDir.resolve("requirements.txt");

            if (Files.exists(requirements)) {
                ProcessBuilder pipPb = new ProcessBuilder(
                        pyExe.toString(),
                        "-m", "pip", "install",
                        "--no-index",
                        "--find-links=wheels",
                        "-r", requirements.toString());
                pipPb.directory(targetDir.toFile());
                pipPb.redirectErrorStream(true);
                Process pipProc = pipPb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(pipProc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                    }
                }
                pipProc.waitFor();
            }
            Files.createFile(markerFile);
        }

        this.pythonHome = pythonDistDir;
        this.pythonExe = pythonDistDir.resolve("python.exe");
    }

    public void log(String level, String msg) {
        System.out.println("[JPyRust-" + instanceId + "] [" + level + "] " + msg);
    }

    private native void initNative(String workDir, String sourceScriptDir, String modelPath, float confidence,
            String memoryKey);

    private native byte[] executeTask(String workDir, String taskType, String requestId, String metadata,
            ByteBuffer data, int length);

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        String requestId = java.util.UUID.randomUUID().toString();
        String metadata = width + " " + height + " " + channels;
        return executeTask(this.workDir, "YOLO", requestId, metadata, data, length);
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels, String requestId) {
        String metadata = width + " " + height + " " + channels;
        return executeTask(this.workDir, "YOLO", requestId, metadata, data, length);
    }

    public byte[] processEdgeDetection(byte[] imageData, int width, int height, int channels) {
        try {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(imageData.length);
            directBuffer.put(imageData);
            directBuffer.flip();

            String metadata = width + " " + height + " " + channels;
            String requestId = java.util.UUID.randomUUID().toString();

            byte[] result = executeTask(this.workDir, "EDGE_DETECT", requestId, metadata, directBuffer,
                    imageData.length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            return null;
        }
    }

    public String processNlp(String text) {
        try {
            byte[] textBytes = text.getBytes("UTF-8");
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(textBytes.length);
            directBuffer.put(textBytes);
            directBuffer.flip();

            String requestId = java.util.UUID.randomUUID().toString();
            String metadata = "TEXT";

            byte[] resultBytes = executeTask(this.workDir, "NLP_TEXTBLOB", requestId, metadata, directBuffer,
                    textBytes.length);

            if (resultBytes == null)
                return "{\"error\": \"Native execution failed\"}";
            return new String(resultBytes, "UTF-8");
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    public String processRegression(String jsonPoints) {
        try {
            byte[] jsonBytes = jsonPoints.getBytes("UTF-8");
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(jsonBytes.length);
            directBuffer.put(jsonBytes);
            directBuffer.flip();

            String requestId = java.util.UUID.randomUUID().toString();
            String metadata = "JSON";

            byte[] resultBytes = executeTask(this.workDir, "REGRESSION", requestId, metadata, directBuffer,
                    jsonBytes.length);

            if (resultBytes == null)
                return "{\"error\": \"Native execution failed\"}";
            return new String(resultBytes, "UTF-8");
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    public String runPythonRaw(ByteBuffer data, int length, int width, int height, int channels) {
        String inputFilePath = workDir + "/input_image.dat";
        String outputFilePath = workDir + "/output_image.dat";

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
                }
            }

            process.waitFor();

            if (!outputFile.exists()) {
                return "Error: Python did not create output file";
            }

            byte[] outputBuffer;
            try (FileInputStream fis = new FileInputStream(outputFilePath)) {
                int b1 = fis.read();
                int b2 = fis.read();
                int b3 = fis.read();
                int b4 = fis.read();
                int outputLength = (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;

                outputBuffer = new byte[outputLength];
                int totalRead = 0;
                while (totalRead < outputLength) {
                    int read = fis.read(outputBuffer, totalRead, outputLength - totalRead);
                    if (read == -1)
                        break;
                    totalRead += read;
                }
            }

            data.position(0);
            data.put(outputBuffer);
            data.flip();

            return output.toString().trim();

        } catch (Exception e) {
            return "Error interacting with Python: " + e.getMessage();
        }
    }
}
