# Compatibility and Versioning

## Support matrix

| Component | Supported | Tested in CI |
| --- | --- | --- |
| JDK | 17 or newer | Temurin 17 |
| Spring Boot (`jpyrust-spring-boot-starter`) | 3.2 or newer, including 4.x | 3.2.1 and 4.2.0, with a real Python worker |
| Linux x86_64 (glibc) | Yes | `ubuntu-latest`, with a real Python worker |
| Linux aarch64 (glibc) | Yes | `ubuntu-24.04-arm`, with a real Python worker |
| macOS Apple Silicon | Yes | `macos-latest`, with a real Python worker |
| macOS Intel | Yes | Built only (cross-compiled, no Intel runner) |
| Windows x86_64 | Yes | `windows-latest`, with a real Python worker and the full `vision`/`audio`/`ml` install |
| Linux musl (Alpine) | No native library yet | — |
| Python (downloaded) | CPython 3.11.18 (default), 3.12.16, 3.13.18 or 3.14.10 via `pythonVersion(...)`, on every OS | 3.11 in every Java test job; switching 3.12 → 3.13 → 3.14 in every Java test job |
| Built-in extras (`vision`, `audio`, `ml`) | Python 3.11 and 3.12 | 3.11, full install on Windows |
| NVIDIA GPUs (`devices`, `torch`) | Linux and Windows x86_64 with an NVIDIA driver; torch builds from download.pytorch.org | Not tested: CI has no GPU runners. Device pinning, OOM handling and the `cpu` torch build are tested on CPU |
| Python (`python(Path)`) | 3.11 tested; other CPython 3.x with `venv` and `pip` should work, but the built-in extras pin wheels for 3.11 | Windows job (setup-python 3.11) |

The JitPack build carries natives for Linux x86_64, macOS Apple Silicon and Windows x86_64. The JAR attached to each GitHub Release carries all five.

## Versioning

JPyRust follows [Semantic Versioning](https://semver.org/) from 2.0.0.

The public API is:

* the public classes and methods in `com.jpyrust` (`JPyRustBridge`, `NDArray` and the exception classes),
* the Python `jpyrust` module (`task`, `config`),
* the `jpyrust.*` system properties, `JPYRUST_*` environment variables and the `doctor` / `setup` / `version` commands,
* the task contract: `(data: bytes | numpy.ndarray, args: str)` in, `bytes`/`str`/`numpy.ndarray`/JSON-serializable out (or a generator of those for `callStream`), with arrays as NumPy `.npy` buffers,
* the names and behavior of the built-in extras and their tasks.

The wire protocol between the native library and `ai_worker.py`, the layout of `~/.jpyrust`, `NativeLoader` and `runPythonRaw` are internal and can change in any release.

| Change | Version bump |
| --- | --- |
| Removing or renaming public API, changing an exception type, changing a default that alters results | Major |
| New methods, extras or options with unchanged defaults | Minor |
| Bug fixes, dependency pins, performance | Patch |

Every release lists behavior changes first in [CHANGELOG.md](CHANGELOG.md). Deprecated API keeps working for at least one minor release before removal in the next major.
