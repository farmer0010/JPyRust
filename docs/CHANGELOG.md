# Changelog

* **Unreleased**
    * **Feature:** Streaming. A task that `yield`s can be read with `callStream(task, input, args)` as a `java.util.stream.Stream`; values arrive as they are produced, the worker waits for Java before producing the next one, and closing the stream stops the generator (its `finally` blocks run). The task timeout applies per value. `JPyRustTemplate.callStream` records one timer per stream, and `examples/spring-boot` serves a generator as Server-Sent Events. Calling a generator task with `call` now fails with a clear `TaskFailedException`.
    * **Feature:** `NDArray` sends typed, shaped arrays to Python and back: `bridge.call(task, NDArray.of(floats, 32, 128), args)` hands the task a `numpy.ndarray` and returns the array it produces. The wire format is NumPy's `.npy`, so there is no JSON or per-element encoding; float32, float64, int8/16/32/64, uint8 and bool are supported. Any task returning a `numpy.ndarray` now produces `.npy` bytes instead of failing JSON serialization.
    * **Feature:** `pythonVersion("3.12")` chooses the downloaded CPython: 3.11 (default), 3.12, 3.13 or 3.14 (`jpyrust.python-version` in the Spring starter). Changing it rebuilds the instance's venv. The core requirements pin `numpy` 1.26.4 on 3.11–3.12 and 2.3.5 on 3.13+; the built-in extras stay on 3.11–3.12 and fail fast on newer versions.
    * **Behavior Change:** Windows downloads CPython 3.11.18 from python-build-standalone and builds a venv like macOS and Linux, instead of python.org's embeddable 3.11.9 plus a pip wheel. Existing Windows instance directories are reinstalled once on the next `initialize()`.

* **v2.0.1**
    * **Fix:** `jpyrust-spring-boot-starter` registers the `jpyRust` health indicator and the `jpyrust.calls` timer on Spring Boot 4. Boot 4 moved the health and metrics classes to new packages, so on 4.x both were silently skipped while calls still worked. The starter is now tested on Boot 3.2.1 and 4.2.0.
    * **Chore:** `windows-sys` 0.61, GitHub Actions updated, the unused `lazy_static` dependency removed. The examples and `demo-web` use Spring Boot 4.2.

