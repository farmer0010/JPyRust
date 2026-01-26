@echo off
setlocal EnableDelayedExpansion

echo ============================================
echo  JPyRust Clean Python Environment Setup
echo ============================================
echo.

set "TEMP_DIR=C:\jpyrust_temp"
set "VENV_DIR=%TEMP_DIR%\venv"
set "PYTHON_EXE=%TEMP_DIR%\python.exe"

REM Step 1: Check if bundled Python exists
echo [Step 1/4] Checking bundled Python...
if not exist "%PYTHON_EXE%" (
    echo ERROR: Bundled Python not found at %PYTHON_EXE%
    echo Please ensure the Python distribution is extracted to %TEMP_DIR%
    pause
    exit /b 1
)

REM Get Python version
echo Python location: %PYTHON_EXE%
"%PYTHON_EXE%" --version
echo.

REM Step 2: Force delete existing venv
echo [Step 2/4] Deleting existing virtual environment...
if exist "%VENV_DIR%" (
    echo Found existing venv at %VENV_DIR%
    echo Removing... (this may take a moment)
    rmdir /s /q "%VENV_DIR%" 2>nul
    if exist "%VENV_DIR%" (
        echo WARNING: Standard removal failed, attempting force delete...
        del /f /s /q "%VENV_DIR%\*" >nul 2>&1
        rmdir /s /q "%VENV_DIR%" 2>nul
    )
    if not exist "%VENV_DIR%" (
        echo Successfully deleted old venv.
    ) else (
        echo ERROR: Could not delete %VENV_DIR%
        echo Please close any programs using this folder and try again.
        pause
        exit /b 1
    )
) else (
    echo No existing venv found. Clean slate!
)
echo.

REM Step 3: Create new venv
echo [Step 3/4] Creating new virtual environment...
"%PYTHON_EXE%" -m venv "%VENV_DIR%"
if errorlevel 1 (
    echo ERROR: Failed to create virtual environment.
    pause
    exit /b 1
)
echo Virtual environment created successfully.
echo.

REM Step 4: Install dependencies
echo [Step 4/4] Installing dependencies with fresh cache...
echo Using --no-cache-dir and --force-reinstall to ensure clean binaries.
echo.

call "%VENV_DIR%\Scripts\activate.bat"

pip install --no-cache-dir --force-reinstall ultralytics numpy opencv-python-headless

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
echo Virtual environment: %VENV_DIR%
echo.
echo To activate manually, run:
echo   %VENV_DIR%\Scripts\activate.bat
echo.
echo Python packages installed:
pip list | findstr /i "ultralytics numpy opencv"
echo.

pause
