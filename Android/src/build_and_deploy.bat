@echo off
setlocal enabledelayedexpansion

:: Always run from the directory this script lives in (Android\src)
cd /d "%~dp0"

set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

echo ============================================
echo  Edge AI - Build and Deploy
echo ============================================
echo.

:: ── Check ADB ────────────────────────────────
if not exist "%ADB%" (
  echo [ERROR] ADB not found at %ADB%
  echo         Install platform-tools or update the ADB path in this script.
  pause & exit /b 1
)

:: ── Check device connected ───────────────────
echo [1/4] Checking USB device...
"%ADB%" devices | findstr /R "device$" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] No Android device detected.
  echo         - Enable USB Debugging on your phone
  echo         - Accept the RSA fingerprint prompt on the device
  echo         - Try a different USB cable / port
  pause & exit /b 1
)
echo       Device found.
echo.

:: ── Build ─────────────────────────────────────
echo [2/4] Building debug APK (this takes a few minutes)...
call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 (
  echo.
  echo [ERROR] Build failed. Check the output above for details.
  pause & exit /b 1
)
echo.
echo       Build successful.
echo.

:: ── Install ───────────────────────────────────
echo [3/4] Installing APK on device...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo.
  echo [ERROR] Install failed.
  echo         If you see INSTALL_FAILED_UPDATE_INCOMPATIBLE, run:
  echo           %ADB% uninstall com.edge.ai.gallery
  echo         then run this script again.
  pause & exit /b 1
)
echo.
echo       Install successful.
echo.

:: ── Launch ────────────────────────────────────
echo [4/4] Launching Edge AI...
"%ADB%" shell am start -n com.edge.ai.gallery/com.google.ai.edge.gallery.MainActivity
echo.
echo ============================================
echo  Done! Edge AI is running on your device.
echo ============================================
echo.
pause
