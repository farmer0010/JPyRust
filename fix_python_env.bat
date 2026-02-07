@echo off
setlocal EnableDelayedExpansion
echo [Fix] Killing processes...
taskkill /F /IM java.exe 2>nul
taskkill /F /IM python.exe 2>nul
timeout /t 2 /nobreak >nul

set "TEMP_DIR=C:\jpyrust_temp"
set "PYTHON_EXE=%TEMP_DIR%\python.exe"

echo [Fix] Configuring python311._pth...
set "PTH_FILE=%TEMP_DIR%\python311._pth"
echo python311.zip> "%PTH_FILE%"
echo .>> "%PTH_FILE%"
echo Lib>> "%PTH_FILE%"
echo Lib\site-packages>> "%PTH_FILE%"
echo import site>> "%PTH_FILE%"

echo [Fix] Installing dependencies...
"%PYTHON_EXE%" -m pip install --no-cache-dir --force-reinstall textblob scikit-learn pandas numpy opencv-python-headless ultralytics requests pillow
if errorlevel 1 (
    echo [Error] Pip install failed. Trying to ensure pip exists...
    curl -sSL https://bootstrap.pypa.io/get-pip.py -o "%TEMP_DIR%\get-pip.py"
    "%PYTHON_EXE%" "%TEMP_DIR%\get-pip.py"
    "%PYTHON_EXE%" -m pip install textblob scikit-learn pandas numpy opencv-python-headless ultralytics requests pillow
)

echo [Fix] Done.
