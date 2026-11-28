# JPyRust Architecture

How a call travels from Java to Python and back, and where each piece of the code lives. The wire protocol described here is internal (see [COMPATIBILITY.md](COMPATIBILITY.md)); it is documented for contributors.

## 1. Layers

```
Java (JPyRustBridge, NDArray)
   │  JNI: executeTask / streamStart / streamNext / streamCancel
Rust (rust-bridge/src/lib.rs, one BridgeState per worker)
   │  stdin/stdout lines            shared memory or files for the data
Python (python-core/ai_worker.py, one process per worker)
   │  @task functions from jpyrust.py, extras/*.py and moduleDir(...)
```

| Layer | Owns |
| --- | --- |
| `JPyRustBridge.java` | Provisioning (download CPython, venv, pip), the worker pool (`workers(n)`), `call` / `callAsync` / `callStream`, the exception types |
| `NativeLoader.java` | Extracting `natives/<os>-<arch>/jpyrust.*` and the Python files from the JAR |
| `NDArray.java` | Typed arrays as NumPy `.npy` bytes |
| `lib.rs` | One Python process per `BridgeState`: spawn, `READY` handshake, timeouts, restart after a crash, shared-memory buffers, the line protocol |
| `ai_worker.py` | The daemon loop, task registry, input decoding and output encoding |
| `jpyrust.py` | The public Python API: `@task`, `config` |

## 2. Worker pool

`workers(n)` creates `n` `BridgeState`s, each with its own Python process, and keeps them in a blocking queue. A call takes a worker, runs, and puts it back, so calls from `n` threads run in parallel and a call never shares a process with another. A stream keeps its worker until it ends or is closed. `close()` takes the pool's write lock, so it waits for running calls and then kills every process.

## 3. Line protocol

Rust writes one command line to the worker's stdin and reads lines from its stdout until it sees a reply; any other output (prints from user code) is ignored.

| Rust → Python | Python → Rust | Meaning |
| --- | --- | --- |
| *(start)* | `READY` | Modules imported; sent once at startup |
| `EXECUTE <task> <id> <metadata...>` | `DONE <n>` | Output of `n` bytes is in the output buffer |
| | `DONE <n> FILE` | Output did not fit; it is in `output_<id>.dat` |
| | `ERROR <message>` | The task raised; becomes `TaskFailedException` |
| `STREAM <task> <id> <metadata...>` | `CHUNK <n>` / `CHUNK <n> FILE` | One yielded value, placed like a `DONE` result |
| `NEXT` | next `CHUNK`, `END` or `ERROR` | Sent after Rust has read a chunk; Python resumes the generator only now |
| `CANCEL` | `END` | Sent instead of `NEXT` when Java closes the stream; Python closes the generator |
| `EXIT` | *(process exits)* | |

`<metadata...>` is `SHMEM <input name> <input length> <output name> <output capacity> <args...>` when shared memory is used, otherwise just `<args...>` with the input in `input_<id>.dat`. Data files are a 4-byte big-endian length followed by the bytes.

Every wait for a reply has a deadline: the startup timeout for `READY`, the task timeout for each `DONE`, `CHUNK` or `END`. A missed deadline or a closed pipe kills the process; the next call starts a new one.

## 4. Shared memory

Each worker owns two named shared-memory buffers, input and output, that are reused across calls. They start at 1 MB and grow to the next power of two when a payload needs more, up to 256 MB (on Linux, at most half of the free space in `/dev/shm`). Python attaches to them by name and caches the handle. An output larger than the output buffer is written to a file for that call, and the buffer grows for the next one. If shared memory cannot be created at all, the worker switches to files for the rest of its life.

## 5. Data conversion in the worker

| Input bytes | Task receives |
| --- | --- |
| start with `\x93NUMPY` | a read-only `numpy.ndarray` viewing the received bytes |
| anything else | `bytes` |

| Task returns | Sent as |
| --- | --- |
| `bytes`, `bytearray`, `memoryview` | as-is |
| `str` | UTF-8 |
| `numpy.ndarray` | `.npy` (little-endian, C order), header and data written separately into the buffer |
| an iterator or generator | `ERROR` for `EXECUTE`; one `CHUNK` per element for `STREAM` |
| anything else | JSON |

## 6. Adding a task

Register a function with `@task` in a module passed to `moduleDir(...)`; there is nothing to change in Java or Rust.

```python
from jpyrust import task

@task("RESIZE")
def resize(image, args):          # numpy.ndarray when Java sends an NDArray
    width, height = map(int, args.split())
    return cv2.resize(image, (width, height))
```

Built-in tasks live in `python-core/extras/<name>.py` with their requirements in `<name>.txt`, and are loaded only when `extras("<name>")` is set.

## 7. Rules for contributors

1. Every reply line is printed with `flush=True`; a buffered reply looks like a hang.
2. Request ids are UUIDs, so file names never collide between workers.
3. A Python exception must become an `ERROR` line, never a crashed process.
4. Anything that waits on Python has a timeout and kills the process when it fires.
5. Changes to the protocol go in `lib.rs`, `ai_worker.py`, the Python tests and this document together.
