# API Reference

Everything public lives in the `com.jpyrust` package. The Python side is the `jpyrust` module, which is on the worker's import path automatically.

## Lifecycle

```java
try (JPyRustBridge bridge = new JPyRustBridge("orders")   // instance id: names ~/.jpyrust/<id>
        .requirements(Path.of("python/requirements.txt"))
        .moduleDir(Path.of("python/tasks"))
        .workers(4)) {
    bridge.initialize();
    byte[] out = bridge.call("SCORE", payload, "v2");
}
```

1. Construct with an instance id.
2. Configure with the methods below. They return the bridge, throw `IllegalStateException` after `initialize()`, and can be called in any order.
3. `initialize()` provisions Python (first run only), starts the workers and blocks until they report ready.
4. Call tasks from any thread.
5. `close()` stops the workers. It is idempotent, and `JPyRustBridge` is `AutoCloseable`.

## Configuration

| Method | Default | Effect |
| --- | --- | --- |
| `requirements(Path file)` | bundled `requirements.txt` (`numpy`) | pip requirements for the worker environment. A change re-runs `pip install` on the next `initialize()`. |
| `extras(String... names)` | none | Adds built-in task bundles: `vision` (YOLO, EDGE_DETECT), `audio` (WHISPER), `ml` (NLP_TEXTBLOB, REGRESSION). Their requirements are appended. Unknown names throw `IllegalArgumentException`. |
| `moduleDir(Path dir)` | none | Every `*.py` in the directory is imported at worker start. Can be called more than once. |
| `pythonVersion(String version)` | `"3.11"` | CPython to download: `"3.11"`, `"3.12"`, `"3.13"` or `"3.14"`. A change rebuilds the venv on the next `initialize()`. The built-in extras need 3.11 or 3.12. |
| `python(Path interpreter)` | downloaded CPython | Builds the venv from an existing interpreter (`--system-site-packages`) instead of downloading one. Overrides `pythonVersion`. |
| `workers(int count)` | 1 | Number of Python processes. Calls from different threads run in parallel, one per process. |
| `devices(String... devices)` | none | One worker per entry, each pinned to a device: `"cuda:N"` sets `CUDA_VISIBLE_DEVICES=N` for that worker (the task always sees `cuda:0`), `"cpu"` hides every GPU. Replaces `workers(n)`; the later of the two calls wins. |
| `torch(String build)` | `"default"` | Which torch build pip installs when the requirements include `torch`: `"default"` (PyPI), `"cpu"`, a CUDA build such as `"cu121"`, a ROCm build such as `"rocm6.2"`, or `"auto"` (the newest CUDA build the NVIDIA driver supports and that is published for the pinned torch version; `cpu` without an NVIDIA GPU; `default` on macOS). A change rebuilds the venv. |
| `timeouts(Duration startup, Duration task)` | 600 s, 300 s | Startup covers interpreter start and module import (model loading). A task that exceeds its timeout kills the worker. |
| `model(String path, float confidence)` | `yolov8n.pt`, 0.5 | YOLO model and threshold for the `vision` extra. |
| `whisperModel(String path)` | none | Whisper model name or checkpoint for the `audio` extra. |

`initialize(String workDirectory)` uses a different directory than `~/.jpyrust/<id>`. The longer `initialize(workDir, modelPath, confidence[, memoryKey[, whisperModelPath]])` overloads remain for 1.x code; prefer `model(...)` and `whisperModel(...)`.

## Calling tasks

| Method | Returns | Notes |
| --- | --- | --- |
| `byte[] call(String task, byte[] input, String args)` | the task's output | `args` is a single line; line breaks throw `IllegalArgumentException`. |
| `String call(String task, String input)` | UTF-8 output | Input is sent as UTF-8 bytes, `args` is empty. |
| `CompletableFuture<byte[]> callAsync(String task, byte[] input, String args)` | future | Runs on a daemon thread pool sized to `workers`. |
| `String sendTask(String task, String metadata)` | UTF-8 output | No input bytes; `metadata` is passed as `args`. |
| `processImage`, `processAudio`, `processEdgeDetection`, `processNlp`, `processRegression` | output | Wrappers around the built-in extras' tasks. |

Task names are case-insensitive. Input and output have no fixed size limit: data goes through shared memory up to 256 MB (less on Linux if `/dev/shm` is small) and through files beyond that.

## Streaming

A task that `yield`s produces a stream. `callStream` returns a `java.util.stream.Stream` whose elements are the yielded values, delivered as the task produces them.

```python
@task("TOKENS")
def tokens(prompt, args):
    for token in model.generate(prompt.decode()):
        yield token
```

