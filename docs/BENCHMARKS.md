# Benchmarks

JPyRust is compared with the alternative most teams would otherwise run: a persistent Python HTTP server on the same machine, called with `java.net.http.HttpClient`. Both sides run the same trivial Python function, so the numbers measure the bridge, not the work.

## Results

Apple M5 (10 cores), macOS, OpenJDK 17.0.18, CPython 3.11.18, JMH 1.36, 2026-11-27.

**Round-trip latency, one caller** (`CallLatencyBenchmark`, echo of `size` bytes, sample mode)

| Payload | JPyRust p50 / p99 | Python HTTP server p50 / p99 | JPyRust is |
| --- | --- | --- | --- |
| 16 B | 10.8 / 18.8 µs | 68.0 / 144.6 µs | 6.3× faster |
| 64 KB | 26.7 / 38.6 µs | 99.6 / 164.1 µs | 3.7× faster |
| 1 MB | 150.3 / 207.6 µs | 615.4 / 938.0 µs | 4.1× faster |

**CPU-bound throughput, four Java threads** (`ParallelThroughputBenchmark`, a pure-Python loop of 200,000 iterations per call, about 3.4 ms)

| Setup | Calls per second |
| --- | --- |
| JPyRust, `workers(1)` | 292 ± 24 |
| JPyRust, `workers(4)` | 930 ± 43 |
| Python `ThreadingHTTPServer` (one process, threads) | 247 ± 44 with the `workers=1` run, 283 ± 27 with the `workers=4` run |

The HTTP server has one process, so its threads share one GIL and four callers get about one core of Python. `workers(4)` runs four Python processes and scales 3.2× on four callers.

## What this does and does not show

* **Shown:** for in-machine calls, shared memory plus a pipe beats HTTP over loopback at every payload size measured, and `workers(n)` turns CPU-bound Python into multi-core throughput without a separate service.
* **Not shown:** a production HTTP stack with several worker processes (for example `uvicorn --workers 4`) also scales across cores; JPyRust's advantage there is latency and not having to deploy and operate the service. Jep and GraalPy were not measured.
* JMH ran one fork, three 2-second warm-up iterations and five 2-second measurement iterations per case. Results vary with hardware; run them yourself.

## Running

```bash
make native                     # build the native library for this machine
./gradlew :benchmarks:jmh       # about 5 minutes; results in benchmarks/build/results/jmh/results.json
```

The first run downloads Python into `~/.jpyrust/cache` and builds a venv in the system temp directory under `jpyrust-bench/`. The HTTP server is started from that venv's interpreter, so both sides use the same Python.

## Footprint

| Item | Size |
| --- | --- |
| JAR, five platforms | 1.6 MB |
| Core venv | 20 MB, plus the 26 MB downloaded interpreter shared by all instances |
| Idle Python worker (RSS) | 22 MB |
| First `initialize()`, empty requirements, interpreter already cached | about 1.1 s |
