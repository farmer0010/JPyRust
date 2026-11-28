import json
import re
import subprocess
import sys

TARGETS = ["x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "x86_64-pc-windows-msvc",
           "aarch64-apple-darwin", "x86_64-apple-darwin"]

MIT_TEXT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
USE OR OTHER DEALINGS IN THE SOFTWARE."""

linked = {}
for target in TARGETS:
    metadata = json.loads(subprocess.check_output(
        ["cargo", "metadata", "--format-version", "1", "--manifest-path", "rust-bridge/Cargo.toml",
         "--filter-platform", target]))
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    seen, stack = set(), [metadata["resolve"]["root"]]
    while stack:
        for dep in nodes[stack.pop()]["deps"]:
            pkg = packages[dep["pkg"]]
            normal = any(kind["kind"] is None for kind in dep["dep_kinds"])
            if normal and "proc-macro" not in pkg["targets"][0]["kind"] and pkg["id"] not in seen:
                seen.add(pkg["id"])
                linked[pkg["id"]] = pkg
                stack.append(pkg["id"])

rows = []
for pkg in sorted(linked.values(), key=lambda p: (p["name"], p["version"])):
    license_expr = pkg.get("license") or ""
    if "MIT" not in re.split(r"[\s/()]+", license_expr):
        sys.exit(f"{pkg['name']} {pkg['version']} is not available under MIT: {license_expr}")
    holders = ", ".join(re.sub(r"\s*<[^>]*>", "", a) for a in pkg.get("authors", [])) or f"The {pkg['name']} authors"
    rows.append(f"| [{pkg['name']}]({pkg.get('repository') or 'https://crates.io/crates/' + pkg['name']}) "
                f"| {pkg['version']} | {license_expr} | {holders} |")

sys.stdout.write("\n".join([
    "# Third-Party Notices",
    "",
    "The JPyRust JAR bundles a native library built from `rust-bridge/`, which statically links the Rust crates",
    "below. Each is available under the MIT license, which JPyRust uses for all of them.",
    "Regenerate this file with `make notices` after changing `rust-bridge/Cargo.lock`.",
    "",
    "| Crate | Version | License | Copyright holders |",
    "| --- | --- | --- | --- |",
    *rows,
    "",
    "## MIT License",
    "",
    "Copyright (c) the copyright holders listed above for each crate.",
    "",
    MIT_TEXT,
    "",
    "## Repository-only assets",
    "",
    "`test-fixtures/bus.jpg` is the demo image shipped in the `ultralytics` package (AGPL-3.0). It is used only",
    "by repository tests and is not included in the JAR.",
    "",
]))
