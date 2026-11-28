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

## Exceptions

All are unchecked.

| Exception | When | Worker state |
| --- | --- | --- |
| `IllegalStateException` | Call before `initialize()` or after `close()` | — |
| `TaskFailedException` | The Python task raised; the message carries `ExceptionType: message` | Keeps running |
| `WorkerTimeoutException` | The task exceeded the task timeout, or startup exceeded the startup timeout | Killed; respawned on the next call |
| `WorkerCrashedException` | The process exited or died (segfault, `os._exit`, OOM kill) | Respawned on the next call |
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
| `jpyrust.startup-timeout`, `jpyrust.task-timeout` | `600s`, `300s` | `timeouts(...)` |
| `jpyrust.model`, `jpyrust.confidence` | `yolov8n.pt`, `0.5` | `model(...)` |
| `jpyrust.whisper-model` | none | `whisperModel(...)` |

Inject `JPyRustTemplate` for `call(...)` and `callAsync(...)`. With Micrometer on the classpath every call records the timer `jpyrust.calls` (tags `task`, `outcome`), and with Actuator `/actuator/health` gets a `jpyRust` component that sends `STATUS` to a worker.
