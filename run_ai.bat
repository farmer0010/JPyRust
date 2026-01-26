@echo off
REM Rust passes: [script_path] [width] [height] [channels]
REM We want to run: python.exe [script_path] [width] [height] [channels]

REM Use %* to pass ALL arguments provided by Rust directly to Python.
"C:\jpyrust_temp\python.exe" %*
