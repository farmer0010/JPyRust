# 🚀 JPyRust: High-Performance Universal AI Bridge

> **"A persistent Python worker for Java, reached over JNI + shared memory instead of a subprocess per call."**

[![Build Status](https://img.shields.io/github/actions/workflow/status/farmer0010/JPyRust/build.yml?style=flat-square&logo=github&label=Build)](https://github.com/farmer0010/JPyRust/actions)
[![Release](https://img.shields.io/github/v/release/farmer0010/JPyRust?style=flat-square&color=blue&label=Release)](https://jitpack.io/#farmer0010/JPyRust)
[![License](https://img.shields.io/github/license/farmer0010/JPyRust?style=flat-square&color=green)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey?style=flat-square)

<p align="center">
  <a href="https://openjdk.org/">
    <img src="https://img.shields.io/badge/Java-17+-orange?logo=openjdk&style=for-the-badge" alt="Java">
  </a>
  <a href="https://www.rust-lang.org/">
    <img src="https://img.shields.io/badge/Rust-1.70+-orange?logo=rust&style=for-the-badge" alt="Rust">
  </a>
  <a href="https://www.python.org/">
    <img src="https://img.shields.io/badge/Python-3.11-blue?logo=python&style=for-the-badge" alt="Python">
  </a>
</p>

<div align="center">
  <a href="README_KR.md">🇰🇷 한국어 버전 (Korean Version)</a> · <a href="docs/GUIDE.md">📚 User Guide</a>
</div>

---

## 💡 Introduction

**JPyRust** is a hybrid architecture designed to bridge the gap between **Java's robustness** and **Python's AI ecosystem**. It enables Spring Boot applications to execute heavy AI models (YOLO, PyTorch, TensorFlow) with **near-native performance**.

Unlike slow `ProcessBuilder` or high-latency HTTP APIs, JPyRust leverages **Rust JNI** and **Shared Memory (SHMEM)** to achieve sub-millisecond communication.

### 🌟 Why JPyRust?
* 🚀 **Zero-Latency**: Uses System RAM (Shared Memory) instead of HTTP/Sockets.
* 🔄 **True Parallelism**: v1.3.0 supports **Multi-Instance** architecture (1 Java App connects to N Python Processes).
* 🛠️ **Zero-Config**: Auto-provisions its Python environment on first run — a fully embedded distribution on Windows, an isolated venv from your system `python3` on macOS/Linux. No manual `pip install`.
* 🛡️ **Crash-Proof**: Rust monitors Python health and auto-restarts workers if they crash.

---

## 🏗️ Architecture (v1.3.0)

With the **v1.3.0 update**, JPyRust has moved from a generic Singleton pattern to a **Multi-Instance Object-Oriented Architecture**. This allows a single Java application to control multiple independent AI workers (e.g., Multi-Channel CCTV processing).

### 🧩 System Component Diagram

```mermaid
graph TD
    subgraph JavaApp [☕ Java Application Layer]
        style JavaApp fill:#f9f2f4,stroke:#333,stroke-width:2px
        J1["Camera 1 Bridge<br>(Instance ID: cam1)"]
        J2["Camera 2 Bridge<br>(Instance ID: cam2)"]
    end

    subgraph NativeLayer [🦀 Rust JNI Layer]
        style NativeLayer fill:#e8f4f8,stroke:#333,stroke-width:2px
        R1["BridgeState A<br>{Ptr: 0x7FA...}"]
        R2["BridgeState B<br>{Ptr: 0x81B...}"]
    end

    subgraph PythonLayer [🐍 Embedded Python Layer]
        style PythonLayer fill:#edfbec,stroke:#333,stroke-width:2px
        P1["Python Worker A<br>(PID: 1001)"]
        P2["Python Worker B<br>(PID: 1002)"]
    end

    J1 -- "JNI Call (nativePtr)" --> R1
    J2 -- "JNI Call (nativePtr)" --> R2
    
    R1 <== "⚡ SHMEM (Images)" ==> P1
    R2 <== "⚡ SHMEM (Images)" ==> P2
    
    P1 -- "JSON Result" --> R1
    P2 -- "JSON Result" --> R2
```

### ⚡ Execution Sequence

```mermaid
sequenceDiagram
    participant Java as ☕ Java (Spring)
    participant Rust as 🦀 Rust (JNI)
    participant Py as 🐍 Python (Worker)

    Note over Java, Py: Initialization Phase
    Java->>Rust: new JPyRustBridge("cam1").initialize()
    Rust->>Rust: Allocate BridgeState (Heap)
    Rust->>Py: Spawn Process (arg: --instance-id cam1)
    Py->>Py: Setup ~/.jpyrust/cam1/
    Py-->>Rust: Signal "READY"
    Rust-->>Java: Return nativePtr (Handle)

    Note over Java, Py: Inference Phase (Loop)
    Java->>Rust: processImage(ptr, image_bytes)
    Rust->>Rust: Write to Shared Memory
    Rust->>Py: Send Signal (IPC)
    Py->>Py: Read SHMEM -> YOLO Inference
    Py-->>Rust: Return JSON Result
    Rust-->>Java: Return Result String
```

---

## ⚡ Performance Benchmark

A real, runnable benchmark ships with the repo: [`LatencyBenchmark.java`](java-api/src/test/java/com/jpyrust/LatencyBenchmark.java). It runs real YOLOv8n inference on an actual image (`sample.png`), 10 iterations after warmup, comparing:
- **Legacy Subprocess** — spawns a brand-new Python interpreter (full torch/ultralytics import + model load) for every single call.
- **JPyRust (JNI + SHMEM)** — reuses one persistent Python daemon with the model already loaded; only the image crosses via shared memory.

| Architecture | Communication | Latency (Avg, measured) |
| :--- | :--- | :---: |
| Legacy Subprocess (cold spawn per call) | Stdin/Stdout | ~2,330 ms |
| JPyRust (JNI + SHMEM, persistent daemon) | Shared Memory | ~6 ms |

> *Measured on: Apple Silicon (arm64), macOS, PyTorch with MPS (Metal) acceleration, Python 3.12. ~390x faster, consistent across repeated runs.*

Nearly all of the "legacy" cost is one-time interpreter/library/model-load overhead (torch + ultralytics import, `YOLO(...)` construction) paid on *every single call* — the actual inference itself is only a few milliseconds. The daemon architecture pays that cost once at startup instead of per-request, which is the entire point of keeping a persistent worker around. Run `LatencyBenchmark` yourself to reproduce this against your own hardware — numbers will vary with CPU/GPU and image size, but the shape of the result (overhead dominates, not compute) should hold generally.

---

## 🚀 Quick Start

### 1. Installation (Gradle)
Add the JitPack repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("[https://jitpack.io](https://jitpack.io)") }
}

dependencies {
    // Latest stable version
    implementation("com.github.farmer0010:JPyRust:v1.3.1")
}
```

### 2. Usage (Java)

**Important:** As of v1.3.0, static methods are removed. You must instantiate `JPyRustBridge`.

```java
import com.jpyrust.JPyRustBridge;

public class VisionService {
    
    public void startDetection() {
        // 1. Create independent instances for each camera
        JPyRustBridge cam1 = new JPyRustBridge("cam1");
        JPyRustBridge cam2 = new JPyRustBridge("cam2");

        // 2. Initialize with defaults (Spawns workers in ~/.jpyrust/camX)
        cam1.initialize();

        // 2-1. (v1.3.1+) Initialize with a custom model & confidence threshold
        cam2.initialize("/path/to/workdir", "custom_model.pt", 0.25f);

        // 3. Process Images (Thread-Safe)
        // arg: (imageData, length, width, height, channels)
        byte[] result1 = cam1.processImage(imgData1, len1, 640, 480, 3);
        byte[] result2 = cam2.processImage(imgData2, len2, 640, 480, 3);
        
        System.out.println("Cam1 Result: " + new String(result1));
    }
}
```

---

## 🛠️ Configuration & Troubleshooting

<details>
<summary><strong>🔧 1. UnsatisfiedLinkError / DLL Not Found</strong></summary>

* **Cause:** Java cannot find the native library.
* **Fix:** The library is bundled in the JAR and extracted to a temp file automatically — `jpyrust.dll` (Windows), `jpyrust.dylib` (macOS), or `jpyrust.so` (Linux). If it still fails, check that your platform's file is present under `src/main/resources/natives/` in the JAR you're using.
</details>

<details>
<summary><strong>🛡️ 2. WinError 5 (Access Denied)</strong></summary>

* **Cause:** Windows Security permissions on Shared Memory.
* **Fix:** JPyRust v1.2+ uses explicit `SECURITY_ATTRIBUTES` with SDDL `D:(A;;GA;;;WD)` to allow Full Access to the Python child process. No manual action required.
</details>

<details>
<summary><strong>🐍 3. Python Dependency Issues</strong></summary>

* **Windows:** JPyRust includes a **portable embedded Python**. It bootstraps itself in `~/.jpyrust/<instanceId>/python_dist`.
* **macOS / Linux:** there is no portable embedded Python for these platforms, so JPyRust instead looks for a `python3` on your `PATH` (tries `python3.12`, `python3.11`, `python3.13`, then `python3`) and provisions a private virtualenv at `~/.jpyrust/<instanceId>/venv`, installing `requirements.txt` into it. This runs once per instance directory (tracked by a `.installed` marker) — delete that directory to force a clean reinstall.
* If libraries are missing, check `requirements.txt` in the resource folder.
</details>

---

## 📜 Version History

* **Unreleased**
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

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

<div align="center">
  <sub>Built with 🦀 Rust & ☕ Java by Farmer0010 (JPyRust Team).</sub>
</div>
