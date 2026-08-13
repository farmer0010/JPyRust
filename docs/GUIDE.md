# JPyRust User Guide

A hands-on guide to installing, initializing, and calling JPyRust from a Java application. For the architecture and benchmark numbers, see the [main README](../README.md). [한국어 가이드](GUIDE_KR.md).

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Basic Usage](#basic-usage)
- [Initialization Options](#initialization-options)
- [Task Types](#task-types)
  - [YOLO Object Detection](#yolo-object-detection)
  - [Edge Detection](#edge-detection)
  - [NLP Sentiment Analysis](#nlp-sentiment-analysis)
  - [Linear Regression](#linear-regression)
- [Multi-Instance Usage](#multi-instance-usage)
- [Platform Notes](#platform-notes)
- [Running the Benchmark Yourself](#running-the-benchmark-yourself)
- [Troubleshooting](#troubleshooting)

## Requirements

| Component | Requirement |
| :--- | :--- |
| Java | 17+ |
| Platform | Windows, macOS, or Linux (x86_64) |
| Windows | Nothing extra — the embedded Python is bundled and self-installs. |
| macOS / Linux | A `python3` interpreter on your `PATH` (JPyRust tries `python3.12`, `python3.11`, `python3.13`, then `python3`). It provisions its own isolated venv from whichever one it finds — it does not touch your system Python's packages. |

## Installation

Add JitPack and the dependency to `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.farmer0010:JPyRust:v1.3.1")
}
```

## Basic Usage

```java
import com.jpyrust.JPyRustBridge;
import java.nio.ByteBuffer;

public class VisionService {
    public void detect(byte[] jpegBytes) {
        JPyRustBridge bridge = new JPyRustBridge("cam1");
        bridge.initialize(); // spawns the worker in ~/.jpyrust/cam1

        ByteBuffer buf = ByteBuffer.allocateDirect(jpegBytes.length);
        buf.put(jpegBytes);
        buf.flip();

        byte[] result = bridge.processImage(buf, jpegBytes.length, 0, 0, 0);
        System.out.println(new String(result)); // {"detections": [...]}

        bridge.close();
    }
}
```

A few things worth calling out for first-time users:

- **`initialize()` is slow the first time, fast after that.** The first call per instance directory provisions Python (embedded on Windows, a venv on macOS/Linux) and installs dependencies — this can take from several seconds to a couple of minutes depending on your network and platform. Subsequent runs reuse it via a `.installed` marker in `~/.jpyrust/<instanceId>/`.
- **`processImage` expects an *encoded* image** (JPEG/PNG bytes, e.g. what you'd read straight from a `.jpg` file or get from `ImageIO`/OpenCV's `imencode`), not a raw pixel buffer. It's decoded internally with OpenCV's `imdecode`, so the `width`/`height`/`channels` arguments are not used for this task type — pass `0` if you don't have them handy.
- **`ByteBuffer` must be direct** (`ByteBuffer.allocateDirect(...)`) — the native side reads it via its raw memory address.
- **Always call `close()`** when you're done with an instance, or the persistent Python worker process keeps running in the background.

## Initialization Options

```java
// 1. Defaults: model=yolov8n.pt, confidence=0.5, work dir=~/.jpyrust/<instanceId>
bridge.initialize();

// 2. Custom work directory, default model/confidence
bridge.initialize("/var/lib/myapp/cam1");

// 3. Custom model + confidence threshold (v1.3.1+)
bridge.initialize("/var/lib/myapp/cam1", "custom_model.pt", 0.25f);

// 4. Full control, including the shared-memory session key
bridge.initialize("/var/lib/myapp/cam1", "custom_model.pt", 0.25f, "my-session-key");
```

`modelPath` is resolved by Ultralytics' `YOLO(...)` constructor on the Python side — an absolute path to a `.pt` file, or a model name it knows how to fetch. If the model fails to load, JPyRust doesn't crash — YOLO detection calls just return an empty detection list (`{"detections": []}`) instead of failing.

`initialize()` is idempotent per instance: calling it twice on the same `JPyRustBridge` object is a no-op after the first call.

## Task Types

Each task type has its own public method on `JPyRustBridge`. Internally they all go through the same native `executeTask` call — the persistent daemon dispatches by task name.

### YOLO Object Detection

```java
byte[] result = bridge.processImage(directBuffer, length, width, height, channels);
// -> {"detections": [{"bbox": [x, y, w, h], "label": "person", "score": 0.87}, ...]}
```

There's also an overload that lets you supply your own request ID (useful for correlating logs/traces):

```java
byte[] result = bridge.processImage(directBuffer, length, width, height, channels, myRequestId);
```

### Edge Detection

Unlike `processImage`, this one *does* expect **raw pixel bytes** (not encoded JPEG/PNG) — `width`/`height`/`channels` describe how to interpret the buffer, and it runs a Canny edge filter via OpenCV, returning an encoded JPEG.

```java
byte[] jpegResult = bridge.processEdgeDetection(rawPixelBytes, width, height, channels);
```

### NLP Sentiment Analysis

Backed by `pandas`/`scikit-learn`/`TextBlob` on the Python side.

```java
String result = bridge.processNlp("This library saved me a ton of latency!");
// -> "POSITIVE (Polarity: 0.62)"
```

### Linear Regression

```java
String points = "[[1,2],[2,4],[3,6]]"; // [x, y] pairs
String result = bridge.processRegression(points);
// -> "Slope: 2.0000, Intercept: 0.0000"
```

## Multi-Instance Usage

Each `JPyRustBridge` instance is fully independent — its own Python daemon process, its own working directory, its own shared-memory session. This is the intended way to handle, e.g., multiple camera streams in parallel:

```java
JPyRustBridge cam1 = new JPyRustBridge("cam1");
JPyRustBridge cam2 = new JPyRustBridge("cam2");
cam1.initialize();
cam2.initialize();

// Both can be called concurrently from different threads —
// each owns its own daemon process and shared-memory segment.
```

Work directories default to `~/.jpyrust/<instanceId>`, so different `instanceId`s never collide even with the defaults.

## Platform Notes

- **Windows**: Ships a portable embedded Python distribution, bundled inside the JAR and extracted on first `initialize()`. Fully self-contained.
- **macOS / Linux**: There's no portable embedded Python for these platforms, so JPyRust finds a system `python3` and builds a private venv from it (`~/.jpyrust/<instanceId>/venv`). This means `python3` must already be installed and reachable on `PATH`.
- All three platforms ship a matching native library (`jpyrust.dll` / `jpyrust.dylib` / `jpyrust.so`) inside the JAR — you don't need to build anything yourself to use the published artifact.

## Running the Benchmark Yourself

The repo ships a real, runnable latency benchmark — no mocks, no hardcoded numbers:

```bash
./gradlew :java-api:compileTestJava
java -cp java-api/build/classes/java/main:java-api/build/classes/java/test:java-api/build/resources/main \
     com.jpyrust.LatencyBenchmark
```

It runs real YOLOv8n inference on `sample.png`, comparing a cold Python subprocess spawn per call against the persistent SHMEM daemon. See the [Performance Benchmark section](../README.md#-performance-benchmark) in the README for what to expect and why the numbers look the way they do.

## Troubleshooting

See the **Configuration & Troubleshooting** section in the [main README](../README.md) for the common failure modes (native library not found, Windows shared-memory permissions, Python dependency issues) and their fixes.
