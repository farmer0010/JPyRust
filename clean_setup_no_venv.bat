@echo off
setlocal EnableDelayedExpansion

echo ============================================
echo  JPyRust Clean Python Setup (No Venv)
echo ============================================
echo.
echo NOTE: The embeddable Python distribution lacks venv.
echo We will install dependencies directly into the Lib folder.
echo.

set "TEMP_DIR=C:\jpyrust_temp"
set "PYTHON_EXE=%TEMP_DIR%\python.exe"
set "LIB_DIR=%TEMP_DIR%\Lib"
set "SITE_PACKAGES=%LIB_DIR%\site-packages"

REM Step 1: Check if bundled Python exists
echo [Step 1/5] Checking bundled Python...
if not exist "%PYTHON_EXE%" (
    echo ERROR: Bundled Python not found at %PYTHON_EXE%
    pause
    exit /b 1
)

echo Python location: %PYTHON_EXE%
"%PYTHON_EXE%" --version
echo.

REM Step 2: Delete old venv if exists (cleanup)
echo [Step 2/5] Cleaning up old venv folder...
if exist "%TEMP_DIR%\venv" (
    rmdir /s /q "%TEMP_DIR%\venv" 2>nul
    echo Deleted old venv folder.
) else (
    echo No old venv folder found.
)
echo.

REM Step 3: Clean existing site-packages
echo [Step 3/5] Cleaning existing site-packages...
if exist "%SITE_PACKAGES%" (
    echo Removing %SITE_PACKAGES%...
    rmdir /s /q "%SITE_PACKAGES%" 2>nul
)
mkdir "%SITE_PACKAGES%"
echo Created clean site-packages directory.
echo.

REM Step 4: Modify python310._pth to enable site-packages
echo [Step 4/5] Configuring Python path...
set "PTH_FILE=%TEMP_DIR%\python310._pth"
echo python310.zip> "%PTH_FILE%"
echo .>> "%PTH_FILE%"
echo Lib>> "%PTH_FILE%"
echo Lib\site-packages>> "%PTH_FILE%"
echo import site>> "%PTH_FILE%"
echo Configured %PTH_FILE%
type "%PTH_FILE%"
echo.

REM Step 5: Install pip first, then dependencies
echo [Step 5/5] Installing pip and dependencies...
echo Downloading get-pip.py...
curl -sSL https://bootstrap.pypa.io/get-pip.py -o "%TEMP_DIR%\get-pip.py"
if errorlevel 1 (
    echo ERROR: Failed to download get-pip.py
    pause
    exit /b 1
)

echo Installing pip...
"%PYTHON_EXE%" "%TEMP_DIR%\get-pip.py" --no-warn-script-location
if errorlevel 1 (
    echo ERROR: Failed to install pip
    pause
    exit /b 1
)

echo.
echo Installing dependencies with --no-cache-dir --force-reinstall...
"%PYTHON_EXE%" -m pip install --no-cache-dir --force-reinstall ultralytics numpy opencv-python-headless

if errorlevel 1 (
    echo.
    echo ERROR: pip install failed!
    pause
    exit /b 1
)

echo.
echo ============================================
echo  Setup Complete!
echo ============================================
echo.
echo Python: %PYTHON_EXE%
echo.
echo Installed packages:
"%PYTHON_EXE%" -m pip list | findstr /i "ultralytics numpy opencv"
echo.
echo Test import (should not show any errors):
"%PYTHON_EXE%" -c "import numpy; import cv2; print('Imports OK!')"
echo.

pause
