# 🌉 JPyRust: Zero-Config Java-Python Bridge

> "Stop asking users to install Python."

[🇰🇷 Korean Version](README_KR.md)

---

## 🏗️ System Architecture
**Polyglot Runtime Environment**
Java (Host), Rust (Bridge), and Python (Worker) operate within a single shared process memory.

```mermaid
graph TD
    %% Client
    Client(["User Client<br>Web Browser"]) -->|HTTP Request| SpringBoot["🍃 Spring Boot Server<br>(Java Host)"]
%% JPyRust Area
subgraph "JPyRust Library (In-Process)"
    SpringBoot -->|"NativeLoader.load()"| Loader["📦 Native Loader<br>(Resource Extractor)"]
    Loader -->|"Extract & Link"| RustBridge["🦀 Rust Bridge<br>(jni-rs + pyo3)"]
    
    RustBridge <-->|"FFI / Shared Memory"| PythonVM["🐍 Embedded Python 3.10<br>(Standalone Runtime)"]
    
    subgraph "AI Worker"
        PythonVM -->|"Execute Script"| Logic["🧠 ai_worker.py<br>(PyTorch/NumPy)"]
    end
end
%% Data Flow
Logic -->|"Result JSON"| RustBridge
RustBridge -->|"JString"| SpringBoot
SpringBoot -->|"HTTP Response"| Client
```

<br>

## 🚀 Key Features

### 1. 📦 Zero-Config Deployment
- **Standalone Runtime**: The end-user does not need Python installed. The JAR file contains an optimized **Python 3.10 Runtime (ZIP)**.
- **Smart NativeLoader**: Detects the OS (Windows/Linux/Mac) at runtime, automatically extracts the necessary DLLs/SOs and Python runtime to a temporary directory, and links them dynamically.

### 2. 🛡️ Memory Safety & Stability
- **Rust Safety Valve**: Unlike C/C++ based JNI (e.g., JEP), Rust's ownership model prevents memory leaks and pointer errors (SegFaults) at the source.
- **Signal Handling Protection**: Prevents the Python interpreter from hijacking the JVM's signal handlers (SIGINT, SIGSEGV) using low-level control (Py_InitializeEx), ensuring JVM stability.

### 3. ⚡ High Performance
- **No ProcessBuilder**: Avoids slow process forking (ProcessBuilder) or HTTP overhead. It uses **JNI (Java Native Interface)** to share memory space.
- **GIL Management**: Explicitly manages the Python GIL (Global Interpreter Lock) acquisition/release at the Rust level, ensuring deadlock-free concurrency even in multi-threaded environments like Spring Boot.

### 4. ⚡ Zero-Copy Shared Memory
- **Direct ByteBuffer**: Java's off-heap memory is directly shared with Rust and Python without copying. (Theoretical transfer speed tends to 0ms)
- **In-Place Modification**: Python (`numpy`/`cv2`) directly modifies image data in Java memory. Completely eliminates serialization overhead for large AI model inference.

### 5. 🛠️ Development Experience
- **Dependency Automation**: Simply list packages in `requirements.txt`. The Gradle build automatically handles `pip install` and embeds them into the JAR.
- **CI/CD Pipeline**: GitHub Actions automatically cross-builds and deploys native libraries for Windows, Linux, and macOS.

## 📂 Project Structure
**Multi-Module Polyglot Project**
Organically combines Java, Rust, Python, and Web code.

```plaintext
.
├── architecture.md             # [Doc] Architecture Design Document
├── docker-compose.yml          # [Infra] Docker Deployment Config
├── Dockerfile                  # [Infra] Multi-stage Build Script
├── settings.gradle.kts         # [Gradle] Multi-module Settings
├── requirements.txt            # [Config] Python Dependencies
├── java-api                    # [Module] Java Library (Core)
│   ├── src/main/java
│   │   └── com/jpyrust
│   │       ├── NativeLoader.java   # [Core] Auto-extractor for DLLs & Python Runtime
│   │       └── JPyRustBridge.java  # [API] User-facing Native Interface
│   └── src/main/resources
│       └── python_dist         # [Res] Embedded Python Runtime (Zip on build)
├── rust-bridge                 # [Module] Rust JNI Implementation
│   ├── Cargo.toml              # [Rust] Dependencies (jni, pyo3)
│   └── src
│       └── lib.rs              # [Code] JNI Functions & Python VM Control Logic
├── python-core                 # [Module] AI/ML Logic
│   └── ai_worker.py            # [Code] Python script performing actual logic
└── demo-web                    # [Module] Spring Boot Demo Server
    └── src/main/java/.../AIImageController.java # Zero-Copy API Endpoint
```