```java
try (Stream<String> tokens = bridge.callStream("TOKENS", "Once upon a time")) {
    tokens.forEach(System.out::print);
}
```

| Method | Elements |
| --- | --- |
| `Stream<byte[]> callStream(String task, byte[] input, String args)` | each yielded value, converted like a `call` result |
| `Stream<String> callStream(String task, String input)` | UTF-8 text |

* The worker produces the next value only after Java asks for it, so a slow consumer never builds up a backlog in memory. Each value may be any size; large ones go through a file.
* A stream holds one worker until it ends or is closed. **Always close it** (try-with-resources): closing early tells Python to stop, which runs the generator's `finally` blocks. A stream read to the end returns its worker by itself.
* `task-timeout` applies to each value, not to the whole stream. An exception raised in the generator surfaces from `hasNext()`/`next()` as `TaskFailedException`; a timeout or crash as `WorkerTimeoutException` / `WorkerCrashedException`.
* Yielded values follow the `call` rules: `bytes`, `str`, `numpy.ndarray` (map with `NDArray::fromNpy`) or JSON. A task that returns instead of yielding gives a stream of one element. Calling a generator task with `call` throws `TaskFailedException`.
* `JPyRustTemplate.callStream` records one `jpyrust.calls` timer per stream, when it is closed. In Spring MVC, return a `StreamingResponseBody` that writes each element as a Server-Sent Event (see `examples/spring-boot`).

## Arrays

`NDArray` carries a typed, shaped array to and from a task without JSON or custom encoding. On the wire it is a standard NumPy `.npy` buffer, so the task receives a `numpy.ndarray` and can return one.

```java
NDArray image = NDArray.ofUInt8(pixels, 480, 640, 3);           // shape (480, 640, 3), dtype uint8
NDArray mask = bridge.call("SEGMENT", image, "");                // whatever array the task returns
float[] scores = bridge.call("SCORE", NDArray.of(features, 32, 128), "").toFloatArray();
```

```python
@task("SEGMENT")
def segment(image, args):          # numpy.ndarray, shape (480, 640, 3), read-only
    return (image.mean(axis=2) > 128).astype("uint8")
```

| Java | dtype |
| --- | --- |
| `NDArray.of(float[], shape...)` / `toFloatArray()` | `float32` |
| `NDArray.of(double[], shape...)` / `toDoubleArray()` | `float64` |
| `NDArray.of(short[] / int[] / long[], shape...)` / `toShortArray()`, `toIntArray()`, `toLongArray()` | `int16`, `int32`, `int64` |
| `NDArray.ofUInt8(byte[], shape...)`, `ofInt8(...)` / `toByteArray()` | `uint8`, `int8` |
| `NDArray.of(boolean[], shape...)` / `toBooleanArray()` | `bool` |
| `NDArray.wrap(DType, ByteBuffer, shape...)` | any of the above, from little-endian bytes |

* Without a shape the array is one-dimensional. A shape whose product differs from the number of values throws `IllegalArgumentException`.
* `call(task, NDArray, args)` and `callAsync(task, NDArray, args)` expect the task to return an array; any other result throws `JPyRustException`. To send an array and get bytes, text or JSON back, call `call(task, array.toNpy(), args)`.
* The task's input array is read-only because it is a view over the received bytes; call `.copy()` to modify it. Returned arrays may be any supported dtype, byte order or memory layout; they are converted to little-endian, C-ordered data.
* `shape()`, `dtype()`, `size()` and the read-only `data()` buffer describe the array; `toNpy()` / `NDArray.fromNpy(byte[])` convert to and from `.npy` bytes, which `numpy.load` reads.
* Any `bytes` input that starts with the `.npy` magic `\x93NUMPY` reaches the task as an array.

## Exceptions

All are unchecked.

| Exception | When | Worker state |
| --- | --- | --- |
| `IllegalStateException` | Call before `initialize()` or after `close()` | — |
| `TaskFailedException` | The Python task raised; the message carries `ExceptionType: message` | Keeps running |
| `WorkerTimeoutException` | The task exceeded the task timeout, or startup exceeded the startup timeout | Killed; respawned on the next call |
| `WorkerCrashedException` | The process exited or died (segfault, `os._exit`, OOM kill) | Respawned on the next call |
| `GpuOutOfMemoryException` | The task raised CUDA `OutOfMemoryError`; a subclass of `TaskFailedException` | Restarted, so the next call starts with free GPU memory |
| `JPyRustException` | Base class; also thrown for provisioning failures (`Failed to initialize JPyRustBridge`) and invalid buffers | — |

