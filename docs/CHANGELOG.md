# Changelog

* **Unreleased**
    * **Feature:** `workers(n)` runs `n` Python processes behind one bridge; calls from different threads run in parallel, so CPU-bound Python code is no longer serialized by a single GIL. `callAsync(task, bytes, args)` returns a `CompletableFuture`.
    * **Feature:** `timeouts(startup, task)`, `model(path, confidence)` and `whisperModel(path)` configure the bridge before `initialize()`, replacing the long `initialize(...)` overloads (which still work).
    * **Behavior Change:** Failures throw a `JPyRustException` hierarchy: `TaskFailedException` (the Python task raised), `WorkerTimeoutException` (task exceeded the timeout; the worker is restarted) and `WorkerCrashedException` (the worker died, e.g. a segfault). All extend `RuntimeException`. `processEdgeDetection`, `processNlp` and `processRegression` now throw like every other call instead of returning `null` or an `{"error": ...}` string.
    * **Perf:** Every task, including `call()`, now passes data through reusable shared-memory buffers that grow on demand (up to 256 MB), instead of files or per-call segments. On Apple M5, a 16 B round trip dropped from 0.22 ms to 0.016 ms and 1 MB from 0.67 ms to 0.19 ms (p50). Outputs that do not fit fall back to a file, so the old 1 MB output limit is gone. Python no longer reports "leaked shared_memory" at exit.
    * **Fix:** YOLO `bbox` values are now in the original image's pixel coordinates. Images wider than 640 px are resized for inference, and the boxes were previously returned in the resized image's coordinates.
    * **Behavior Change:** The legacy `plugins/` directory loader is removed. It was never extracted from the JAR, and its sample returned a response the bridge could not read. Register tasks with `@task` and `moduleDir(...)` instead.
    * **Fix (demo-web):** The image and webcam pages draw the YOLO detections on a canvas instead of treating the JSON response as an image. `/api/plugin/MATH_ADD` runs the `@task` module in `demo-web/python/tasks/`.
    * **Chore:** `rust-bridge` is versioned with the library (1.3.1, was 0.1.3), and CI fails if `Cargo.toml` and `gradle.properties` disagree. The JAR now carries `META-INF/THIRD_PARTY_NOTICES.md` for the statically linked Rust crates (`make notices` regenerates it).
    * **Feature:** `python(Path)` builds the venv from an existing interpreter (`python -m venv --system-site-packages`) instead of `uv` or the python.org download, on every OS — for offline, intranet and container setups.
    * **Feature:** `JPyRustBridge` implements `AutoCloseable`, so it works with try-with-resources.
    * **Behavior Change:** Logs go through `System.Logger` (`com.jpyrust`) instead of `System.out`/`System.err`. Native-loading and pip output are logged at `DEBUG`, so they are hidden by default.
    * **Behavior Change:** The built-in tasks moved into opt-in extras: `vision` (YOLO, edge detection), `audio` (Whisper) and `ml` (TextBlob sentiment, regression). The default install is now only `numpy`, so `ultralytics` (AGPL-3.0), `torch` and Whisper are no longer installed unless you call `extras("vision")` / `extras("audio")` / `extras("ml")`. Calling a built-in task without its extra fails with `Task YOLO requires extras("vision")`.
    * **Feature:** Call your own Python code. `@task` (from the new `jpyrust` module) registers a Python function, `moduleDir(Path)` points the daemon at your modules, `requirements(Path)` replaces the bundled dependency list, and `call(task, bytes, args)` / `call(task, text)` invoke it. Results are returned as bytes, UTF-8 text or JSON.
    * **Behavior Change:** The `.installed` marker now stores the SHA-256 of `requirements.txt`, and a mismatch re-runs `pip install`. Existing installs run `pip install` once more on the next `initialize()` (fast when nothing changed).
    * **Fix:** The Python worker starts without `numpy`/`opencv` installed; only the built-in tasks that need them fail.
    * **Behavior Change:** Windows no longer bundles a ~400 MB embedded Python in the JAR. The first `initialize()` downloads python.org's embeddable Python 3.11.9 and a pinned `pip` wheel (both SHA-256 verified) and installs `requirements.txt` from PyPI, so the first run needs network access. Whisper now works on Windows too. The JAR shrinks to under 1 MB, and JitPack and GitHub Release JARs now behave the same on Windows (previously the JitPack JAR had no Python bundle at all).
    * **Fix:** Windows dependency install failures are no longer silently ignored — `initialize()` throws if pip fails.
    * **Behavior Change:** `processImage`, `processAudio` and the new `sendTask` throw `RuntimeException` on failure instead of returning `null`; calling them before `initialize()` or after `close()` throws `IllegalStateException`. `initialize()` now throws if the Python daemon fails to start (previously it only logged and left the bridge half-initialized). See *Errors & Timeouts* in the [README](../README.md).
    * **Fix:** Rust JNI entry points are wrapped in `catch_unwind` — a native panic or JNI error becomes a Java exception instead of aborting the JVM. `executeTask` and `close()` are synchronized, so closing during an in-flight call can no longer free native state underneath it.
    * **Fix:** Waiting on the Python daemon now actually times out (600 s startup, 300 s per task); a hung or dead daemon is killed and respawned on the next call. The previous 600 s startup timeout never fired because the read it guarded was blocking.
    * **Fix:** Results larger than the 1 MB shared-memory output buffer raise an error instead of silently returning an empty array.
    * **Feature:** `sendTask(taskType, metadata)` calls any task registered in `ai_worker.py` and returns its output as a UTF-8 string.
    * **Security:** Windows shared-memory mappings are restricted to the owning user and SYSTEM (`D:P(A;;GA;;;OW)(A;;GA;;;SY)`) instead of Everyone.
    * **Fix:** macOS/Linux venv provisioning switched from searching `PATH` for a system `python3.12`/`python3.11`/`python3.13`/`python3` to `uv venv --python 3.11 --seed`, which pins an exact interpreter version instead of depending on whatever happens to be installed. This fixes local environments where the only system `python3` was 3.13+ and `requirements.txt`'s pinned `numpy`/`torch`/etc. had no matching wheels.
    * **Feature:** Audio transcription via OpenAI Whisper — new `processAudio(data, length, sampleRate)` on the Java side and a `WHISPER` task handler in `ai_worker.py`, reusing the existing generic `executeTask` JNI call (no new native entry point). Routes through the file-based IPC fallback, which already supports variable-length payloads.
    * **Feature:** `initialize(workDir, modelPath, confidence, memoryKey, whisperModelPath)` overload to configure the Whisper model (name or local checkpoint path) alongside the existing YOLO model/confidence.
    * **Fix:** `modelPath`/`confidence` passed to `initialize(...)` were previously read in Rust but never actually forwarded to the Python daemon process — Python silently kept using its own argparse defaults regardless of what was passed. The daemon spawn now passes `--model`/`--conf`/`--whisper-model` through for real.
    * **Note:** `openai-whisper` ships no PyPI wheel (sdist only), so it's excluded from the Windows embedded offline distribution (`downloadWheels`/`stagePython`) — Windows users get YOLO/image features fully offline, Whisper requires network on first install. macOS/Linux (venv bootstrap) installs it via `--no-build-isolation` with a pinned older `setuptools` (its legacy `setup.py` needs `pkg_resources`).
    * **Feature:** macOS/Linux now provision a Python venv from the system `python3` for a fully native setup path.
    * **Perf:** Tuned shared-memory naming for cross-platform consistency (Rust ↔ Python) and to stay within macOS's `shm_open` limits.
    * **Feature:** Added MPS (Metal) as a device option on Apple Silicon, alongside CUDA/CPU.
    * **Chore:** Refined internal script paths and the legacy-subprocess benchmark protocol for consistency across platforms.

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