## 🔄 Logic Flow
The process flow from a Web Request -> Java -> Rust -> Python AI execution.

```mermaid
sequenceDiagram
    autonumber
    actor User as 👤 User
    participant Boot as 🍃 Spring Boot
    participant Loader as 📦 NativeLoader
    participant Rust as 🦀 Rust Bridge
    participant Py as 🐍 Python VM
%% Initialization Phase
Note over Boot, Py: 1. Initialization (Application Start)
Boot->>Loader: JPyRustBridge.init()
activate Loader
Loader->>Loader: Detect OS & Extract Resources (Temp Dir)
Loader->>Rust: System.load(rust_bridge.dll)
activate Rust
Rust->>Py: Py_InitializeEx(0) (Signal Protection)
Rust->>Py: Windows DLL Path Patch
deactivate Rust
deactivate Loader
%% Execution Phase (Zero-Copy)
Note over Boot, Py: 2. Zero-Copy Request (Image Processing)
User->>Boot: POST /api/ai/process-image (Image)
activate Boot
Boot->>Boot: DirectByteBuffer.allocate()
Boot->>Rust: runPythonRaw(Buffer Pointer)
activate Rust
Rust->>Py: PyMemoryView_FromMemory(Ptr)
activate Py
Py->>Py: numpy.frombuffer() -> In-Place Edit
Py-->>Rust: Return
deactivate Py
Rust-->>Boot: Return
deactivate Rust
Boot-->>User: Processed Image (PNG)
deactivate Boot
```

## 📜 Version History

| Version | Stage | Key Achievement |
| :--- | :--- | :--- |
| **v0.1** | PoC | Established basic Java-Rust-Python communication pipeline (JNI Pipeline). |
| **v0.2** | Zero-Config | Implemented NativeLoader. Removed `-Djava.library.path` requirement. |
| **v0.3** | Desert Mode | Embedded Standalone Python(3.10). Enabled offline execution without local Python installation. |
| **v0.4** | Safety Patch | Patched SIGINT conflicts and fixed Windows DLL path issues. |
| **v1.0** | Release | Integrated with Spring Boot and added Docker multi-stage build support. |
| **v1.1** | Optimization | Implemented Zero-Copy Shared Memory & Image Processing Demo. |
| **v1.2** | Automation | Gradle-based Python Dependency Automation & GitHub Actions CI/CD. |

---

## ⚙️ Setup & Run

### 1. Prerequisites
- Java 17+ (JDK)
- Rust (Cargo, only required for building from source)
- Docker (for containerized execution)

### 2. Run with Gradle (Local)

```bash
# 1. Build Rust Library (Release Mode)
cd rust-bridge
cargo build --release

# 2. Copy Resources (Can be automated)
# (Skip if dll/so is already in the natives folder)

# 3. Run Spring Boot Demo
cd ../demo-web
./gradlew bootRun
```
  * Chat API: `http://localhost:8080/api/ai/chat?message=HelloJPyRust&id=1`

### 3. Run with Docker (Recommended)
Use Docker to test in a clean environment without Python or Rust installed.

```bash
# Build & Run Docker Image
docker build -t jpyrust-demo .
docker run -p 8080:8080 jpyrust-demo
```

### 4. Zero-Copy Image Processing Demo
Experience the Zero-Copy performance directly in your web browser.

1. Run Server: `./gradlew bootRun`
2. Access: `http://localhost:8080`
3. Features:
   - Image upload and real-time grayscale/process
   - **Zero-Copy Processing Time** visible in console logs