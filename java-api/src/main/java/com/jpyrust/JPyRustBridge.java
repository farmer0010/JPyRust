package com.jpyrust;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JPyRustBridge implements AutoCloseable {

    private static final String EMBEDDED_PYTHON_URL = "https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip";
    private static final String EMBEDDED_PYTHON_SHA256 = "009d6bf7e3b2ddca3d784fa09f90fe54336d5b60f0e0f305c37f400bf83cfd3b";
    private static final String PIP_WHEEL_URL = "https://files.pythonhosted.org/packages/8a/6a/19e9fe04fca059ccf770861c7d5721ab4c2aebc539889e97c7977528a53b/pip-24.0-py3-none-any.whl";
    private static final String PIP_WHEEL_SHA256 = "ba0d021a166865d2265246961bec0152ff124de910c5cc39f1156ce3fa7c69dc";
    private static final List<String> AVAILABLE_EXTRAS = List.of("vision", "audio", "ml");
    private static final System.Logger LOGGER = System.getLogger("com.jpyrust");

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
    private Path pythonExe;
    private Path requirementsFile;
    private Path pythonInterpreter;
    private final List<Path> moduleDirs = new ArrayList<>();
    private final List<String> extras = new ArrayList<>();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    public JPyRustBridge(String instanceId) {
        this.instanceId = instanceId;
    }

    public synchronized JPyRustBridge requirements(Path file) {
        if (initialized) {
            throw new IllegalStateException("requirements() must be called before initialize()");
        }
        this.requirementsFile = file;
        return this;
    }

    public synchronized JPyRustBridge moduleDir(Path dir) {
        if (initialized) {
            throw new IllegalStateException("moduleDir() must be called before initialize()");
        }
        moduleDirs.add(dir.toAbsolutePath().normalize());
        return this;
    }

    public synchronized JPyRustBridge python(Path interpreter) {
        if (initialized) {
            throw new IllegalStateException("python() must be called before initialize()");
        }
        this.pythonInterpreter = interpreter;
        return this;
    }

    public synchronized JPyRustBridge extras(String... names) {
        if (initialized) {
            throw new IllegalStateException("extras() must be called before initialize()");
        }
        for (String name : names) {
            if (!AVAILABLE_EXTRAS.contains(name)) {
                throw new IllegalArgumentException("Unknown extra: " + name + " (available: " + AVAILABLE_EXTRAS + ")");
            }
            if (!extras.contains(name)) {
                extras.add(name);
            }
        }
        return this;
    }

    public synchronized void initialize() {
        String userHome = System.getProperty("user.home");
        Path defaultWorkDir = Paths.get(userHome, ".jpyrust", instanceId);
        initialize(defaultWorkDir.toString());
    }

    public synchronized void initialize(String workDirectory) {
        initialize(workDirectory, "yolov8n.pt", 0.5f);
    }

    public synchronized void initialize(String workDirectory, String modelPath, float confidence) {
        String memoryKey = "JR" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        initialize(workDirectory, modelPath, confidence, memoryKey);
    }

    public synchronized void initialize(String workDirectory, String modelPath, float confidence, String memoryKey) {
        initialize(workDirectory, modelPath, confidence, memoryKey, "");
    }

    public synchronized void initialize(String workDirectory, String modelPath, float confidence, String memoryKey,
            String whisperModelPath) {
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

            lifecycleLock.writeLock().lock();
            try {
                initNative(workDir, workDir, modelPath, confidence, memoryKey, whisperModelPath);
                initialized = true;
            } finally {
                lifecycleLock.writeLock().unlock();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize JPyRustBridge", e);
        }
    }

    private void setupEmbeddedPython(Path targetDir) throws Exception {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        NativeLoader.extractFile("/ai_worker.py", targetDir.resolve("ai_worker.py"));
        NativeLoader.extractFile("/jpyrust.py", targetDir.resolve("jpyrust.py"));
        Path requirements = targetDir.resolve("requirements.txt");
        if (requirementsFile != null) {
            Files.copy(requirementsFile, requirements, StandardCopyOption.REPLACE_EXISTING);
        } else {
            NativeLoader.extractFile("/requirements.txt", requirements);
        }
        for (String extra : extras) {
            NativeLoader.extractFile("/extras/" + extra + ".py", targetDir.resolve("extras/" + extra + ".py"));
            Path extraRequirements = targetDir.resolve("extras/" + extra + ".txt");
            NativeLoader.extractFile("/extras/" + extra + ".txt", extraRequirements);
            Files.writeString(requirements, "\n" + Files.readString(extraRequirements), StandardOpenOption.APPEND);
        }
        writeWorkerConfig(targetDir.resolve("worker.json"));
        String requirementsHash = sha256Hex(Files.readAllBytes(requirements));

        if (isWindows && pythonInterpreter == null) {
            setupWindowsEmbeddedPython(targetDir, requirementsHash);
        } else {
            setupVenvPython(targetDir, requirementsHash, isWindows);
        }
    }

    private void writeWorkerConfig(Path configFile) throws Exception {
        StringBuilder json = new StringBuilder("{\"extras\": [");
        for (int i = 0; i < extras.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(extras.get(i)).append('"');
        }
        json.append("], \"module_dirs\": [");
        for (int i = 0; i < moduleDirs.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(moduleDirs.get(i).toString().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        json.append("]}\n");
        Files.writeString(configFile, json.toString());
    }

    private static boolean isInstalled(Path markerFile, String requirementsHash) throws Exception {
        return Files.exists(markerFile) && Files.readString(markerFile).trim().equals(requirementsHash);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void setupWindowsEmbeddedPython(Path targetDir, String requirementsHash) throws Exception {
        Path pythonDistDir = targetDir.resolve("python_dist");
        Path markerFile = pythonDistDir.resolve(".installed");
        Path pyExe = pythonDistDir.resolve("python.exe");

        if (!Files.exists(markerFile)) {
            Path downloads = targetDir.resolve("downloads");
            Path pythonZip = download(EMBEDDED_PYTHON_URL, EMBEDDED_PYTHON_SHA256,
                    downloads.resolve("python-3.11.9-embed-amd64.zip"));
            NativeLoader.extractZip(pythonZip, pythonDistDir);
            Files.write(pythonDistDir.resolve("python311._pth"), "python311.zip\n.\nimport site".getBytes());

            Path pipWheel = download(PIP_WHEEL_URL, PIP_WHEEL_SHA256, downloads.resolve("pip-24.0-py3-none-any.whl"));
            NativeLoader.extractZip(pipWheel, pythonDistDir.resolve("Lib/site-packages"));
            runOrFail("Failed to pre-install setuptools/wheel in " + pythonDistDir, targetDir,
                    pyExe.toString(), "-m", "pip", "install", "setuptools<70", "wheel");
        }

        if (!isInstalled(markerFile, requirementsHash)) {
            runOrFail("pip install failed for requirements.txt in " + pythonDistDir, targetDir,
                    pyExe.toString(), "-m", "pip", "install", "--no-build-isolation",
                    "-r", targetDir.resolve("requirements.txt").toString());
            Files.writeString(markerFile, requirementsHash);
        }

        this.pythonExe = pyExe;
    }

    private Path download(String url, String expectedSha256, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        log("INFO", "Downloading " + url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new RuntimeException("Download failed (HTTP " + response.statusCode() + "): " + url);
        }
        String actual = sha256Hex(Files.readAllBytes(target));
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(target);
            throw new RuntimeException("Checksum mismatch for " + url + ": expected " + expectedSha256 + ", got " + actual);
        }
        return target;
    }

    private void runOrFail(String failureMessage, Path workingDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        drainQuietly(process);
        if (process.waitFor() != 0) {
            throw new RuntimeException(failureMessage);
        }
    }

    private void setupVenvPython(Path targetDir, String requirementsHash, boolean isWindows) throws Exception {
        Path markerFile = targetDir.resolve(".installed");
        Path venvDir = targetDir.resolve("venv");
        Path venvPython = venvDir.resolve(isWindows ? "Scripts/python.exe" : "bin/python3");
        Path requirements = targetDir.resolve("requirements.txt");

        if (!Files.exists(markerFile)) {
            if (pythonInterpreter != null) {
                runOrFail("Failed to create venv with " + pythonInterpreter + " at: " + venvDir, targetDir,
                        pythonInterpreter.toString(), "-m", "venv", "--system-site-packages", venvDir.toString());
            } else {
                runOrFail("Failed to create Python 3.11 venv via uv at: " + venvDir, targetDir,
                        findUv(), "venv", "--python", "3.11", "--seed", venvDir.toString());
            }

            // openai-whisper ships no wheel on PyPI (sdist only), and its legacy setup.py
            // imports pkg_resources at build time. Pip's default isolated build overlay only
            // installs the latest setuptools/wheel, which no longer guarantees pkg_resources
            // there — so we pre-install an older setuptools into the venv itself and build
            // against that with --no-build-isolation. This only affects packages that need a
            // build step (i.e. openai-whisper); everything else in requirements.txt ships
            // wheels and installs unaffected by either flag.
            ProcessBuilder setuptoolsPb = new ProcessBuilder(
                    venvPython.toString(), "-m", "pip", "install", "setuptools<70", "wheel");
            setuptoolsPb.directory(targetDir.toFile());
            setuptoolsPb.redirectErrorStream(true);
            Process setuptoolsProc = setuptoolsPb.start();
            drainQuietly(setuptoolsProc);
            if (setuptoolsProc.waitFor() != 0) {
                throw new RuntimeException("Failed to pre-install setuptools/wheel in " + venvDir);
            }
        }

        if (!isInstalled(markerFile, requirementsHash)) {
            ProcessBuilder pipPb = new ProcessBuilder(
                    venvPython.toString(), "-m", "pip", "install", "--no-build-isolation",
                    "-r", requirements.toString());
            pipPb.directory(targetDir.toFile());
            pipPb.redirectErrorStream(true);
            Process pipProc = pipPb.start();
            drainQuietly(pipProc);
            if (pipProc.waitFor() != 0) {
                throw new RuntimeException("pip install failed for requirements.txt in " + venvDir);
            }

            Files.writeString(markerFile, requirementsHash);
        }

        this.pythonExe = venvPython;
    }

    private String findUv() {
        try {
            Process p = new ProcessBuilder("uv", "--version").redirectErrorStream(true).start();
            drainQuietly(p);
            if (p.waitFor() == 0) {
                return "uv";
            }
        } catch (Exception ignored) {
        }
        throw new RuntimeException(
                "uv가 설치되어 있지 않습니다. https://docs.astral.sh/uv/getting-started/installation/ 를 참고해 설치 후 다시 시도하세요.\n"
                        + "  macOS/Linux: curl -LsSf https://astral.sh/uv/install.sh | sh");
    }

    private void drainQuietly(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log("DEBUG", line);
            }
        }
    }

    public void log(String level, String msg) {
        System.Logger.Level loggerLevel = switch (level) {
            case "DEBUG" -> System.Logger.Level.DEBUG;
            case "WARN" -> System.Logger.Level.WARNING;
            case "ERROR" -> System.Logger.Level.ERROR;
            default -> System.Logger.Level.INFO;
        };
        LOGGER.log(loggerLevel, "[" + instanceId + "] " + msg);
    }

    private native void initNative(String workDir, String sourceScriptDir, String modelPath, float confidence,
            String memoryKey, String whisperModelPath);

    private native void closeNative();

    private native byte[] executeTask(String workDir, String taskType, String requestId, String metadata,
            ByteBuffer data, int length);

    private byte[] invokeTask(String taskType, String requestId, String metadata, ByteBuffer data, int length) {
        lifecycleLock.readLock().lock();
        try {
            if (!initialized) {
                throw new IllegalStateException("JPyRustBridge is not initialized or already closed");
            }
            return executeTask(workDir, taskType, requestId, metadata, data, length);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public String sendTask(String taskType, String metadata) {
        String requestId = java.util.UUID.randomUUID().toString();
        byte[] result = invokeTask(taskType, requestId, metadata, ByteBuffer.allocateDirect(1), 0);
        return new String(result, java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] call(String taskName, byte[] input, String args) {
        if (args.contains("\n") || args.contains("\r")) {
            throw new IllegalArgumentException("args must not contain line breaks");
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(input.length, 1));
        buffer.put(input);
        buffer.flip();
        String requestId = java.util.UUID.randomUUID().toString();
        return invokeTask(taskName, requestId, args, buffer, input.length);
    }

    public String call(String taskName, String input) {
        byte[] result = call(taskName, input.getBytes(java.nio.charset.StandardCharsets.UTF_8), "");
        return new String(result, java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels) {
        String requestId = java.util.UUID.randomUUID().toString();
        String metadata = width + " " + height + " " + channels;
        return invokeTask("YOLO", requestId, metadata, data, length);
    }

    public byte[] processImage(ByteBuffer data, int length, int width, int height, int channels, String requestId) {
        String metadata = width + " " + height + " " + channels;
        return invokeTask("YOLO", requestId, metadata, data, length);
    }

    public byte[] processAudio(ByteBuffer data, int length, int sampleRate) {
        String requestId = java.util.UUID.randomUUID().toString();
        String metadata = String.valueOf(sampleRate);
        return invokeTask("WHISPER", requestId, metadata, data, length);
    }

    public byte[] processEdgeDetection(byte[] imageData, int width, int height, int channels) {
        try {
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(imageData.length);
            directBuffer.put(imageData);
            directBuffer.flip();

            String metadata = width + " " + height + " " + channels;
            String requestId = java.util.UUID.randomUUID().toString();

            byte[] result = invokeTask("EDGE_DETECT", requestId, metadata, directBuffer,
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

            byte[] resultBytes = invokeTask("NLP_TEXTBLOB", requestId, metadata, directBuffer,
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

            byte[] resultBytes = invokeTask("REGRESSION", requestId, metadata, directBuffer,
                    jsonBytes.length);

            if (resultBytes == null)
                return "{\"error\": \"Native execution failed\"}";
            return new String(resultBytes, "UTF-8");
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    public String runPythonRaw(ByteBuffer data, int length, int width, int height, int channels) {
        String requestId = java.util.UUID.randomUUID().toString();
        String inputFilePath = workDir + "/input_" + requestId + ".dat";
        String outputFilePath = workDir + "/output_" + requestId + ".dat";
        Path scriptPath = Paths.get(workDir, "ai_worker.py");

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
                    scriptPath.toString(),
                    "--daemon",
                    "--instance-id", instanceId);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String response;
            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    java.io.OutputStream stdin = process.getOutputStream()) {

                String ready = stdout.readLine();
                if (ready == null || !ready.trim().equals("READY")) {
                    process.destroyForcibly();
                    return "Error: Python worker did not signal READY (got: " + ready + ")";
                }

                String command = "EXECUTE YOLO " + requestId + " " + width + " " + height + " " + channels + "\n";
                stdin.write(command.getBytes("UTF-8"));
                stdin.flush();

                response = stdout.readLine();

                stdin.write("EXIT\n".getBytes("UTF-8"));
                stdin.flush();
            }

            process.waitFor();

            if (response == null || !response.startsWith("DONE")) {
                return "Error: Python worker did not complete the task (got: " + response + ")";
            }

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

            return response;

        } catch (Exception e) {
            return "Error interacting with Python: " + e.getMessage();
        } finally {
            new File(inputFilePath).delete();
            new File(outputFilePath).delete();
        }
    }

    public synchronized void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (initialized) {
                closeNative();
                initialized = false;
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
}
