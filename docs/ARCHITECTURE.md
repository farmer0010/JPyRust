# JPyRust Architecture & Guidelines

## 1. Project Vision

**JPyRust** is a high-performance bridge library enabling Java applications to use the Python AI ecosystem with **Production-Grade Performance**.

The system achieves **778x speedup** (7s → 9ms) through:
- **Daemon Mode**: Python stays warm with models pre-loaded
- **UUID Isolation**: Thread-safe concurrent request handling
- **Task Dispatching**: Multiple AI tasks via single daemon

---

## 2. Core Architecture (v2.0 - Universal Bridge)

```
[Java App] -> (JNI) -> [Rust Bridge] -> (stdin/stdout) -> [Python Daemon]
                              |
                       File-based IPC
                    input_{uuid}.dat
                    output_{uuid}.dat
```

### Layer 1: Java API (User-Facing)
- **Controllers**: `AIImageController`, `AITextController`
- **Bridge**: `JPyRustBridge.java` with `executeTask()` native method
- **Config**: `application.yml` for paths and settings

### Layer 2: Rust Bridge (Process Manager)
- **Tech**: `jni-rs`, `lazy_static`
- **Role**: Spawn and manage Python daemon lifecycle
- **IPC**: stdin/stdout with `EXECUTE` protocol
- **Resilience**: Auto-restart on daemon crash

### Layer 3: Python Daemon (AI Worker)
- **File**: `ai_worker.py`
- **Mode**: Persistent loop (not one-shot)
- **Tasks**: YOLO (image), SENTIMENT (text)
- **Models**: Loaded once at startup

---

## 3. IPC Protocol

### Command Format
```
EXECUTE <task_type> <request_id> <metadata...>
```

### Supported Tasks
| Task | Metadata | Input File | Output File |
|------|----------|------------|-------------|
| YOLO | `width height channels` | Raw BGR bytes | JPEG bytes |
| SENTIMENT | `NONE` | UTF-8 text | UTF-8 result |

### Response Format
```
READY              # Initialization complete
DONE <result>      # Success
ERROR <message>    # Failure
```

---

## 4. File-Based Data Transfer

### Why Files Instead of stdin?
- Binary data (images) is complex over stdin
- UUID-named files enable concurrent safety
- Easy debugging (files persist for inspection)

### File Naming Convention
```
{work_dir}/input_{uuid}.dat   # Request data
{work_dir}/output_{uuid}.dat  # Response data
```

### File Format
```
[4 bytes: length (big-endian)] [N bytes: data]
```

---

## 5. Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| First Request | ~7s | Model loading (YOLO) |
| Text Analysis | ~9ms | After warmup |
| Image Detection | ~60-100ms | Includes resize + inference |
| Memory (Daemon) | ~500MB | YOLO model in GPU/CPU |

---

## 6. Development Rules

1. **UUID for Everything**: Never use fixed filenames
2. **Cleanup After Use**: Delete temp files post-processing
3. **Graceful Errors**: Return `ERROR` message, don't crash
4. **Flush Always**: Use `flush=True` on Python prints
5. **Timeout Protection**: 60s limit on daemon startup

---

## 7. Extension Guide

### Adding a New Task

1. **Python** (`ai_worker.py`):
   ```python
   def handle_newtask(request_id, metadata):
       # Read input_{request_id}.dat
       # Process
       # Write output_{request_id}.dat
       return "DONE result"
   
   TASK_HANDLERS["NEWTASK"] = handle_newtask
   ```

2. **Java** (`JPyRustBridge.java`):
   ```java
   public byte[] processNewTask(String input) {
       return execute("NEWTASK", "NONE", input.getBytes());
   }
   ```

3. **Controller**:
   ```java
   @PostMapping("/api/ai/newtask")
   public ResponseEntity<String> newTask(@RequestBody String input) {
       return ResponseEntity.ok(bridge.processNewTask(input));
   }
   ```

---

<p align="center"><i>Last updated: 2026-01-26 (v2.0 Universal Bridge)</i></p>
