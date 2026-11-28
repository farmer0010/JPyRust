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
| Python (downloaded) | CPython 3.11.18 on macOS/Linux, 3.11.9 on Windows | Every Java test job |
| Python (`python(Path)`) | 3.11 tested; other CPython 3.x with `venv` and `pip` should work, but the built-in extras pin wheels for 3.11 | Windows job (setup-python 3.11) |

The JitPack build carries natives for Linux x86_64, macOS Apple Silicon and Windows x86_64. The JAR attached to each GitHub Release carries all five.

## Versioning

JPyRust follows [Semantic Versioning](https://semver.org/) from 2.0.0.

The public API is:

* the public classes and methods in `com.jpyrust` (`JPyRustBridge` and the exception classes),
* the Python `jpyrust` module (`task`, `config`),
* the task contract: `(data: bytes, args: str)` in, `bytes`/`str`/JSON-serializable out,
* the names and behavior of the built-in extras and their tasks.

The wire protocol between the native library and `ai_worker.py`, the layout of `~/.jpyrust`, `NativeLoader` and `runPythonRaw` are internal and can change in any release.

| Change | Version bump |
| --- | --- |
| Removing or renaming public API, changing an exception type, changing a default that alters results | Major |
| New methods, extras or options with unchanged defaults | Minor |
| Bug fixes, dependency pins, performance | Patch |

Every release lists behavior changes first in [CHANGELOG.md](CHANGELOG.md). Deprecated API keeps working for at least one minor release before removal in the next major.
