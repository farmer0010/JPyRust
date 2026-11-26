# Contributing to JPyRust

Thanks for helping out. Issues and pull requests in English or Korean are both welcome.

## Repository layout

| Directory | What it is |
| --- | --- |
| `rust-bridge/` | JNI native library (`cdylib`): spawns the Python daemon, creates shared memory, relays tasks |
| `java-api/` | The published Java library (`JPyRustBridge`, `NativeLoader`) and bundled native binaries |
| `python-core/` | `ai_worker.py` (the daemon) and its tests |
| `demo-web/` | Spring Boot demo app that uses `java-api` |
| `test-fixtures/`, `baseline/` | Inputs and recorded outputs for manual YOLO/Whisper regression checks |

## Prerequisites

- JDK 17
- Rust (stable)
- [`uv`](https://docs.astral.sh/uv/) on macOS/Linux (the library provisions its Python 3.11 venv with it)
- Python 3.11 for the Python tests

## Build and test

Run the same checks CI runs before opening a PR.

```bash
# Rust
cd rust-bridge
cargo build --release
cargo clippy --all-targets -- -D warnings
cargo test
cd ..

# Java
./gradlew :java-api:test :demo-web:compileJava

# Python
uv venv --python 3.11 .venv
uv pip install --python .venv numpy opencv-python-headless pytest
.venv/bin/python -m pytest python-core/tests
```

To try a Rust change from Java locally, copy your build output (`rust-bridge/target/release/libjpyrust.dylib`, `libjpyrust.so` or `jpyrust.dll`) over the bundled file for your platform under `java-api/src/main/resources/natives/`.

After changing `requirements.txt` or the Python version, re-check YOLO/Whisper output against `baseline/` as described in [`test-fixtures/README.md`](test-fixtures/README.md).

## Pull requests

- Branch from `main`.
- Use a Conventional Commits prefix in the title: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.
- Add an entry under **Unreleased** in both `CHANGELOG.md` and `CHANGELOG_KR.md` for user-visible changes. Mark behavior changes explicitly.
- Do not commit locally built native binaries in a feature PR. Bundled binaries are replaced from CI artifacts in a separate PR.
- Keep each file's existing line endings. Many files use CRLF, and converting a whole file turns a small change into a full-file diff.
- CI must pass.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).
