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

    private static final String STANDALONE_RELEASE = "20261110";
    private static final String DEFAULT_PYTHON_VERSION = "3.11";
    private static final Map<String, String> STANDALONE_PYTHON = Map.of(
            "3.11", "3.11.18",
            "3.12", "3.12.16",
            "3.13", "3.13.18",
            "3.14", "3.14.10");
    private static final List<String> EXTRAS_PYTHON_VERSIONS = List.of("3.11", "3.12");
    private static final Map<String, String> STANDALONE_SHA256 = Map.ofEntries(
            Map.entry("3.11.18-aarch64-apple-darwin", "73f1ed14486aa278d331e95f7d6d452f5cfb69d90b418e26e62fd4846d53557d"),
            Map.entry("3.11.18-x86_64-apple-darwin", "9ff3ea8df9037e692939a3f2348f5fd760239ab8eb9d0d2d11d099457dda63a0"),
            Map.entry("3.11.18-aarch64-unknown-linux-gnu", "623a370be4489d8c35c7a274be02f3007a030618cda29025780beb416c6c8861"),
            Map.entry("3.11.18-x86_64-unknown-linux-gnu", "ac0b758b6ae4ba1cb87f7091eb6bb17c9a383a9bcdb5c18493cdd4f34e35ae8c"),
            Map.entry("3.11.18-x86_64-pc-windows-msvc", "5ca7e23bbcc8933a75f4ae9c3a5e5d1ffae5fd143aec9a9d77cf5124cc6e0eef"),
            Map.entry("3.12.16-aarch64-apple-darwin", "e956e60bc9465d577ef8062059aa05cd3a2665b4b8678a2ed24e432368330b8f"),
            Map.entry("3.12.16-x86_64-apple-darwin", "83f2922437e6d546d0fcdb8c11f150a8e52787e8d9ea8651b1974e20908eb1bc"),
            Map.entry("3.12.16-aarch64-unknown-linux-gnu", "4792f81750282597fb2a3389fe18b945ed2ba6aad5e45ad406d122c58cb33081"),
            Map.entry("3.12.16-x86_64-unknown-linux-gnu", "c2f87d4b4e8db3abddb0f116c6ccfc503d0cbdbb9ee8faacddad1b679ec12e99"),
            Map.entry("3.12.16-x86_64-pc-windows-msvc", "e23fe5b16926de1d81661b1a97352e89fb167549c55a6825796ccd0f32ec7f1b"),
            Map.entry("3.13.18-aarch64-apple-darwin", "1afd3983237c2a10277345f235be730e9cb49a123e0574ed252dbf3a841a24e4"),
            Map.entry("3.13.18-x86_64-apple-darwin", "a46bcdeecd28ba253522b321dcb3806da1b0ff581479fc1d27e9613e3ac44764"),
            Map.entry("3.13.18-aarch64-unknown-linux-gnu", "d25a65036d5e149db902fa52ad65878d0c00e9301360ec197832f99063087e9e"),
            Map.entry("3.13.18-x86_64-unknown-linux-gnu", "50c774f6674740739f791c05a9177ed03e2fe02abebda9bca3512eb7309b37cc"),
            Map.entry("3.13.18-x86_64-pc-windows-msvc", "7e93813bd362c6413f30788b248590e2f169df2d17dcac81aafbcc7a6eeb542c"),
            Map.entry("3.14.10-aarch64-apple-darwin", "42031ed5f0d38777335252c8c77d64708c8010dd67c128246bdec25000672666"),
            Map.entry("3.14.10-x86_64-apple-darwin", "f6c82fcf867b4aaadd373ac97699ad95fbb9c96f9ecf9b25775943aa5458b87f"),
            Map.entry("3.14.10-aarch64-unknown-linux-gnu", "e59f83159984a55e719bb3805a0b2b5b281f645769222218084b78b92eedc07f"),
            Map.entry("3.14.10-x86_64-unknown-linux-gnu", "3fe0d7c0538eb5d190c34582992d26230532551355cbd789fabc6591621cfc07"),
            Map.entry("3.14.10-x86_64-pc-windows-msvc", "b9bee0a6f590914a495a8305701951e543b4475bb620d11661b49052b990456b"));
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
    private String pythonVersion = DEFAULT_PYTHON_VERSION;
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

    public synchronized JPyRustBridge pythonVersion(String version) {
        requireNotInitialized("pythonVersion");
        if (!STANDALONE_PYTHON.containsKey(version)) {
            throw new IllegalArgumentException("Unsupported Python version: " + version
                    + " (supported: " + STANDALONE_PYTHON.keySet().stream().sorted().toList() + ")");
        }
        this.pythonVersion = version;
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
        if (!extras.isEmpty() && pythonInterpreter == null && !EXTRAS_PYTHON_VERSIONS.contains(pythonVersion)) {
            throw new IllegalStateException("extras " + extras + " pin packages for Python "
                    + String.join(" and ", EXTRAS_PYTHON_VERSIONS) + "; pythonVersion(\"" + pythonVersion
                    + "\") cannot install them");
        }

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

        deleteRecursively(targetDir.resolve("python_dist"));
        setupVenvPython(targetDir, requirementsHash, isWindows);
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

    private static Path cacheDir() {
        return Paths.get(System.getProperty("user.home"), ".jpyrust", "cache");
    }

    private Path standalonePython() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWindows = os.contains("win");
        String arch = NativeLoader.normalizeArch(System.getProperty("os.arch"));
        String triple = arch + (isWindows ? "-pc-windows-msvc" : os.contains("mac") ? "-apple-darwin" : "-unknown-linux-gnu");
        String version = STANDALONE_PYTHON.get(pythonVersion);
        String sha256 = STANDALONE_SHA256.get(version + "-" + triple);
        if (sha256 == null) {
            throw new JPyRustException("No Python " + pythonVersion + " download for " + triple
                    + "; pass python(Path) to use an installed interpreter");
        }
        String name = "cpython-" + version + "+" + STANDALONE_RELEASE + "-" + triple + "-install_only";
        Path home = cacheDir().resolve(name);
        Path python = home.resolve(isWindows ? "python/python.exe" : "python/bin/python3");
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
            deleteRecursively(staging);
        }
        Files.deleteIfExists(archive);
        return python;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
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

        Path interpreterFile = venvDir.resolve(".jpyrust-python");
        String interpreter = pythonInterpreter != null
                ? "path:" + pythonInterpreter.toAbsolutePath()
                : "cpython-" + STANDALONE_PYTHON.get(pythonVersion) + "+" + STANDALONE_RELEASE;
        if (Files.exists(markerFile) && Files.exists(venvPython) && !Files.exists(interpreterFile)) {
            Files.writeString(interpreterFile, interpreter);
        }
        if (Files.exists(markerFile)
                && !(Files.exists(interpreterFile) && Files.readString(interpreterFile).equals(interpreter))) {
            log("INFO", "Python changed to " + interpreter + "; rebuilding " + venvDir);
            deleteRecursively(venvDir);
            Files.delete(markerFile);
        }

        if (!Files.exists(markerFile)) {
            deleteRecursively(venvDir);
            if (pythonInterpreter != null) {
                runOrFail("Failed to create venv with " + pythonInterpreter + " at: " + venvDir, targetDir,
                        pythonInterpreter.toString(), "-m", "venv", "--system-site-packages", venvDir.toString());
            } else {
                runOrFail("Failed to create Python " + pythonVersion + " venv at: " + venvDir, targetDir,
                        standalonePython().toString(), "-m", "venv", venvDir.toString());
            }
            Files.writeString(interpreterFile, interpreter);

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
