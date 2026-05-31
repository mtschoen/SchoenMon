@echo off
setlocal enabledelayedexpansion

echo ==========================================================
echo               PerfStream Emulator Launcher
echo ==========================================================
echo.

set CLI_PATH=C:\Users\mtsch\AppData\AndroidCLI\android.exe
set AVD_NAME=Medium_Phone_API_36.1

if not exist "!CLI_PATH!" (
    echo [ERROR] Android CLI not found at !CLI_PATH!
    goto error
)

echo [1/2] Starting Android Emulator: !AVD_NAME!...
echo (This will block until the emulator is fully booted and ready...)
echo.
"!CLI_PATH!" emulator start !AVD_NAME!
if %ERRORLEVEL% neq 0 (
    echo [WARNING] Emulator command returned non-zero code. It might already be running or starting.
)

echo.
echo [2/2] Compiling, deploying, and running PerfStream...
echo.
"!CLI_PATH!" run --activity=com.example.perfstream.MainActivity
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Failed to compile or deploy the application.
    goto error
)

echo.
echo ==========================================================
echo [SUCCESS] PerfStream is running on the emulator!
echo ==========================================================
goto end

:error
echo.
echo [FAILURE] Failed to launch or run. Check output above for errors.
pause
exit /b 1

:end
pause
exit /b 0
