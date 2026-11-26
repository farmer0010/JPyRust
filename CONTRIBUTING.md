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

Run the same checks CI runs before opening a PR. On macOS/Linux, `make check` runs all of them (it creates `.venv` with `uv` on first use). The commands it wraps:

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

To try a Rust change from Java locally, run `make native`. It builds `rust-bridge` and copies the result over the bundled file for your platform under `java-api/src/main/resources/natives/<os>-<arch>/`. Restore it with `git checkout -- java-api/src/main/resources/natives` before committing.

Other targets: `make jar` builds the library JAR, and `make provision-test` provisions Python from scratch into a temp directory and checks that the daemon answers (slow: it installs torch).

`make` isn't available on Windows by default; use the commands above directly.

After changing `requirements.txt` or the Python version, re-check YOLO/Whisper output against `baseline/` as described in [`test-fixtures/README.md`](test-fixtures/README.md).

## Pull requests

- Branch from `main`.
- Use a Conventional Commits prefix in the title: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.
- Add an entry under **Unreleased** in both `CHANGELOG.md` and `CHANGELOG_KR.md` for user-visible changes. Mark behavior changes explicitly.
- Do not commit locally built native binaries in a feature PR. Bundled binaries are replaced from CI artifacts in a separate PR.
- Line endings are LF, enforced by `.gitattributes` (`.bat`/`.cmd`/`.ps1` stay CRLF).
- CI must pass.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).
