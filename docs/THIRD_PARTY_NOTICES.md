# Third-Party Notices

The JPyRust JAR bundles a native library built from `rust-bridge/`, which statically links the Rust crates
below. Each is available under the MIT license, which JPyRust uses for all of them.
Regenerate this file with `make notices` after changing `rust-bridge/Cargo.lock`.

| Crate | Version | License | Copyright holders |
| --- | --- | --- | --- |
| [bitflags](https://github.com/bitflags/bitflags) | 1.3.2 | MIT/Apache-2.0 | The Rust Project Developers |
| [bytes](https://github.com/tokio-rs/bytes) | 1.11.0 | MIT | Carl Lerche, Sean McArthur |
| [cesu8](https://github.com/emk/cesu8-rs) | 1.1.0 | Apache-2.0/MIT | Eric Kidd |
| [cfg-if](https://github.com/rust-lang/cfg-if) | 1.0.4 | MIT OR Apache-2.0 | Alex Crichton |
| [combine](https://github.com/Marwes/combine) | 4.6.7 | MIT | Markus Westerlind |
| [getrandom](https://github.com/rust-random/getrandom) | 0.2.17 | MIT OR Apache-2.0 | The Rand Project Developers |
| [jni](https://github.com/jni-rs/jni-rs) | 0.21.1 | MIT/Apache-2.0 | Josh Chase |
| [jni-sys](https://github.com/sfackler/rust-jni-sys) | 0.3.0 | MIT/Apache-2.0 | Steven Fackler |
| [libc](https://github.com/rust-lang/libc) | 0.2.180 | MIT OR Apache-2.0 | The Rust Project Developers |
| [log](https://github.com/rust-lang/log) | 0.4.29 | MIT OR Apache-2.0 | The Rust Project Developers |
| [memchr](https://github.com/BurntSushi/memchr) | 2.7.6 | Unlicense OR MIT | Andrew Gallant, bluss |
| [memoffset](https://github.com/Gilnaa/memoffset) | 0.6.5 | MIT | Gilad Naaman |
| [nix](https://github.com/nix-rust/nix) | 0.23.2 | MIT | The nix-rust Project Developers |
| [ppv-lite86](https://github.com/cryptocorrosion/cryptocorrosion) | 0.2.21 | MIT OR Apache-2.0 | The CryptoCorrosion Contributors |
| [rand](https://github.com/rust-random/rand) | 0.8.5 | MIT OR Apache-2.0 | The Rand Project Developers, The Rust Project Developers |
| [rand_chacha](https://github.com/rust-random/rand) | 0.3.1 | MIT OR Apache-2.0 | The Rand Project Developers, The Rust Project Developers, The CryptoCorrosion Contributors |
| [rand_core](https://github.com/rust-random/rand) | 0.6.4 | MIT OR Apache-2.0 | The Rand Project Developers, The Rust Project Developers |
| [shared_memory](https://github.com/elast0ny/shared_memory-rs) | 0.12.4 | MIT OR Apache-2.0 | ElasT0ny |
| [thiserror](https://github.com/dtolnay/thiserror) | 1.0.69 | MIT OR Apache-2.0 | David Tolnay |
| [win-sys](https://github.com/elast0ny/win-sys) | 0.3.1 | MIT OR Apache-2.0 | ElasT0ny |
| [windows](https://github.com/microsoft/windows-rs) | 0.34.0 | MIT OR Apache-2.0 | Microsoft |
| [windows-link](https://github.com/microsoft/windows-rs) | 0.2.1 | MIT OR Apache-2.0 | The windows-link authors |
| [windows-sys](https://github.com/microsoft/windows-rs) | 0.45.0 | MIT OR Apache-2.0 | Microsoft |
| [windows-sys](https://github.com/microsoft/windows-rs) | 0.61.2 | MIT OR Apache-2.0 | The windows-sys authors |
| [windows-targets](https://github.com/microsoft/windows-rs) | 0.42.2 | MIT OR Apache-2.0 | Microsoft |
| [windows_x86_64_msvc](https://github.com/microsoft/windows-rs) | 0.34.0 | MIT OR Apache-2.0 | Microsoft |
| [windows_x86_64_msvc](https://github.com/microsoft/windows-rs) | 0.42.2 | MIT OR Apache-2.0 | Microsoft |
| [zerocopy](https://github.com/google/zerocopy) | 0.8.33 | BSD-2-Clause OR Apache-2.0 OR MIT | Joshua Liebow-Feeser, Jack Wrenn |

## MIT License

Copyright (c) the copyright holders listed above for each crate.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
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
USE OR OTHER DEALINGS IN THE SOFTWARE.

## Repository-only assets

`test-fixtures/bus.jpg` is the demo image shipped in the `ultralytics` package (AGPL-3.0). It is used only
by repository tests and is not included in the JAR.