## Python side

```python
from jpyrust import task, config

@task("SCORE")              # name is optional; defaults to the function name, upper-cased
def score(data: bytes, args: str):
    return {"score": 0.93}   # bytes are returned as-is, str as UTF-8, anything else as JSON
```

* `data` is the input as `bytes`, `args` is the string passed from Java.
* An exception becomes a `TaskFailedException` in Java with `ExceptionType: message`.
* Module-level code runs once per worker process at startup, so load models there.
* `config` is a dict with `model`, `conf`, `whisper_model` and `device` (`cuda`, `mps` or `cpu`, detected when `torch` is installed).
* A module that fails to import is reported on the worker's stderr and skipped; the other modules still load.

## Choosing versions per environment

Every setting that depends on the machine can be set when the application starts, without changing code. A system property wins over an environment variable, and both win over the builder.

| System property | Environment variable | Same as |
| --- | --- | --- |
| `jpyrust.python-version` | `JPYRUST_PYTHON_VERSION` | `pythonVersion(...)` |
| `jpyrust.torch` | `JPYRUST_TORCH` | `torch(...)` |
| `jpyrust.devices` (comma-separated) | `JPYRUST_DEVICES` | `devices(...)` |
| `jpyrust.workers` | `JPYRUST_WORKERS` | `workers(...)` |

```bash
java -Djpyrust.torch=auto -Djpyrust.devices=cuda:0,cuda:1 -jar app.jar     # GPU server
JPYRUST_TORCH=cpu java -jar app.jar                                         # CPU server, no CUDA libraries
```

The override applies to every bridge in the JVM. `torchBuild()` returns the build that was installed, after `auto` is resolved.

## Command line

The JPyRust JAR is runnable. Download `jpyrust-<version>.jar` from Maven Central or a GitHub Release.

```bash
java -jar jpyrust-2.2.0.jar doctor
java -jar jpyrust-2.2.0.jar setup --instance detector --torch auto --extras vision --python-version 3.11
```

| Command | Does |
| --- | --- |
| `doctor` | Prints the OS, Java, NVIDIA GPUs and driver CUDA version, the cached Python builds, and the recommended `torch` build and `devices` with the matching `-D` flags |
| `setup [options]` | Runs the same provisioning as `initialize()` into `~/.jpyrust/<instance>` (or `--work-dir`), starts a worker once and prints its status. Options: `--instance`, `--work-dir`, `--python-version`, `--python`, `--torch`, `--extras a,b`, `--requirements`, `--module-dir` (repeatable) |
| `version` | Prints the version |

Run `setup` in a Docker build step with the same instance id and options as the application, and the application starts without downloading anything.

## Spring Boot starter

`io.github.farmer0010:jpyrust-spring-boot-starter` creates the bridge from `jpyrust.*` properties, calls `initialize()` at startup and `close()` at shutdown. It backs off when the application defines its own `JPyRustBridge` bean. Spring Boot 3.2+ and 4.x are supported.

```yaml
jpyrust:
  instance-id: orders
  requirements: python/requirements.txt
  module-dirs: python/tasks
  python-version: "3.12"
  workers: 4
  task-timeout: 30s
```

| Property | Default | Same as |
| --- | --- | --- |
| `jpyrust.instance-id` | `default` | constructor argument |
| `jpyrust.work-dir` | `~/.jpyrust/<instance-id>` | `initialize(String)` |
| `jpyrust.requirements` | bundled | `requirements(Path)` |
| `jpyrust.module-dirs` | none | `moduleDir(Path)`, a list |
| `jpyrust.extras` | none | `extras(...)`, a list |
| `jpyrust.python-version` | `3.11` | `pythonVersion(String)` |
| `jpyrust.python` | none | `python(Path)` |
| `jpyrust.workers` | `1` | `workers(int)` |
| `jpyrust.devices` | none | `devices(...)`, a list |
| `jpyrust.torch` | `default` | `torch(String)` |
| `jpyrust.startup-timeout`, `jpyrust.task-timeout` | `600s`, `300s` | `timeouts(...)` |
| `jpyrust.model`, `jpyrust.confidence` | `yolov8n.pt`, `0.5` | `model(...)` |
| `jpyrust.whisper-model` | none | `whisperModel(...)` |

Inject `JPyRustTemplate` for `call(...)` and `callAsync(...)`. With Micrometer on the classpath every call records the timer `jpyrust.calls` (tags `task`, `outcome`), and with Actuator `/actuator/health` gets a `jpyRust` component that sends `STATUS` to a worker.