* **v2.0.0**
    * **Upgrading from 1.x** — read these first:
        * **Built-in tasks are opt-in extras.** `vision` (YOLO, edge detection), `audio` (Whisper) and `ml` (TextBlob sentiment, regression). The default install is only `numpy`, so `ultralytics` (AGPL-3.0), `torch` and Whisper are not installed unless you call `extras("vision")` / `extras("audio")` / `extras("ml")`. Calling a built-in task without its extra fails with `Task YOLO requires extras("vision")`.
        * **Failures throw instead of returning `null` or an error string.** All failures are a `JPyRustException` (a `RuntimeException`): `TaskFailedException` (the Python task raised), `WorkerTimeoutException` (the task exceeded the timeout; the worker is restarted) and `WorkerCrashedException` (the worker died, e.g. a segfault). This covers `processImage`, `processAudio`, `processEdgeDetection`, `processNlp`, `processRegression` and `sendTask`. Calls before `initialize()` or after `close()` throw `IllegalStateException`, and `initialize()` throws if the Python worker fails to start. See *Errors & Timeouts* in the [README](../README.md).
        * **Python is downloaded on first run.** macOS and Linux no longer need `uv` or a system Python: the first `initialize()` downloads a portable CPython 3.11.18 (python-build-standalone) and verifies its SHA-256. Windows no longer bundles a ~400 MB Python in the JAR; it downloads python.org's embeddable Python 3.11.9 and a pinned `pip` wheel (both SHA-256 verified). Downloads are cached in `~/.jpyrust/cache` and shared across instances. Without network access, use `python(Path)`.
        * **The `plugins/` directory loader is removed.** It was never extracted from the JAR. Register tasks with `@task` and `moduleDir(...)`.
        * **YOLO `bbox` values are in the original image's pixel coordinates.** Images wider than 640 px were previously reported in the resized image's coordinates.
        * **Logs go through `System.Logger` (`com.jpyrust`)** instead of `System.out`/`System.err`. Native-loading and pip output are at `DEBUG`, hidden by default.
        * **Existing installs run `pip install` once more.** The `.installed` marker now stores the SHA-256 of the requirements, and a mismatch re-runs `pip install`.
        * **Maven Central coordinates.** Releases are published as `io.github.farmer0010:jpyrust` and `io.github.farmer0010:jpyrust-spring-boot-starter`. JitPack keeps `com.github.farmer0010:JPyRust`.
    * **Feature:** Call your own Python code. `@task` (from the `jpyrust` module) registers a function, `moduleDir(Path)` points the worker at your modules, `requirements(Path)` replaces the bundled dependency list, and `call(task, bytes, args)` / `call(task, text)` invoke it. Results come back as bytes, UTF-8 text or JSON.
    * **Feature:** `workers(n)` runs `n` Python processes behind one bridge; calls from different threads run in parallel, so CPU-bound Python is no longer serialized by one GIL. `callAsync(task, bytes, args)` returns a `CompletableFuture`.
    * **Feature:** `timeouts(startup, task)`, `model(path, confidence)` and `whisperModel(path)` configure the bridge before `initialize()`. The long `initialize(...)` overloads still work.
    * **Feature:** `python(Path)` builds the venv from an existing interpreter (`python -m venv --system-site-packages`) on every OS, for offline, intranet and container setups.
    * **Feature:** `JPyRustBridge` implements `AutoCloseable`.
    * **Feature:** `jpyrust-spring-boot-starter` configures the bridge from `jpyrust.*` properties, exposes `JPyRustTemplate`, records a Micrometer timer `jpyrust.calls` (tags `task`, `outcome`) and adds an Actuator health indicator.
    * **Feature:** `sendTask(taskType, metadata)` calls any registered task and returns its output as a UTF-8 string.
    * **Feature:** Audio transcription with OpenAI Whisper (`processAudio(data, length, sampleRate)`, `audio` extra), now on Windows too.
    * **Feature:** MPS (Metal) device on Apple Silicon, alongside CUDA and CPU.
    * **Perf:** Every call passes data through reusable shared-memory buffers that grow on demand (up to 256 MB) instead of files or per-call segments. On Apple M5 a 16 B round trip dropped from 0.22 ms to 0.016 ms and 1 MB from 0.67 ms to 0.19 ms (p50). Larger outputs fall back to a file, so there is no output size limit. On Linux, buffers are kept within half of the free `/dev/shm`, so Docker's 64 MB default no longer risks a crash.
    * **Fix:** Rust JNI entry points are wrapped in `catch_unwind`, so a native panic or JNI error becomes a Java exception instead of aborting the JVM. Closing during an in-flight call can no longer free native state underneath it.
    * **Fix:** Waiting on the Python worker actually times out (600 s startup, 300 s per task, configurable); a hung or dead worker is killed and respawned on the next call. The previous startup timeout never fired.
    * **Fix:** `modelPath`/`confidence`/Whisper model passed to `initialize(...)` are forwarded to Python; they were previously ignored.
    * **Fix:** The Python worker starts without `numpy`/`opencv`; only the tasks that need them fail. Windows dependency install failures make `initialize()` throw instead of being ignored.
    * **Fix (demo-web):** The image and webcam pages draw YOLO detections on a canvas. `/api/plugin/MATH_ADD` runs the `@task` module in `demo-web/python/tasks/`.
    * **Security:** Windows shared-memory mappings are restricted to the owning user and SYSTEM (`D:P(A;;GA;;;OW)(A;;GA;;;SY)`) instead of Everyone.
    * **Build:** `rust-bridge` is versioned with the library, and CI fails if `Cargo.toml` and `gradle.properties` disagree. The JAR carries `META-INF/THIRD_PARTY_NOTICES.md` for the statically linked Rust crates. Maven Central artifacts include sources and javadoc JARs and signed POMs.
    * **Docs:** JMH benchmarks against a persistent Python HTTP server ([BENCHMARKS.md](BENCHMARKS.md)), an [API reference](API.md), a [support matrix and versioning policy](COMPATIBILITY.md), and runnable `examples/quickstart` and `examples/spring-boot`.

* **v1.3.1**
    * **Linux/Docker Support:** `setupEmbeddedPython` detects the OS and uses the system `python3` on Linux instead of the Windows-only embedded distribution.
    * **Custom Parameters:** New `initialize(workDir, modelPath, confidence)` overload for custom AI model configuration.
    * **Backward Compatible:** Existing `initialize(workDir)` still works with defaults (`yolov8n.pt`, `0.5f`).

* **v1.3.0**
    * **Major Refactor:** Switched to Multi-Instance Architecture.
    * **Breaking Change:** Removed static methods; added Constructor-based instantiation.
    * **Feature:** Isolated working directories per instance (`~/.jpyrust/cam1`).

* **v1.2.0**
    * **Performance:** Restored Shared Memory (SHMEM) on Windows via Win32 API.
    * **Security:** Fixed `WinError 5` using custom Security Descriptors.

* **v1.1.0**
    * Initial Windows Support & File-based IPC fallback.
