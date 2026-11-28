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
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JPyRustBridge implements AutoCloseable {

    private static final String EMBEDDED_PYTHON_URL = "https://www.python.org/ftp/python/3.11.9/python-3.11.9-embed-amd64.zip";
    private static final String EMBEDDED_PYTHON_SHA256 = "009d6bf7e3b2ddca3d784fa09f90fe54336d5b60f0e0f305c37f400bf83cfd3b";
    private static final String PIP_WHEEL_URL = "https://files.pythonhosted.org/packages/8a/6a/19e9fe04fca059ccf770861c7d5721ab4c2aebc539889e97c7977528a53b/pip-24.0-py3-none-any.whl";
    private static final String PIP_WHEEL_SHA256 = "ba0d021a166865d2265246961bec0152ff124de910c5cc39f1156ce3fa7c69dc";
    private static final String STANDALONE_RELEASE = "20261110";
    private static final String STANDALONE_PYTHON = "3.11.18";
    private static final Map<String, String> STANDALONE_SHA256 = Map.of(
            "aarch64-apple-darwin", "73f1ed14486aa278d331e95f7d6d452f5cfb69d90b418e26e62fd4846d53557d",
            "x86_64-apple-darwin", "9ff3ea8df9037e692939a3f2348f5fd760239ab8eb9d0d2d11d099457dda63a0",
            "aarch64-unknown-linux-gnu", "623a370be4489d8c35c7a274be02f3007a030618cda29025780beb416c6c8861",
            "x86_64-unknown-linux-gnu", "ac0b758b6ae4ba1cb87f7091eb6bb17c9a383a9bcdb5c18493cdd4f34e35ae8c");
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
    private String modelPath = "yolov8n.pt";
    private float confidence = 0.5f;
    private String whisperModelPath = "";
    private Duration startupTimeout = Duration.ofSeconds(600);
    private Duration taskTimeout = Duration.ofSeconds(300);
    private int workerCount = 1;
    private final List<JPyRustBridge> extraWorkers = new ArrayList<>();
    private BlockingQueue<JPyRustBridge> idleWorkers;
    private ExecutorService asyncExecutor;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    public JPyRustBridge(String instanceId) {
        this.instanceId = instanceId;
    }

    public synchronized JPyRustBridge requirements(Path file) {
        requireNotInitialized("requirements");
        this.requirementsFile = file;
        return this;
    }

    public synchronized JPyRustBridge moduleDir(Path dir) {
        requireNotInitialized("moduleDir");
        moduleDirs.add(dir.toAbsolutePath().normalize());
        return this;
    }

    public synchronized JPyRustBridge python(Path interpreter) {
        requireNotInitialized("python");
        this.pythonInterpreter = interpreter;
        return this;
    }

    public synchronized JPyRustBridge extras(String... names) {
        requireNotInitialized("extras");
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

    public synchronized JPyRustBridge model(String path, float confidence) {
        requireNotInitialized("model");
        this.modelPath = path;
        this.confidence = confidence;
        return this;
    }

    public synchronized JPyRustBridge whisperModel(String path) {
        requireNotInitialized("whisperModel");
        this.whisperModelPath = path;
        return this;
    }

    public synchronized JPyRustBridge timeouts(Duration startup, Duration task) {
        requireNotInitialized("timeouts");
        if (startup.isNegative() || startup.isZero() || task.isNegative() || task.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        this.startupTimeout = startup;
        this.taskTimeout = task;
        return this;
    }

    public synchronized JPyRustBridge workers(int count) {
        requireNotInitialized("workers");
        if (count < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        this.workerCount = count;
        return this;
    }

    private void requireNotInitialized(String method) {
        if (initialized) {
            throw new IllegalStateException(method + "() must be called before initialize()");
        }
    }

    private static String newMemoryKey() {
        return "JR" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public synchronized void initialize() {
        String userHome = System.getProperty("user.home");
        Path defaultWorkDir = Paths.get(userHome, ".jpyrust", instanceId);
        initialize(defaultWorkDir.toString());
    }

    public synchronized void initialize(String workDirectory) {
        initialize(workDirectory, modelPath, confidence);
    }

    public synchronized void initialize(String workDirectory, String modelPath, float confidence) {
        initialize(workDirectory, modelPath, confidence, newMemoryKey());
    }

    public synchronized void initialize(String workDirectory, String modelPath, float confidence, String memoryKey) {
        initialize(workDirectory, modelPath, confidence, memoryKey, whisperModelPath);
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
                long startupMs = startupTimeout.toMillis();
                long taskMs = taskTimeout.toMillis();
                initNative(workDir, workDir, modelPath, confidence, memoryKey, whisperModelPath, startupMs, taskMs);
                idleWorkers = new ArrayBlockingQueue<>(workerCount);
                idleWorkers.add(this);
                for (int i = 1; i < workerCount; i++) {
                    JPyRustBridge worker = new JPyRustBridge(instanceId);
                    worker.initNative(workDir, workDir, modelPath, confidence, newMemoryKey(), whisperModelPath,
                            startupMs, taskMs);
                    extraWorkers.add(worker);
                    idleWorkers.add(worker);
                }
                initialized = true;
            } catch (RuntimeException e) {
                closeWorkers();
                throw e;
            } finally {
                lifecycleLock.writeLock().unlock();
            }

        } catch (Exception e) {
            throw new JPyRustException("Failed to initialize JPyRustBridge", e);
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
            Path pythonZip = download(EMBEDDED_PYTHON_URL, EMBEDDED_PYTHON_SHA256,
                    cacheDir().resolve("python-3.11.9-embed-amd64.zip"));
            NativeLoader.extractZip(pythonZip, pythonDistDir);
            Files.write(pythonDistDir.resolve("python311._pth"), "python311.zip\n.\nimport site".getBytes());

            Path pipWheel = download(PIP_WHEEL_URL, PIP_WHEEL_SHA256, cacheDir().resolve("pip-24.0-py3-none-any.whl"));
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

    private static Path cacheDir() {
        return Paths.get(System.getProperty("user.home"), ".jpyrust", "cache");
    }

    private Path standalonePython() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String triple = NativeLoader.normalizeArch(System.getProperty("os.arch"))
                + (os.contains("mac") ? "-apple-darwin" : "-unknown-linux-gnu");
        String sha256 = STANDALONE_SHA256.get(triple);
        if (sha256 == null) {
            throw new JPyRustException("No Python download for " + triple + "; pass python(Path) to use an installed interpreter");
        }
        String name = "cpython-" + STANDALONE_PYTHON + "+" + STANDALONE_RELEASE + "-" + triple + "-install_only";
        Path home = cacheDir().resolve(name);
        Path python = home.resolve("python/bin/python3");
        if (Files.exists(python)) {
            return python;
        }
        Path archive = download("https://github.com/astral-sh/python-build-standalone/releases/download/"
                + STANDALONE_RELEASE + "/" + name.replace("+", "%2B") + ".tar.gz", sha256, cacheDir().resolve(name + ".tar.gz"));
        Path staging = Files.createTempDirectory(cacheDir(), "extract-");
        runOrFail("Failed to extract " + archive, cacheDir(), "tar", "-xzf", archive.toString(), "-C", staging.toString());
        try {
            Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileSystemException e) {
            if (!Files.exists(python)) {
                throw e;
            }
            try (var paths = Files.walk(staging)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.deleteIfExists(archive);
        return python;
    }

    private Path download(String url, String expectedSha256, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && sha256Hex(Files.readAllBytes(target)).equals(expectedSha256)) {
            return target;
        }
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
                runOrFail("Failed to create Python " + STANDALONE_PYTHON + " venv at: " + venvDir, targetDir,
                        standalonePython().toString(), "-m", "venv", venvDir.toString());
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
            String memoryKey, String whisperModelPath, long startupTimeoutMs, long taskTimeoutMs);

    private native void closeNative();

    private native byte[] executeTask(String workDir, String taskType, String requestId, String metadata,
            ByteBuffer data, int length);

    private byte[] invokeTask(String taskType, String requestId, String metadata, ByteBuffer data, int length) {
        lifecycleLock.readLock().lock();
        try {
            if (!initialized) {
                throw new IllegalStateException("JPyRustBridge is not initialized or already closed");
            }
            JPyRustBridge worker = idleWorkers.take();
            try {
                return worker.executeTask(workDir, taskType, requestId, metadata, data, length);
            } finally {
                idleWorkers.add(worker);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JPyRustException("Interrupted while waiting for a free Python worker", e);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public CompletableFuture<byte[]> callAsync(String taskName, byte[] input, String args) {
        ExecutorService executor;
        synchronized (this) {
            if (!initialized) {
                throw new IllegalStateException("JPyRustBridge is not initialized or already closed");
            }
            if (asyncExecutor == null) {
                asyncExecutor = Executors.newFixedThreadPool(workerCount, runnable -> {
                    Thread thread = new Thread(runnable, "jpyrust-" + instanceId + "-async");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            executor = asyncExecutor;
        }
        return CompletableFuture.supplyAsync(() -> call(taskName, input, args), executor);
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
        return call("EDGE_DETECT", imageData, width + " " + height + " " + channels);
    }

    public String processNlp(String text) {
        return call("NLP_TEXTBLOB", text);
    }

    public String processRegression(String jsonPoints) {
        return call("REGRESSION", jsonPoints);
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
                closeWorkers();
                initialized = false;
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void closeWorkers() {
        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
            asyncExecutor = null;
        }
        for (JPyRustBridge worker : extraWorkers) {
            worker.closeNative();
        }
        extraWorkers.clear();
        closeNative();
    }
}
