# Security Policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please do not open a public issue for security problems.

Report privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**.

Please include the affected version, platform (OS and architecture), and steps to reproduce.

## Areas of particular interest

- Shared memory between the JVM and the Python daemon (access control, especially on Windows)
- Native library extraction and loading (`NativeLoader`)
- Python plugin loading (every `.py` file in `plugins/` next to `ai_worker.py` is imported)
- The Python environment provisioned under `~/.jpyrust/<instanceId>/`
